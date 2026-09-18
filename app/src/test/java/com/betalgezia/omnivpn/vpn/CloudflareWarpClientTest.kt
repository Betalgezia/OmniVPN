package com.betalgezia.omnivpn.vpn

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudflareWarpClientTest {
    private fun response(endpoint: String): String =
        JSONObject()
            .put("id", "device-id")
            .put("token", "token")
            .put("account", JSONObject().put("id", "account-id"))
            .put(
                "config",
                JSONObject()
                    .put("client_id", Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP))
                    .put(
                        "interface",
                        JSONObject().put(
                            "addresses",
                            JSONObject()
                                .put("v4", "172.16.0.2")
                                .put("v6", "2606:4700:4700::1001")
                        )
                    )
                    .put(
                        "peers",
                        JSONArray().put(
                            JSONObject()
                                .put("public_key", "peer-public")
                                .put("endpoint", JSONObject().put("host", endpoint))
                        )
                    )
            )
            .toString()

    @Test
    fun usesCloudflareEndpointWhenDefaultWasRequested() {
        val account = CloudflareWarpClient().parseRegistration(
            response("engage.example.com:2408"),
            "private-key",
            WarpAccount.DEFAULT_ENDPOINT
        )
        assertEquals("engage.example.com:2408", account.endpoint)
    }

    @Test
    fun keepsCustomEndpointOverCloudflareResponse() {
        val account = CloudflareWarpClient().parseRegistration(
            response("engage.example.com:2408"),
            "private-key",
            "162.159.192.1:2408"
        )
        assertEquals("162.159.192.1:2408", account.endpoint)
    }

    @Test
    fun ignoresMalformedCloudflareEndpoint() {
        val account = CloudflareWarpClient().parseRegistration(
            response("not-an-endpoint"),
            "private-key",
            WarpAccount.DEFAULT_ENDPOINT
        )
        assertEquals(WarpAccount.DEFAULT_ENDPOINT, account.endpoint)
    }
}
