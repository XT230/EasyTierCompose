package cc.ptoe.easytier.compose.transport.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import cc.ptoe.easytier.compose.R
import androidx.core.content.ContextCompat
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.RuntimeState
import cc.ptoe.easytier.compose.data.RuntimeStatus
import cc.ptoe.easytier.compose.transport.RuntimeTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

class VpnTunTransport(
    private val context: Context,
    permissionRequester: VpnPermissionRequester,
) : RuntimeTransport {
    // @Volatile: the requester is swapped at runtime when the app moves from a
    // background (boot, no-op) to a foreground (MainActivity, consent dialog)
    // context, so a late start() from the boot receiver must see the latest one.
    @Volatile private var permissionRequester: VpnPermissionRequester = permissionRequester
    private val mutableStatus = MutableStateFlow(RuntimeStatus.Stopped)
    override val status: StateFlow<RuntimeStatus> = mutableStatus.asStateFlow()

    /** Replaces the permission requester (e.g. swap boot no-op for the activity one). */
    fun updatePermissionRequester(requester: VpnPermissionRequester) {
        permissionRequester = requester
    }

    // Guards against establishWhenResolved racing with stop(): once running flips to
    // false, no further startForegroundService intents are posted, so stopService()
    // cannot be re-activated by a late poll iteration.
    @Volatile private var running: Boolean = false
    // Tracks the last established VPN state so we only re-establish when the address
    // or routes actually change, instead of re-calling startForegroundService every
    // 2 seconds from the poll loop.
    private var establishedIpv4Cidr: String? = null
    private var establishedRoutes: List<String> = emptyList()
    // MTU lives on the (effective) profile; cached at start() so the poll loop's
    // establishWhenResolved() calls can reuse it without threading settings through.
    private var activeMtu: Int = DEFAULT_MTU

    override suspend fun start(profile: EasyTierProfile, toml: String, globalSettings: GlobalSettings): RuntimeStatus {
        // Reopen the session window: a stop requested earlier must not swallow
        // this explicit start.
        EasyTierVpnService.gate.beginStart()
        running = true
        activeMtu = profile.mtu
        establishedIpv4Cidr = null
        establishedRoutes = emptyList()
        val ipv4Cidr = profile.virtualIpv4?.takeIf { !profile.dhcp }
        val prepared = VpnService.prepare(context)
        if (prepared != null) {
            // Synchronously request VPN permission. In the foreground this launches
            // the system consent dialog via the injected requester; from a background
            // context (e.g. boot) the no-op requester returns false and we surface
            // an ERROR — the user must open the app once to grant permission.
            mutableStatus.value = RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
            val granted = permissionRequester.request(prepared)
            if (!granted) {
                return RuntimeStatus(RuntimeState.ERROR, profile.id, profile.tunMode, null, null, context.getString(R.string.error_vpn_permission_denied))
                    .also { running = false; mutableStatus.value = it }
            }
        }
        return startPrepared(profile, ipv4Cidr, emptyList())
    }

    /**
     * Adopts an already-running VPN session started by another coordinator instance
     * (e.g. BootCompletedReceiver). EasyTierVpnService FGS is already running with the
     * VPN interface established, so we restore [running] and the established address/
     * routes to prevent establishWhenResolved() from redundantly re-launching the FGS
     * with the same parameters.
     */
    fun adopt(profile: EasyTierProfile, ipv4Cidr: String?, routes: List<String>): RuntimeStatus {
        EasyTierVpnService.gate.beginStart()
        running = true
        establishedIpv4Cidr = ipv4Cidr
        establishedRoutes = routes
        val state = if (ipv4Cidr.isNullOrBlank()) RuntimeState.STARTING else RuntimeState.RUNNING
        val tunDevice = if (ipv4Cidr.isNullOrBlank()) null else context.getString(R.string.vpn_device_name)
        return RuntimeStatus(state, profile.id, profile.tunMode, ipv4Cidr, tunDevice, null)
            .also { mutableStatus.value = it }
    }

    fun establishWhenResolved(profile: EasyTierProfile, ipv4Cidr: String, routes: List<String>): RuntimeStatus {
        if (!running) return mutableStatus.value
        // A teardown may have landed while the poll coroutine was in flight; the
        // gate is the authoritative check because `running` is reset by stop().
        if (!EasyTierVpnService.gate.allowStart()) {
            running = false
            return mutableStatus.value
        }
        // Only re-establish the VPN interface when the address or routes actually change,
        // avoiding redundant startForegroundService calls that race with stop().
        if (ipv4Cidr == establishedIpv4Cidr && routes == establishedRoutes) {
            return mutableStatus.value
        }
        establishedIpv4Cidr = ipv4Cidr
        establishedRoutes = routes
        ContextCompat.startForegroundService(
            context,
            EasyTierVpnService.intent(context, profile.id, ipv4Cidr, routes, profile.enableMagicDns, activeMtu),
        )
        return RuntimeStatus(RuntimeState.RUNNING, profile.id, profile.tunMode, ipv4Cidr, context.getString(R.string.vpn_device_name), null)
            .also { mutableStatus.value = it }
    }

    /**
     * Tears the VPN down and waits for the service to acknowledge.
     *
     * Closing the TUN is not enough on its own: the EasyTier core also holds the
     * descriptor (via `setTunFd`), so the caller must stop the core *first*
     * (see [cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator]). This
     * method guarantees the service side is gone and that a late start intent
     * cannot resurrect it.
     */
    override suspend fun stop() {
        running = false
        establishedIpv4Cidr = null
        establishedRoutes = emptyList()
        EasyTierVpnService.requestStop(context)
        // Belt and braces for the "service not alive in this process" case.
        context.stopService(Intent(context, EasyTierVpnService::class.java))
        val acknowledged = withTimeoutOrNull(TEARDOWN_ACK_TIMEOUT_MS) {
            EasyTierVpnService.gate.closed.first { it }
        }
        if (acknowledged == null) {
            Log.w(TAG, "stop: VpnService did not acknowledge teardown within ${TEARDOWN_ACK_TIMEOUT_MS}ms")
        }
        mutableStatus.value = RuntimeStatus.Stopped
    }

    private fun startPrepared(profile: EasyTierProfile, ipv4Cidr: String?, routes: List<String>): RuntimeStatus {
        if (ipv4Cidr == null) return RuntimeStatus(RuntimeState.STARTING, profile.id, profile.tunMode, null, null, null)
            .also { mutableStatus.value = it }
        return establishWhenResolved(profile, ipv4Cidr, routes)
    }

    /** `true` while the service believes a TUN is established. */
    fun isTunnelOpen(): Boolean = EasyTierVpnService.gate.isOpen()

    private companion object {
        const val TAG = "EasyTierVpnTransport"
        const val DEFAULT_MTU = 1380
        const val TEARDOWN_ACK_TIMEOUT_MS = 1_000L
    }
}
