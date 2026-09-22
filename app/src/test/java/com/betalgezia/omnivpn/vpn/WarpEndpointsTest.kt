package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarpEndpointsTest {

    @Test
    fun everyCandidateIsALiteralIpEndpoint() {
        // A hostname candidate would hit the confirmed sing-box-lx bug that
        // WarpAccount.DEFAULT_ENDPOINT exists to avoid (UDP socket recreated on
        // every handshake retry, handshake never completes) - and it would do so
        // silently, looking like just another dead endpoint during a scan.
        WarpEndpoints.CANDIDATES.forEach { endpoint ->
            assertTrue(
                WarpAccount.isLiteralIpEndpoint(endpoint),
                "$endpoint must be a literal IP:port, not a hostname"
            )
        }
    }

    @Test
    fun theKnownGoodEndpointIsTriedFirst() {
        assertEquals(WarpAccount.DEFAULT_ENDPOINT, WarpEndpoints.CANDIDATES.first())
    }

    @Test
    fun candidatesAreDistinctAndSpanBothWarpRanges() {
        assertEquals(WarpEndpoints.CANDIDATES.size, WarpEndpoints.CANDIDATES.distinct().size)
        // Both /24 blocks Cloudflare publishes for WARP: a block on one range
        // has to leave something to fall back to.
        assertTrue(WarpEndpoints.CANDIDATES.any { it.startsWith("162.159.") })
        assertTrue(WarpEndpoints.CANDIDATES.any { it.startsWith("188.114.") })
        // Port spread is the other half of the point - one pair getting dropped
        // must not mean every candidate shares its port.
        val ports = WarpEndpoints.CANDIDATES.map { it.substringAfterLast(':') }.toSet()
        assertTrue(ports.size >= 4, "expected several distinct ports, got $ports")
    }
}
