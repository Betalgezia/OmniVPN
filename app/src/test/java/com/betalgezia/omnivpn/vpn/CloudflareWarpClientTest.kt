package com.betalgezia.omnivpn.vpn

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
                    .put("client_id", "AQID")
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
                                .put("public_key", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")
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
        // Must differ from WarpAccount.DEFAULT_ENDPOINT, or parseRegistration
        // treats it as "no override requested" and takes Cloudflare's
        // endpoint instead - which is exactly the behavior this test exists
        // to rule out. (This literal used to be a safe choice, until
        // DEFAULT_ENDPOINT itself became "162.159.192.1:2408" in e123adf.)
        val customEndpoint = "203.0.113.5:2408"
        val account = CloudflareWarpClient().parseRegistration(
            response("engage.example.com:2408"),
            "private-key",
            customEndpoint
        )
        assertEquals(customEndpoint, account.endpoint)
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
