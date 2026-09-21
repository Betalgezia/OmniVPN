package com.betalgezia.omnivpn.vpn

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WarpAccountTest {
    @Test
    fun defaultEndpointIsALiteralIp() {
        // Regression guard: a domain-name WireGuard/AmneziaWG peer hits a
        // confirmed sing-box-lx bug (UDP socket recreated on every handshake
        // retry, handshake never completes) - see the comment on
        // WarpAccount.DEFAULT_ENDPOINT. This must stay a literal IP.
        assertTrue(WarpAccount.isLiteralIpEndpoint(WarpAccount.DEFAULT_ENDPOINT))
    }

    @Test
    fun isLiteralIpEndpointDistinguishesHostFromIp() {
        assertTrue(WarpAccount.isLiteralIpEndpoint("162.159.192.1:2408"))
        assertTrue(WarpAccount.isLiteralIpEndpoint("[2606:4700:d0::a29f:c001]:2408"))
        assertFalse(WarpAccount.isLiteralIpEndpoint("engage.cloudflareclient.com:2408"))
        assertFalse(WarpAccount.isLiteralIpEndpoint("not a valid endpoint"))
    }

    @Test
    fun clientIdBecomesThreePeerReservedBytes() {
        val clientId = "AX//"
        val account = WarpAccount(
            privateKey = "private", peerPublicKey = "peer",
            clientV4 = "172.16.0.2", clientV6 = "2606:4700:4700::1001",
            clientId = clientId, accountId = "account", deviceId = "device", token = "token",
            license = null, warpPlus = false, endpoint = "engage.cloudflareclient.com:2408",
            createdAt = "2026-09-18T00:00:00Z"
        )
        val endpoint = JSONObject(account.toSingBoxEndpoint())
        val peer = endpoint.getJSONArray("peers").getJSONObject(0)
        val reserved = peer.getJSONArray("reserved")
        assertEquals(3, reserved.length())
        assertEquals(1, reserved.getInt(0))
        assertEquals(127, reserved.getInt(1))
        assertEquals(255, reserved.getInt(2))
        assertEquals(2, peer.getJSONArray("allowed_ips").length())
    }

    @Test
    fun toNodeAppliesJunkPacketObfuscationWithStandardHeaderBytes() {
        // Regression guard: after the literal-IP fix, "Get WARP" still timed
        // out identically on WiFi and mobile data with a from-scratch
        // registration - pointing at DPI fingerprinting the plain WireGuard
        // handshake rather than an app bug (see the comment on
        // WarpAccount.DPI_JUNK_OBFUSCATION). The node built for WARP must
        // carry junk-packet params (jc/jmin/jmax > 0), and h1-h4 must stay
        // WireGuard's own standard message-type bytes (1,2,3,4) - not
        // scrambled - since Cloudflare's server only understands plain
        // WireGuard and would silently drop a mangled header.
        val account = WarpAccount(
            privateKey = "private", peerPublicKey = "peer",
            clientV4 = "172.16.0.2", clientV6 = "2606:4700:4700::1001",
            clientId = null, accountId = "account", deviceId = "device", token = "token",
            license = null, warpPlus = false, endpoint = WarpAccount.DEFAULT_ENDPOINT,
            createdAt = "2026-09-21T00:00:00Z"
        )
        val awg = account.toNode().awg
        requireNotNull(awg)
        assertTrue(awg.jc > 0 && awg.jmin > 0 && awg.jmax > 0)
        assertEquals(1L, awg.h1)
        assertEquals(2L, awg.h2)
        assertEquals(3L, awg.h3)
        assertEquals(4L, awg.h4)
        assertEquals(0, awg.s1 + awg.s2 + awg.s3 + awg.s4)
    }
}