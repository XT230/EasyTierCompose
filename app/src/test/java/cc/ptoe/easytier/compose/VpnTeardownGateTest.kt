package cc.ptoe.easytier.compose

import cc.ptoe.easytier.compose.transport.vpn.VpnTeardownGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTeardownGateTest {

    @Test
    fun `a fresh gate accepts starts and reports closed`() {
        val gate = VpnTeardownGate()
        assertTrue(gate.allowStart())
        assertFalse(gate.isOpen())
        assertTrue(gate.closed.value)
    }

    @Test
    fun `requestStop closes the start window immediately`() {
        val gate = VpnTeardownGate()
        gate.markOpen()
        gate.requestStop()
        assertFalse(gate.allowStart())
    }

    @Test
    fun `a start intent queued before the stop is dropped`() {
        // Reproduces the race: establishWhenResolved() posts
        // startForegroundService, the user disconnects, then the queued intent is
        // finally delivered. It must not re-open the session.
        val gate = VpnTeardownGate()
        gate.beginStart()
        gate.markOpen()
        gate.requestStop()
        gate.markClosed()
        assertFalse(gate.allowStart())
        assertFalse(gate.isOpen())
    }

    @Test
    fun `beginStart reopens the window for the next explicit connect`() {
        val gate = VpnTeardownGate()
        gate.requestStop()
        gate.beginStart()
        assertTrue(gate.allowStart())
    }

    @Test
    fun `markOpen and markClosed track the tunnel state`() {
        val gate = VpnTeardownGate()
        gate.markOpen()
        assertTrue(gate.isOpen())
        assertFalse(gate.closed.value)
        gate.markClosed()
        assertFalse(gate.isOpen())
        assertTrue(gate.closed.value)
    }

    @Test
    fun `markClosed is idempotent across teardown paths`() {
        // onDestroy, onRevoke, stopFromApp and stopAndExit all report the close;
        // repeated calls must stay closed.
        val gate = VpnTeardownGate()
        gate.markOpen()
        repeat(3) { gate.markClosed() }
        assertTrue(gate.closed.value)
    }
}
