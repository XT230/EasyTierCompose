package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.rule.Ipv4Cidr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class Ipv4CidrTest {

    private fun cidr(text: String): Ipv4Cidr = requireNotNull(Ipv4Cidr.parseOrNull(text))

    private fun address(text: String) = InetAddress.getByName(text) as java.net.Inet4Address

    @Test
    fun `parses a canonical cidr`() {
        val parsed = cidr("192.168.1.0/24")
        assertEquals(24, parsed.prefix)
        assertEquals("192.168.1.0/24", parsed.toString())
    }

    @Test
    fun `normalises host bits away`() {
        // Users routinely type the phone's own address instead of the network
        // address; the stored rule must still be the network.
        assertEquals("192.168.1.0/24", cidr("192.168.1.23/24").toString())
        assertEquals("10.0.0.0/8", cidr("10.4.5.6/8").toString())
    }

    @Test
    fun `contains addresses inside the prefix`() {
        val rule = cidr("192.168.1.0/24")
        assertTrue(rule.contains(address("192.168.1.1")))
        assertTrue(rule.contains(address("192.168.1.254")))
        assertFalse(rule.contains(address("192.168.2.1")))
        assertFalse(rule.contains(address("10.0.0.1")))
    }

    @Test
    fun `the rule mask is used, not the address prefix length`() {
        // A phone on a /16 still matches a /24 home rule when its address is in it.
        val rule = cidr("192.168.1.0/24")
        assertTrue(rule.contains(address("192.168.1.77")))
        assertFalse(rule.contains(address("192.168.2.77")))
    }

    @Test
    fun `slash 32 matches a single host`() {
        val rule = cidr("192.168.1.5/32")
        assertTrue(rule.contains(address("192.168.1.5")))
        assertFalse(rule.contains(address("192.168.1.6")))
    }

    @Test
    fun `slash 0 matches everything`() {
        val rule = cidr("0.0.0.0/0")
        assertTrue(rule.contains(address("1.2.3.4")))
        assertTrue(rule.contains(address("192.168.1.1")))
    }

    @Test
    fun `rejects malformed input`() {
        listOf(
            "",
            "192.168.1.0",
            "192.168.1.0/",
            "192.168.1.0/33",
            "192.168.1.0/-1",
            "256.1.1.1/24",
            "192.168.1/24",
            "1.2.3.4.5/24",
            "abc/24",
            "192.168.1.1 / 24",
        ).forEach { raw ->
            assertNull("expected null for '$raw'", Ipv4Cidr.parseOrNull(raw))
        }
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals("192.168.1.0/24", cidr("  192.168.1.0/24  ").toString())
    }
}
