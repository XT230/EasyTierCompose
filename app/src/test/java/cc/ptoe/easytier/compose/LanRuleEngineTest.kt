package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.rule.Ipv4Cidr
import cc.ptoe.easytier.compose.rule.LanRuleEngine
import cc.ptoe.easytier.compose.rule.NetworkSnapshot
import cc.ptoe.easytier.compose.rule.NetworkSnapshot.Kind
import cc.ptoe.easytier.compose.rule.RuleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class LanRuleEngineTest {

    private fun engine(vararg cidrs: String) =
        LanRuleEngine(cidrs.map { requireNotNull(Ipv4Cidr.parseOrNull(it)) })

    private fun snapshot(kind: Kind, vararg addresses: String) = NetworkSnapshot(
        kind = kind,
        localAddresses = addresses.map { InetAddress.getByName(it) as Inet4Address },
    )

    private val home = engine("192.168.1.0/24")

    @Test
    fun `stops when a session runs on the configured subnet`() {
        val action = home.decide(snapshot(Kind.WIFI, "192.168.1.23"), sessionActive = true, canStart = true)
        assertEquals(RuleAction.STOP, action)
    }

    @Test
    fun `respects a manual reconnect while staying on the configured subnet`() {
        // `enteringLocalNetwork = false` means the device never left the network:
        // the user reconnected by hand, so the rule must not tear it down again.
        val action = home.decide(
            snapshot(Kind.WIFI, "192.168.1.23"),
            sessionActive = true,
            canStart = true,
            enteringLocalNetwork = false,
        )
        assertEquals(RuleAction.NONE, action)
    }

    @Test
    fun `stops again after leaving and rejoining the subnet`() {
        val action = home.decide(
            snapshot(Kind.WIFI, "192.168.1.23"),
            sessionActive = true,
            canStart = true,
            enteringLocalNetwork = true,
        )
        assertEquals(RuleAction.STOP, action)
    }

    @Test
    fun `does nothing when already stopped on the configured subnet`() {
        val action = home.decide(snapshot(Kind.WIFI, "192.168.1.23"), sessionActive = false, canStart = true)
        assertEquals(RuleAction.NONE, action)
    }

    @Test
    fun `does nothing when a session already runs elsewhere`() {
        val action = home.decide(snapshot(Kind.WIFI, "10.0.0.5"), sessionActive = true, canStart = true)
        assertEquals(RuleAction.NONE, action)
    }

    @Test
    fun `starts after leaving every configured subnet`() {
        val action = home.decide(snapshot(Kind.WIFI, "10.0.0.5"), sessionActive = false, canStart = true)
        assertEquals(RuleAction.START, action)
    }

    @Test
    fun `starts when there is no network at all`() {
        val action = home.decide(snapshot(Kind.NONE), sessionActive = false, canStart = true)
        assertEquals(RuleAction.START, action)
    }

    @Test
    fun `reports forbidden instead of start when a start is impossible`() {
        // No VPN consent (or no profile): a background service cannot prompt, and
        // repeating the attempt would only spam ERROR states.
        val action = home.decide(snapshot(Kind.WIFI, "10.0.0.5"), sessionActive = false, canStart = false)
        assertEquals(RuleAction.FORBIDDEN, action)
    }

    @Test
    fun `ignores cellular links so carrier cgnat never matches`() {
        // A broad home range such as 10.0.0.0/8 must not disable the tunnel on
        // mobile data, which routinely lives in 10/8.
        val broad = engine("10.0.0.0/8")
        assertEquals(RuleAction.NONE, broad.decide(snapshot(Kind.MOBILE, "10.1.2.3"), sessionActive = true, canStart = true))
        assertFalse(broad.matches(snapshot(Kind.MOBILE, "10.1.2.3")))
    }

    @Test
    fun `ignores the vpn transport`() {
        val action = home.decide(snapshot(Kind.VPN, "192.168.1.23"), sessionActive = true, canStart = true)
        assertEquals(RuleAction.NONE, action)
    }

    @Test
    fun `matches ethernet as well as wifi`() {
        assertEquals(RuleAction.STOP, home.decide(snapshot(Kind.ETHERNET, "192.168.1.2"), sessionActive = true, canStart = true))
    }

    @Test
    fun `matches any configured subnet`() {
        val multi = engine("192.168.1.0/24", "172.16.0.0/16")
        assertEquals(RuleAction.STOP, multi.decide(snapshot(Kind.WIFI, "172.16.5.9"), sessionActive = true, canStart = true))
    }

    @Test
    fun `an empty rule never fires`() {
        val disabled = LanRuleEngine(emptyList())
        assertFalse(disabled.enabled)
        assertEquals(RuleAction.NONE, disabled.decide(snapshot(Kind.WIFI, "192.168.1.23"), sessionActive = true, canStart = true))
        assertEquals(RuleAction.NONE, disabled.decide(snapshot(Kind.WIFI, "10.0.0.5"), sessionActive = false, canStart = true))
    }

    @Test
    fun `a wide home rule matches an address inside it`() {
        val wide = engine("192.168.0.0/16")
        assertTrue(wide.matches(snapshot(Kind.WIFI, "192.168.1.23")))
        assertFalse(wide.matches(snapshot(Kind.WIFI, "192.169.1.23")))
    }
}
