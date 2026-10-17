package cc.ptoe.easytier.compose.rule

import java.net.Inet4Address

/**
 * The slice of the platform's network state the LAN rule needs.
 *
 * Produced by [LanNetworkReader] from the *active* network and consumed by
 * [LanRuleEngine]; keeping it a plain value object makes the decision table
 * testable without Robolectric.
 */
data class NetworkSnapshot(
    val kind: Kind,
    /** IPv4 addresses configured on the active network. */
    val localAddresses: List<Inet4Address> = emptyList(),
) {
    enum class Kind {
        WIFI,
        ETHERNET,
        MOBILE,
        /** The active network is itself a VPN (e.g. mihomo); rules must not run. */
        VPN,
        OTHER,
        NONE,
    }
}
