package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

sealed interface NodeHealth {
    data object Unknown : NodeHealth
    data object Checking : NodeHealth
    data class Reachable(val latencyMs: Long) : NodeHealth
    // UDP-only outcome: neither a reply nor an ICMP error arrived. Not the
    // same as Unreachable - see NodeHealthChecker.checkUdp.
    data object NoResponse : NodeHealth
    data class Unreachable(val reason: String) : NodeHealth
}

/**
 * Liveness probe for a [Node]'s server:port, run off the VPN tunnel (plain
 * network sockets, not sing-box) so it works whether or not a proxy is
 * currently connected.
 */
@Singleton
class NodeHealthChecker @Inject constructor() {

    suspend fun check(node: Node, timeoutMs: Int = DEFAULT_TIMEOUT_MS): NodeHealth =
        withContext(Dispatchers.IO) {
            val host = node.server.trim()
            val port = node.port
            if (host.isEmpty() || port !in 1..65535) {
                return@withContext NodeHealth.Unreachable("Invalid server address")
            }
            when (node.protocol) {
                Protocol.VLESS, Protocol.TROJAN -> checkTcp(host, port, timeoutMs)
                Protocol.HYSTERIA2, Protocol.AMNEZIAWG, Protocol.WARP -> checkUdp(host, port, timeoutMs)
            }
        }

    /**
     * Checks [nodes] with at most [concurrency] probes in flight at once, invoking
     * [onResult] as each one completes so callers can update UI progressively
     * instead of waiting for the slowest server.
     */
    suspend fun checkAll(
        nodes: List<Node>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        onResult: suspend (Node, NodeHealth) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        nodes.map { node ->
            async(Dispatchers.IO) {
                semaphore.withPermit { onResult(node, check(node, timeoutMs)) }
            }
        }.forEach { it.await() }
    }

    private fun checkTcp(host: String, port: Int, timeoutMs: Int): NodeHealth {
        val start = System.nanoTime()
        return try {
            Socket().use { socket -> socket.connect(InetSocketAddress(host, port), timeoutMs) }
            NodeHealth.Reachable(elapsedMs(start))
        } catch (e: SocketTimeoutException) {
            NodeHealth.Unreachable("Timed out")
        } catch (e: ConnectException) {
            NodeHealth.Unreachable("Connection refused")
        } catch (e: UnknownHostException) {
            NodeHealth.Unreachable("Unknown host")
        } catch (e: IOException) {
            NodeHealth.Unreachable(e.message ?: "Connection failed")
        }
    }

    // Hysteria2 (QUIC), AmneziaWG and WARP (WireGuard) all silently drop
    // datagrams that don't pass their handshake, by design - that's what
    // keeps them from being usable as UDP reflection/amplification targets
    // and (for AmneziaWG's own junk-packet obfuscation, see WarpAccount) is
    // the same property this app leans on to evade DPI. So an unanswered
    // probe does NOT mean the server is down; only two things are actually
    // conclusive: a reply (something is listening) or an ICMP "port
    // unreachable" (nothing is). A connected DatagramSocket surfaces that
    // ICMP error as PortUnreachableException on a later send/receive, so it
    // is what NoResponse is distinguished from below - not a fallback guess.
    private fun checkUdp(host: String, port: Int, timeoutMs: Int): NodeHealth {
        val start = System.nanoTime()
        return try {
            DatagramSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port))
                socket.soTimeout = (timeoutMs / 2).coerceAtLeast(500)
                val probe = ByteArray(1)
                val buffer = ByteArray(512)
                // Two rounds: a delayed ICMP error can still land on the
                // second send/receive after the first attempt merely timed out.
                repeat(2) {
                    socket.send(DatagramPacket(probe, probe.size))
                    try {
                        socket.receive(DatagramPacket(buffer, buffer.size))
                        return NodeHealth.Reachable(elapsedMs(start))
                    } catch (e: SocketTimeoutException) {
                        // fall through to the next round / NoResponse below
                    }
                }
                NodeHealth.NoResponse
            }
        } catch (e: PortUnreachableException) {
            NodeHealth.Unreachable("Port unreachable")
        } catch (e: UnknownHostException) {
            NodeHealth.Unreachable("Unknown host")
        } catch (e: IOException) {
            NodeHealth.Unreachable(e.message ?: "Connection failed")
        }
    }

    private fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 4000
        const val DEFAULT_CONCURRENCY = 5
    }
}
