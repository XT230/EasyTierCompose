package cc.ptoe.easytier.compose.rule

/** What the auto start/stop rule wants to happen to the EasyTier session. */
enum class RuleAction {
    /** Runtime state already matches the rule. */
    NONE,

    /** The device is on a configured subnet while a session is active. */
    STOP,

    /** The device left every configured subnet and no session is active. */
    START,

    /** A start is wanted but impossible (no VPN consent, no profile). */
    FORBIDDEN,
}

/**
 * Decision table for "keep EasyTier off while the phone is on the home LAN".
 *
 * Semantics: if a local Wi-Fi/Ethernet network's address falls inside one of
 * [cidrs], the session must not run; anywhere else it must. The engine is
 * stateless — callers pass in the current session state (and whether the device
 * just joined the network) and get back either "nothing to do" or the single
 * action required, so repeated network callbacks cannot cause flapping.
 *
 * Cellular links are ignored on purpose: carrier networks routinely use
 * `10/8` or `100.64/10`, which would match a broad home CIDR and keep the
 * tunnel disabled forever. A VPN transport is ignored too, because the EasyTier
 * tunnel (or a co-resident VPN app) would otherwise hide the underlying Wi-Fi.
 */
class LanRuleEngine(cidrs: List<Ipv4Cidr>) {
    private val cidrs: List<Ipv4Cidr> = cidrs.distinct()

    /** False when nothing usable was configured; the rule is then inert. */
    val enabled: Boolean get() = this.cidrs.isNotEmpty()

    /** True when [snapshot] is a local network covered by the configured subnets. */
    fun matches(snapshot: NetworkSnapshot): Boolean {
        if (!enabled) return false
        val eligible = when (snapshot.kind) {
            NetworkSnapshot.Kind.WIFI, NetworkSnapshot.Kind.ETHERNET -> true
            else -> false
        }
        if (!eligible) return false
        return snapshot.localAddresses.any { address -> cidrs.any { it.contains(address) } }
    }

    /**
     * @param sessionActive whether the coordinator currently has a running session.
     * @param canStart whether a start is possible at all (VPN consent granted and a
     *   profile selected); a start from the background cannot prompt for consent.
     * @param enteringLocalNetwork true on the first observation of a local network the
     *   device just joined (and after leaving one). A false value means the device has
     *   been sitting on the same local network, so a session started by hand there is
     *   respected until the network changes — otherwise the rule would fight the user
     *   every time they reconnected.
     */
    fun decide(
        snapshot: NetworkSnapshot,
        sessionActive: Boolean,
        canStart: Boolean,
        enteringLocalNetwork: Boolean = true,
    ): RuleAction {
        if (!enabled) return RuleAction.NONE
        val onLocalNetwork = matches(snapshot)
        return when {
            onLocalNetwork && sessionActive && enteringLocalNetwork -> RuleAction.STOP
            !onLocalNetwork && !sessionActive -> if (canStart) RuleAction.START else RuleAction.FORBIDDEN
            else -> RuleAction.NONE
        }
    }
}
