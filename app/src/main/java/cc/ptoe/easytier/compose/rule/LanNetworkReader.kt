package cc.ptoe.easytier.compose.rule

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import java.net.Inet4Address

/**
 * Reads the platform's local networks into a [NetworkSnapshot].
 *
 * Only `ACCESS_NETWORK_STATE` is required: reading the phone's own link
 * addresses needs neither location nor Wi-Fi permissions, which is why the rule
 * keys on subnets instead of SSIDs.
 *
 * Built from [ConnectivityManager.allNetworks] rather than `activeNetwork` on
 * purpose: while the EasyTier VpnService is up the app's default network can be
 * the VPN itself, which used to hide the underlying Wi-Fi from the rule — so
 * "arrived home while connected" was never detected.
 */
internal object LanNetworkReader {

    /** Snapshot of every Wi-Fi/Ethernet interface, or a non-local kind. */
    fun read(context: Context): NetworkSnapshot {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkSnapshot(NetworkSnapshot.Kind.NONE)
        var wifi: List<Inet4Address> = emptyList()
        var ethernet: List<Inet4Address> = emptyList()
        for (network in manager.allNetworks) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val addresses = manager.getLinkProperties(network)?.ipv4Addresses().orEmpty()
            if (addresses.isEmpty()) continue
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi = wifi + addresses
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ethernet = ethernet + addresses
            }
        }
        if (wifi.isNotEmpty()) return NetworkSnapshot(NetworkSnapshot.Kind.WIFI, wifi.distinct())
        if (ethernet.isNotEmpty()) return NetworkSnapshot(NetworkSnapshot.Kind.ETHERNET, ethernet.distinct())
        return NetworkSnapshot(if (isCellularDefault(manager)) NetworkSnapshot.Kind.MOBILE else NetworkSnapshot.Kind.NONE)
    }

    /**
     * True when the app may start the VpnService without showing the consent
     * dialog. A background service cannot launch that dialog, so a missing
     * consent turns the rule into a no-op instead of a repeating error.
     */
    fun canStartVpn(context: Context): Boolean = VpnService.prepare(context) == null

    private fun isCellularDefault(manager: ConnectivityManager): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}

private fun LinkProperties.ipv4Addresses(): List<Inet4Address> =
    linkAddresses.mapNotNull { linkAddress ->
        val address = linkAddress.address as? Inet4Address ?: return@mapNotNull null
        address.takeUnless { it.isLoopbackAddress || it.isLinkLocalAddress }
    }
