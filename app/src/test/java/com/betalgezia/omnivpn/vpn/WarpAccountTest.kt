package com.betalgezia.omnivpn.vpn

import android.util.Base64
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class WarpAccountTest {
    @Test
    fun clientIdBecomesThreePeerReservedBytes() {
        val clientId = Base64.encodeToString(byteArrayOf(1, 127, 255.toByte()), Base64.NO_WRAP)
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