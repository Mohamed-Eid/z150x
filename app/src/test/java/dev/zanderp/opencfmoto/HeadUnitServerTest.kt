
package dev.zanderp.opencfmoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

class HeadUnitServerTest {
    private val loopback4 = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @Test
    fun `a listening server is detected`() {
        ServerSocket(0, 1, loopback4).use { server ->
            assertEquals(true, HeadUnitServer.listenerOnLoopback(server.localPort))
        }
    }

    @Test
    fun `a free port reads as not running`() {
        val port = ServerSocket(0, 1, loopback4).use { it.localPort }
        assertEquals(false, HeadUnitServer.listenerOnLoopback(port))
    }

    @Test
    fun `checking never opens a connection to the server`() {
        // A connection is what wedged Android Auto's server, so the check must not reach accept().
        ServerSocket(0, 1, loopback4).use { server ->
            HeadUnitServer.listenerOnLoopback(server.localPort)
            server.soTimeout = 200
            try {
                server.accept().close()
                fail("the check connected to the server")
            } catch (_: SocketTimeoutException) {
            }
        }
    }

    @Test
    fun `a server can start on the port right after a check`() {
        val port = ServerSocket(0, 1, loopback4).use { it.localPort }
        HeadUnitServer.listenerOnLoopback(port)
        ServerSocket(port, 1, loopback4).use { assertEquals(port, it.localPort) }
    }

    @Test
    fun `only Android Auto 17_4 and newer need the server`() {
        assertFalse(HeadUnitServer.versionNeedsServer("16.4.651234-release"))
        assertFalse(HeadUnitServer.versionNeedsServer("17.3.0"))
        assertTrue(HeadUnitServer.versionNeedsServer("17.4.0"))
        assertTrue(HeadUnitServer.versionNeedsServer("17.10.1"))
        assertTrue(HeadUnitServer.versionNeedsServer("18.0.5-release"))
    }

    @Test
    fun `an unreadable version counts as needing the server`() {
        assertTrue(HeadUnitServer.versionNeedsServer(null))
        assertTrue(HeadUnitServer.versionNeedsServer(""))
        assertTrue(HeadUnitServer.versionNeedsServer("beta"))
    }
}
