package cc.ptoe.easytier.compose.transport.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log

/**
 * Read-only view of the platform's VPN networks, used to document teardown in
 * field reports.
 *
 * The probe is intentionally *not* used as a hard assertion: another VPN app
 * (mihomo etc.) may legitimately keep a `TRANSPORT_VPN` network alive, so the
 * authoritative signal for "our TUN is gone" remains
 * [VpnTeardownGate.closed]. This only tells the maintainer what the system
 * still sees after a disconnect.
 */
internal object VpnNetworkProbe {

    /** One structured log line describing the VPN networks the system sees. */
    fun logState(context: Context, tag: String) {
        val details = runCatching { describe(context) }.getOrElse { error ->
            Log.w(tag, "vpn probe failed: ${error.message}")
            return
        }
        if (details.isEmpty()) {
            Log.i(tag, "system vpn networks: none")
        } else {
            Log.i(tag, "system vpn networks: $details")
        }
    }

    /** `iface[addr addr]` per VPN network; empty when no VPN transport exists. */
    fun describe(context: Context): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        return manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val link = manager.getLinkProperties(network)
            val iface = link?.interfaceName ?: "?"
            val addresses = link?.linkAddresses?.mapNotNull { it.address.hostAddress }.orEmpty()
            "$iface[${addresses.joinToString(" ")}]"
        }
    }
}
