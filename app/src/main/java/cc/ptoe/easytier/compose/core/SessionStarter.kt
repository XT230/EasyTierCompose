package cc.ptoe.easytier.compose.core

import android.content.Context
import cc.ptoe.easytier.compose.data.EasyTierProfile
import cc.ptoe.easytier.compose.data.GlobalSettings
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.transport.vpn.VpnPermissionRequester
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first

/**
 * Starts the selected profile without an Activity.
 *
 * Shared by [cc.ptoe.easytier.compose.boot.BootCompletedReceiver] and
 * [cc.ptoe.easytier.compose.rule.LanRuleService]: both run without UI, so they
 * cannot answer the VPN consent dialog and must reuse an existing consent.
 */
internal object SessionStarter {

    data class Target(val profile: EasyTierProfile, val settings: GlobalSettings)

    // Background contexts cannot prompt for VPN consent; a start that needs it
    // surfaces ERROR through the coordinator instead of a dialog.
    private val NO_OP_REQUESTER = VpnPermissionRequester { false }

    /** Selected profile + global settings, or null when no profile exists. */
    suspend fun loadTarget(context: Context): Target? {
        val repository = ProfileRepository(context)
        val profiles = repository.profiles.first()
        if (profiles.isEmpty()) return null
        val selectedId = repository.selectedProfileId.first()
        val profile = profiles.firstOrNull { it.id == selectedId } ?: profiles.first()
        val settings = GlobalSettingsRepository(context).settings.first()
        return Target(profile, settings)
    }

    /**
     * Starts [target] through the process-wide coordinator and returns the job
     * so the caller can bound how long it waits.
     *
     * Reuses an existing coordinator when the app process already hosts one, so
     * an Activity-backed VPN permission requester is not replaced by the no-op
     * one mid-session.
     */
    fun startDetached(context: Context, target: Target): Job {
        val coordinator = EasyTierRuntimeCoordinator.existingInstance()
            ?: EasyTierRuntimeCoordinator.getInstance(context.applicationContext, NO_OP_REQUESTER)
        return coordinator.startDetached(target.profile, target.settings)
    }
}
