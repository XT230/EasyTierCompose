package cc.ptoe.easytier.compose.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cc.ptoe.easytier.compose.core.SessionStarter
import cc.ptoe.easytier.compose.rule.LanRuleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts the EasyTier VPN / Root TUN service after system boot when the user
 * has enabled "Start on boot" in Settings, and keeps the LAN rule service alive
 * across reboots.
 *
 * Does NOT launch MainActivity: the selected profile is loaded from DataStore
 * and the coordinator is started headless. This is BAL-compliant because no
 * Activity is started from a RECEIVER process state.
 *
 * - ROOT_TUN: the libsu daemon process runs independently and survives app
 *   process death. The boot receiver only needs to trigger `start` and let
 *   the daemon take over.
 * - VPN_SERVICE: if `VpnService.prepare` returns null (user has previously
 *   authorized VPN), the EasyTier core starts and `EasyTierVpnService` FGS
 *   is started once the virtual IPv4 resolves, keeping the app process alive.
 *   If VPN permission has not been granted, the no-op requester returns false
 *   and the coordinator surfaces an ERROR — the user must open the app once
 *   to grant permission; subsequent boots will be authorized.
 * - no_tun: only the EasyTier core runs in the app process. There is no FGS
 *   to keep the process alive, so the system may reclaim it under memory
 *   pressure. This is a known limitation of headless no_tun mode.
 *
 * Does not auto-connect in the UI sense — the user has explicitly opted in via
 * the "Start on boot" setting.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appContext = context.applicationContext

                // The LAN rule must survive a reboot: BOOT_COMPLETED is one of the
                // documented exemptions that may start a foreground service.
                runCatching { LanRuleService.sync(appContext) }
                    .onFailure { Log.w(TAG, "lan rule sync failed: ${it.message}") }

                // Booting while already on the home subnet: starting here would be
                // undone by the rule a few seconds later, so skip the session and
                // let the rule service keep it off.
                if (LanRuleService.isSuppressed(appContext)) {
                    Log.i(TAG, "on a rule subnet; skipping boot start")
                    return@launch
                }

                val target = SessionStarter.loadTarget(appContext) ?: return@launch
                // startOnBoot is a per-profile flag toggled from the main Settings
                // page; the selected profile's value decides whether to start at boot.
                if (!target.profile.startOnBoot) return@launch

                // startDetached launches `start` in the coordinator's own SupervisorJob
                // scope, so this withTimeoutOrNull only abandons *waiting* for the
                // result — the start operation itself (bind + remote.start + daemon
                // startup) keeps running. This is critical for ROOT_TUN where boot-time
                // root process startup can exceed 8s; cancelling bind() mid-way would
                // leave the daemon bound but never told which profile to run.
                val startJob = SessionStarter.startDetached(appContext, target)
                withTimeoutOrNull(8_000) { startJob.join() }
                    ?: Log.w(TAG, "coordinator.start did not settle within 8s; leaving it running")
            } catch (e: Throwable) {
                Log.e(TAG, "boot start failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
