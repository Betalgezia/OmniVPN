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
}