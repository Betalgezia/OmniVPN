package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import java.net.DatagramSocket
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeHealthCheckerTest {
    private val checker = NodeHealthChecker()

    private fun node(protocol: Protocol, port: Int, server: String = "127.0.0.1") =
        Node(name = "test", protocol = protocol, server = server, port = port)

    @Test
    fun tcpReachableWhenSomethingIsListening() {
        val server = ServerSocket(0)
        try {
            thread(isDaemon = true) { runCatching { server.accept()?.close() } }
            val result = runBlocking { checker.check(node(Protocol.VLESS, server.localPort), timeoutMs = 1000) }
            assertTrue(result is NodeHealth.Reachable, "expected Reachable, got $result")
        } finally {
            server.close()
        }
    }

    @Test
    fun tcpUnreachableWhenPortIsClosed() {
        val freePort = ServerSocket(0).use { it.localPort }
        val result = runBlocking { checker.check(node(Protocol.TROJAN, freePort), timeoutMs = 1000) }
        assertTrue(result is NodeHealth.Unreachable, "expected Unreachable, got $result")
    }

    @Test
    fun udpNoResponseWhenServerIgnoresTheProbe() {
        // A well-behaved AmneziaWG/Hysteria2/WARP endpoint silently drops
        // datagrams that don't pass its handshake - simulated here with a
        // socket that is open but never reads anything.
        val server = DatagramSocket(0)
        try {
            val result = runBlocking { checker.check(node(Protocol.AMNEZIAWG, server.localPort), timeoutMs = 400) }
            assertEquals(NodeHealth.NoResponse, result)
        } finally {
            server.close()
        }
    }

    @Test
    fun udpUnreachableWhenPortIsClosed() {
        val freePort = DatagramSocket(0).use { it.localPort }
        val result = runBlocking { checker.check(node(Protocol.HYSTERIA2, freePort), timeoutMs = 1000) }
        assertTrue(result is NodeHealth.Unreachable, "expected Unreachable, got $result")
    }

    @Test
    fun rejectsInvalidPortWithoutTouchingTheNetwork() {
        val result = runBlocking { checker.check(node(Protocol.VLESS, port = 0)) }
        assertEquals(NodeHealth.Unreachable("Invalid server address"), result)
    }

    @Test
    fun checkAllReportsEveryNodeExactlyOnce() {
        val server = ServerSocket(0)
        try {
            thread(isDaemon = true) {
                repeat(2) { runCatching { server.accept()?.close() } }
            }
            val nodes = listOf(
                node(Protocol.VLESS, server.localPort).copy(id = 1),
                node(Protocol.TROJAN, server.localPort).copy(id = 2)
            )
            val results = mutableMapOf<Long, NodeHealth>()
            runBlocking {
                checker.checkAll(nodes, timeoutMs = 1000) { n, health -> results[n.id] = health }
            }
            assertEquals(setOf(1L, 2L), results.keys)
            results.values.forEach { assertTrue(it is NodeHealth.Reachable, "expected Reachable, got $it") }
        } finally {
            server.close()
        }
    }
}
