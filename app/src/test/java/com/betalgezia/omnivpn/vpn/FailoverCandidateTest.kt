package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailoverCandidateTest {

    private fun node(id: Long, sub: Long?) =
        Node(id = id, name = "n$id", protocol = Protocol.VLESS, server = "10.0.0.$id", port = 443, sourceId = sub)

    private val subA = listOf(node(1, 7), node(2, 7), node(3, 7), node(4, 7))
    private val subB = listOf(node(10, 8), node(11, 8))
    private val all = subA + subB + node(20, null)
    private val ttl = 600_000L

    @Test
    fun picksTheNextServerOfTheSameSubscription() {
        assertEquals(3L, pickFailoverCandidate(all, node(2, 7), emptyMap(), 1_000_000, ttl)?.id)
    }

    @Test
    fun wrapsAroundToTheStartOfTheSubscription() {
        assertEquals(1L, pickFailoverCandidate(all, node(4, 7), emptyMap(), 1_000_000, ttl)?.id)
    }

    @Test
    fun neverLeavesTheSubscription() {
        assertEquals(11L, pickFailoverCandidate(all, node(10, 8), emptyMap(), 1_000_000, ttl)?.id)
        assertNull(pickFailoverCandidate(all, node(10, 8), mapOf(11L to 999_000L), 1_000_000, ttl))
    }

    @Test
    fun skipsServersFoundDeadRecently() {
        val dead = mapOf(3L to 990_000L, 4L to 995_000L)
        assertEquals(1L, pickFailoverCandidate(all, node(2, 7), dead, 1_000_000, ttl)?.id)
    }

    @Test
    fun retriesAServerOnceItsDeadMarkExpired() {
        val dead = mapOf(3L to 100_000L)
        assertEquals(3L, pickFailoverCandidate(all, node(2, 7), dead, 1_000_000, ttl)?.id)
    }

    @Test
    fun nothingLeftWhenEveryOtherServerIsDead() {
        val dead = mapOf(1L to 999_000L, 3L to 999_000L, 4L to 999_000L)
        assertNull(pickFailoverCandidate(all, node(2, 7), dead, 1_000_000, ttl))
    }

    // ---- pickAlternates (idle outbounds for the slow-server switch) ----

    @Test
    fun alternatesFollowTheCurrentServerAndWrapAround() {
        val ids = pickAlternates(all, node(3, 7), emptyMap(), 1_000_000, ttl, 4).map { it.id }
        assertEquals(listOf(4L, 1L, 2L), ids)
    }

    @Test
    fun alternatesAreCappedAndStayInTheSubscription() {
        val big = (1L..9L).map { node(it, 7) } + subB
        val ids = pickAlternates(big, node(1, 7), emptyMap(), 1_000_000, ttl, 4).map { it.id }
        assertEquals(listOf(2L, 3L, 4L, 5L), ids)
    }

    @Test
    fun alternatesSkipRecentlyDeadAndNonProxyServers() {
        val warp = Node(id = 5, name = "w", protocol = Protocol.WARP, server = "x", port = 2408, sourceId = 7)
        val list = subA + warp
        val ids = pickAlternates(list, node(1, 7), mapOf(2L to 999_000L), 1_000_000, ttl, 4).map { it.id }
        assertEquals(listOf(3L, 4L), ids)
    }
}
