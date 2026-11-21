package dev.opendeck.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

class ReachabilityTest {
    @Test
    fun ourOwnTimeoutCountsAsUnreachable() {
        assertEquals(Reachability.UNREACHABLE, Reachability.of(null))
    }

    @Test
    fun androidOkHttpMessages() {
        // As OkHttp reports them on Android 10.
        val refused = ConnectException("Failed to connect to /192.168.1.100:28198").apply {
            initCause(ConnectException("failed to connect to /192.168.1.100 (port 28198) from /192.168.1.23 (port 41234) after 6000ms: isConnected failed: ECONNREFUSED (Connection refused)"))
        }
        assertEquals(Reachability.REFUSED, Reachability.of(refused))
        assertEquals(Reachability.UNREACHABLE, Reachability.of(SocketTimeoutException("failed to connect to /192.168.1.100 (port 28198) after 6000ms")))
        assertEquals(Reachability.UNREACHABLE, Reachability.of(NoRouteToHostException("EHOSTUNREACH (No route to host)")))
        assertEquals(Reachability.UNREACHABLE, Reachability.of(IOException("x", ConnectException("ENETUNREACH (Network is unreachable)"))))
    }

    @Test
    fun suppressedRefusalWins() {
        val e = ConnectException("Failed to connect").apply { addSuppressed(ConnectException("Connection refused")) }
        assertEquals(Reachability.REFUSED, Reachability.of(e))
    }

    @Test
    fun otherFailures() {
        assertEquals(Reachability.OTHER, Reachability.of(IOException("Expected HTTP 101 response but was '404 Not Found'")))
    }

    @Test
    fun realClosedPortIsRefused() {
        val port = ServerSocket(0).use { it.localPort } // free port, now closed
        val e = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 2000) } }.exceptionOrNull()
        assertEquals(Reachability.REFUSED, Reachability.of(e))
    }
}
