package cc.ptoe.easytier.compose.rule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import cc.ptoe.easytier.compose.MainActivity
import cc.ptoe.easytier.compose.R
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.core.SessionStarter
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.transport.vpn.VpnPermissionRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Applies the selected profile's LAN rule while the app is not in front.
 *
 * Why a foreground service: the "start again after leaving home" half of the
 * rule needs a live process to observe connectivity, and the phone only has one
 * while a session is running. The service therefore lives independently of the
 * VPN session and is started only while the rule is enabled.
 *
 * Three signals feed it, because none is sufficient alone:
 *  * per-transport Wi-Fi/Ethernet callbacks — a default-network callback stops
 *    reporting the underlying link while the EasyTier VpnService owns the
 *    default route, which is how "arrived home" used to get lost;
 *  * a slow periodic sweep — a missed callback, or settings edited while the
 *    process was frozen, must not wedge the rule;
 *  * the evaluation on start — enabling the rule at home takes effect at once.
 */
class LanRuleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())
    private val evaluateRunnable = Runnable { evaluateAsync() }
    private val sweepRunnable = object : Runnable {
        override fun run() {
            evaluateAsync()
            handler.postDelayed(this, SWEEP_INTERVAL_MS)
        }
    }
    private val registrations = mutableListOf<ConnectivityManager.NetworkCallback>()

    /** null until the first evaluation, then whether the device sat on a rule subnet. */
    private var lastOnLocalNetwork: Boolean? = null

    /** Set while the profile list cannot be read; guards against stopping on a race. */
    private var missingTargetSince: Long? = null

    /**
     * A fresh callback per registration: [ConnectivityManager] keys registrations by
     * the callback instance, so one object cannot be registered for two requests.
     */
    private fun newCallback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleEvaluate()

        override fun onLost(network: Network) = scheduleEvaluate()

        // Deliberately no onCapabilitiesChanged: it fires on signal/bandwidth
        // updates and would re-apply the rule every few seconds.
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = scheduleEvaluate()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: LAN rule starting")
        startForegroundCompat()
        val manager = getSystemService(ConnectivityManager::class.java)
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable; stopping LAN rule")
            stopSelf()
            return
        }
        registerCallbacks(manager)
        // Evaluate once on start so a rule enabled while already at home takes
        // effect immediately instead of waiting for the next network change.
        scheduleEvaluate(delayMs = 0)
        handler.postDelayed(sweepRunnable, SWEEP_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service can be restarted by the system (START_STICKY) with a null intent.
        startForegroundCompat()
        scheduleEvaluate(delayMs = 0)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not silently disable the rule.
        Log.i(TAG, "task removed; LAN rule keeps running")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: LAN rule stopping")
        val manager = getSystemService(ConnectivityManager::class.java)
        registrations.forEach { registered ->
            runCatching { manager?.unregisterNetworkCallback(registered) }
        }
        registrations.clear()
        handler.removeCallbacks(evaluateRunnable)
        handler.removeCallbacks(sweepRunnable)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun registerCallbacks(manager: ConnectivityManager) {
        // One request per transport: NetworkRequest treats several addTransportType
        // calls as "must have all of them", which no network satisfies.
        intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET,
        ).forEach { transport ->
            val request = NetworkRequest.Builder().addTransportType(transport).build()
            val callback = newCallback()
            runCatching { manager.registerNetworkCallback(request, callback) }
                .onSuccess { registrations += callback }
                .onFailure { Log.w(TAG, "cannot watch transport $transport: ${it.message}") }
        }
    }

    private fun scheduleEvaluate(delayMs: Long = DEBOUNCE_MS) {
        handler.removeCallbacks(evaluateRunnable)
        handler.postDelayed(evaluateRunnable, delayMs)
    }

    private fun evaluateAsync() {
        scope.launch {
            runCatching { evaluate() }
                .onFailure { Log.w(TAG, "evaluate failed: ${it.message}") }
        }
    }

    private suspend fun evaluate() {
        val target = SessionStarter.loadTarget(this)
        if (target == null) {
            // An unreadable profile list is usually a cold-start race (DataStore has
            // not emitted yet); stopping immediately here is what killed the rule
            // right after boot.
            val now = SystemClock.elapsedRealtime()
            val since = missingTargetSince ?: now.also { missingTargetSince = now }
            if (now - since > MISSING_TARGET_GRACE_MS) {
                Log.w(TAG, "no profile for ${now - since}ms; stopping LAN rule")
                stopSelf()
            } else {
                Log.i(TAG, "no profile yet; retrying on next evaluation")
            }
            return
        }
        missingTargetSince = null
        if (!target.profile.autoSwitchEnabled) {
            Log.i(TAG, "rule disabled; stopping")
            stopSelf()
            return
        }
        val engine = LanRuleEngine(target.profile.autoSwitchCidrs.mapNotNull { Ipv4Cidr.parseOrNull(it) })
        if (!engine.enabled) {
            Log.i(TAG, "no usable local subnet configured; stopping")
            stopSelf()
            return
        }
        val snapshot = LanNetworkReader.read(this)
        val onLocalNetwork = engine.matches(snapshot)
        // Only entering a local network (and the first observation) may stop a
        // running session; staying on it must not undo a manual reconnect.
        val entering = lastOnLocalNetwork != true
        lastOnLocalNetwork = onLocalNetwork
        val coordinator = EasyTierRuntimeCoordinator.existingInstance()
            ?: EasyTierRuntimeCoordinator.getInstance(this, NO_OP_REQUESTER)
        val sessionActive = coordinator.status.value.state in ACTIVE_STATES
        val action = engine.decide(
            snapshot = snapshot,
            sessionActive = sessionActive,
            canStart = LanNetworkReader.canStartVpn(this),
            enteringLocalNetwork = entering,
        )
        Log.i(
            TAG,
            "network=${snapshot.kind} addrs=${snapshot.localAddresses.joinToString { it.hostAddress ?: "?" }} " +
                "onLocal=$onLocalNetwork entering=$entering active=$sessionActive action=$action",
        )
        when (action) {
            RuleAction.STOP -> coordinator.stop()
            RuleAction.START -> SessionStarter.startDetached(this, target)
            RuleAction.FORBIDDEN, RuleAction.NONE -> Unit
        }
    }

    private fun startForegroundCompat() {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.lan_rule_notification_title))
        .setContentText(getString(R.string.lan_rule_notification_text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.lan_rule_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "LanRuleService"
        private const val CHANNEL_ID = "easytier_lan_rule"
        private const val NOTIFICATION_ID = 1002
        private const val DEBOUNCE_MS = 1_200L

        /** Safety net for missed callbacks and for settings changed while frozen. */
        private const val SWEEP_INTERVAL_MS = 15_000L

        /** How long an unreadable profile list is tolerated before giving up. */
        private const val MISSING_TARGET_GRACE_MS = 60_000L
        private val ACTIVE_STATES = setOf(RuntimeState.STARTING, RuntimeState.RUNNING)

        // A background service cannot launch the VPN consent dialog; a start that
        // still needs it surfaces ERROR instead of a prompt.
        private val NO_OP_REQUESTER = VpnPermissionRequester { false }

        /**
         * Starts or stops the rule service according to the selected profile.
         * Safe to call repeatedly; callers use it after settings changes, on app
         * start and after boot.
         */
        suspend fun sync(context: Context) {
            val enabled = runCatching {
                val target = SessionStarter.loadTarget(context) ?: return@runCatching false
                target.profile.autoSwitchEnabled &&
                    target.profile.autoSwitchCidrs.any { Ipv4Cidr.parseOrNull(it) != null }
            }.getOrDefault(false)
            val appContext = context.applicationContext
            if (enabled) {
                // Foreground-service starts are refused from the background on
                // API 31+; never let that crash the caller (boot, ViewModel).
                runCatching {
                    ContextCompat.startForegroundService(appContext, Intent(appContext, LanRuleService::class.java))
                }.onFailure { Log.w(TAG, "cannot start LAN rule service: ${it.message}") }
            } else {
                appContext.stopService(Intent(appContext, LanRuleService::class.java))
            }
        }

        /**
         * True when the rule is enabled and the device is currently on one of the
         * configured subnets, i.e. a session must not be opened right now. Used by
         * the boot receiver to avoid starting a session the rule would immediately
         * stop again.
         */
        suspend fun isSuppressed(context: Context): Boolean = runCatching {
            val target = SessionStarter.loadTarget(context) ?: return@runCatching false
            if (!target.profile.autoSwitchEnabled) return@runCatching false
            val engine = LanRuleEngine(target.profile.autoSwitchCidrs.mapNotNull { Ipv4Cidr.parseOrNull(it) })
            engine.matches(LanNetworkReader.read(context))
        }.getOrDefault(false)
    }
}
