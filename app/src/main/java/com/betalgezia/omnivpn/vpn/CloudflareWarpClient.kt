package com.betalgezia.omnivpn.vpn
private const val MAX_ERROR_BODY_CHARS = 64 * 1024


import android.util.Base64
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CloudflareWarpClient {
    suspend fun register(licenseKey: String? = null, endpointOverride: String? = null): WarpAccount = withContext(Dispatchers.IO) {
        val endpoint = endpointOverride?.trim().takeUnless { it.isNullOrEmpty() } ?: WarpAccount.DEFAULT_ENDPOINT
        validateEndpoint(endpoint)
        val keyPair = generateKeyPair()
        val body = JSONObject()
            .put("key", keyPair.publicKey)
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", nowIso8601())
            .put("model", "PC")
            .put("type", "Android")
            .put("locale", "en_US")
            .toString()

        var lastError: Throwable? = null
        var registered: WarpAccount? = null
        var registrationHost: String? = null
        for (host in API_HOSTS) {
            try {
                val response = request("https://$host/v0a2158/reg", "POST", body, null)
                registered = parseRegistration(response, keyPair.privateKey, endpoint)
                registrationHost = host
                break
            } catch (t: Throwable) {
                lastError = t
            }
        }
        val account = registered ?: throw IllegalStateException(
            "WARP registration failed: ${lastError?.message ?: "unknown error"}",
            lastError
        )
        val license = licenseKey?.trim().takeUnless { it.isNullOrEmpty() }
        return@withContext if (license == null) {
            account
        } else {
            runCatching { applyLicenseAtHost(account, license, registrationHost!!) }
                .getOrElse {
                    account.copy(license = null, warpPlus = false)
                }
        }
    }

    internal fun parseRegistration(body: String, privateKey: String, endpoint: String): WarpAccount {
        val root = JSONObject(body)
        val config = root.optJSONObject("config") ?: error("Cloudflare WARP response has no config")
        val peer = config.optJSONArray("peers")?.optJSONObject(0) ?: error("Cloudflare WARP response has no peer")
        val peerPublicKey = peer.optString("public_key")
        require(peerPublicKey.isNotBlank()) { "Cloudflare WARP peer public key is missing" }
        val apiEndpoint = peer.optJSONObject("endpoint")?.optString("host").orEmpty().trim()
        val effectiveEndpoint = endpoint.takeIf { it != WarpAccount.DEFAULT_ENDPOINT }
            ?: apiEndpoint.takeIf { it.isNotBlank() && isValidEndpoint(apiEndpoint) }
            ?: WarpAccount.DEFAULT_ENDPOINT
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
            ?: error("Cloudflare WARP response has no interface addresses")
        val clientV4 = addresses.optString("v4")
        val clientV6 = addresses.optString("v6")
        require(clientV4.isNotBlank() && clientV6.isNotBlank()) { "Cloudflare WARP client addresses are missing" }
        val clientId = config.optString("client_id").takeIf { it.isNotBlank() }
        if (clientId != null) {
            val decoded = runCatching { Base64.decode(clientId, Base64.DEFAULT or Base64.NO_WRAP) }.getOrNull()
            require(decoded?.size == 3) { "Cloudflare WARP client_id must decode to 3 bytes" }
        }
        val account = root.optJSONObject("account") ?: error("Cloudflare WARP response has no account")
        val accountId = account.optString("id")
        val deviceId = root.optString("id")
        val token = root.optString("token")
        require(accountId.isNotBlank() && deviceId.isNotBlank() && token.isNotBlank()) { "Cloudflare WARP account/device credentials are missing" }
        return WarpAccount(
            privateKey = privateKey, peerPublicKey = peerPublicKey, clientV4 = clientV4, clientV6 = clientV6,
            clientId = clientId, accountId = accountId, deviceId = deviceId, token = token,
            license = account.optString("license").takeIf { it.isNotBlank() },
            warpPlus = account.optBoolean("warp_plus", false), endpoint = effectiveEndpoint, createdAt = nowIso8601()
        )
    }

    suspend fun applyLicense(account: WarpAccount, license: String): WarpAccount = withContext(Dispatchers.IO) {
        val normalized = license.trim()
        require(normalized.isNotEmpty()) { "WARP license is empty" }
        var lastError: Throwable? = null
        for (host in API_HOSTS) {
            try {
                return@withContext applyLicenseAtHost(account, normalized, host)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw IllegalStateException(
            "WARP license application failed: ${lastError?.message ?: "unknown error"}",
            lastError
        )
    }

    private fun applyLicenseAtHost(account: WarpAccount, license: String, host: String): WarpAccount {
        val response = request(
            "https://$host/v0a2158/reg/${account.deviceId}/account",
            "PATCH",
            JSONObject().put("license", license).toString(),
            account.token
        )
        val remoteAccount = JSONObject(response).optJSONObject("account")
        return account.copy(
            license = license,
            warpPlus = remoteAccount?.optBoolean("warp_plus", true) ?: true
        )
    }
    private fun request(url: String, method: String, body: String, bearer: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.useCaches = false
        connection.doInput = true
        connection.doOutput = method == "POST" || method == "PATCH"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
        connection.setRequestProperty("CF-Client-Version", "a-7.21-0721")
        bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return try {
            if (connection.doOutput) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = BufferedReader(InputStreamReader(stream ?: error("HTTP $code"))).use {
                it.readTextLimited(MAX_ERROR_BODY_CHARS)
            }
            if (code !in 200..299) error("HTTP $code: ${text.take(400)}")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun generateKeyPair(): GeneratedKeyPair {
        val generator = X25519KeyPairGenerator().apply { init(X25519KeyGenerationParameters(SecureRandom())) }
        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val privateKey = (pair.private as X25519PrivateKeyParameters).encoded
        val publicKey = (pair.public as X25519PublicKeyParameters).encoded
        return GeneratedKeyPair(Base64.encodeToString(privateKey, Base64.NO_WRAP), Base64.encodeToString(publicKey, Base64.NO_WRAP))
    }

    private fun isValidEndpoint(value: String): Boolean {
        val raw = value.trim()
        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            if (close <= 0 || close + 2 > raw.length || raw[close + 1] != ':') return false
            return raw.substring(close + 2).toIntOrNull()?.let { it in 1..65535 } == true
        }
        if (raw.count { it == ':' } != 1) return false
        val port = raw.substringAfterLast(':').toIntOrNull() ?: return false
        return port in 1..65535
    }

    private fun validateEndpoint(value: String) {
        val raw = value.trim()
        if (raw.startsWith("[")) {
            val close = raw.indexOf(']')
            require(close > 0 && close + 2 <= raw.length && raw[close + 1] == ':') { "WARP endpoint must be host:port" }
            val port = raw.substring(close + 2).toIntOrNull() ?: error("WARP endpoint port is invalid")
            require(port in 1..65535) { "WARP endpoint port is invalid" }
            return
        }
        require(raw.count { it == ':' } <= 1) { "WARP IPv6 endpoint must use [ipv6]:port" }
        val colon = raw.lastIndexOf(':')
        require(colon > 0 && colon < raw.length - 1) { "WARP endpoint must be host:port" }
        val port = raw.substring(colon + 1).toIntOrNull() ?: error("WARP endpoint port is invalid")
        require(port in 1..65535) { "WARP endpoint port is invalid" }
    }

    private fun nowIso8601(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
    private data class GeneratedKeyPair(val privateKey: String, val publicKey: String)

    private fun BufferedReader.readTextLimited(maxChars: Int): String {
        val out = StringBuilder()
        val buffer = CharArray(8192)
        while (out.length < maxChars) {
            val read = read(buffer, 0, minOf(buffer.size, maxChars - out.length))
            if (read < 0) break
            out.append(buffer, 0, read)
        }
        return out.toString()
    }
    companion object { private val API_HOSTS = listOf("api.devices.cloudflare.com", "api.cloudflareclient.com") }
}