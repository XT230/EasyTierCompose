package cc.ptoe.easytier.compose.transport.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide handshake between "stop the VPN" and "start the VPN".
 *
 * `VpnTunTransport` starts [EasyTierVpnService] with `startForegroundService`,
 * which is asynchronous: the intent can still be queued when the user already
 * hit disconnect. Without a shared gate the late intent re-creates the service
 * (and the TUN) after `stopService()`, leaving a system VPN with no EasyTier
 * core behind it — the "disconnect disconnects the mesh but the VPN stays up"
 * symptom.
 *
 * The gate enforces one invariant: **after [requestStop] every start request is
 * dropped until [beginStart] explicitly opens a new session**. [closed] mirrors
 * the real TUN state so callers can await teardown instead of fire-and-forget.
 *
 * Kept free of Android types on purpose, so the state machine is unit-testable.
 */
class VpnTeardownGate {
    private val lock = Any()
    private var stopRequested = false
    private val mutableClosed = MutableStateFlow(true)

    /** `true` when no TUN is established (initial state and after teardown). */
    val closed: StateFlow<Boolean> = mutableClosed.asStateFlow()

    /** Opens a new session window; called when a start is accepted. */
    fun beginStart() {
        synchronized(lock) { stopRequested = false }
    }

    /** Closes the session window; called before touching the service. */
    fun requestStop() {
        synchronized(lock) { stopRequested = true }
    }

    /** `false` once a stop was requested, until [beginStart] is called again. */
    fun allowStart(): Boolean = synchronized(lock) { !stopRequested }

    /** Marks the TUN as established. */
    fun markOpen() {
        mutableClosed.value = false
    }

    /** Marks the TUN as closed; called from every teardown path. */
    fun markClosed() {
        mutableClosed.value = true
    }

    /** `true` while a TUN is believed to be established. */
    fun isOpen(): Boolean = !mutableClosed.value
}
