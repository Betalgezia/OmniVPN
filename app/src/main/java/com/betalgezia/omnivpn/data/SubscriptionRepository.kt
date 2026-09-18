package com.betalgezia.omnivpn.data

import com.betalgezia.omnivpn.data.local.SubscriptionDao
import com.betalgezia.omnivpn.data.local.SubscriptionEntity
import com.betalgezia.omnivpn.data.local.OmniVpnDatabase
import androidx.room.withTransaction
import com.betalgezia.omnivpn.data.local.toDomain
import com.betalgezia.omnivpn.data.local.toEntity
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Subscription
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val MAX_ERROR_BODY_CHARS = 64 * 1024

@Singleton
class SubscriptionRepository @Inject constructor(
    private val dao: SubscriptionDao,
    private val nodes: NodeRepository,
    private val database: OmniVpnDatabase
) {
    private val addMutex = Mutex()

    val subscriptions: Flow<List<Subscription>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun add(name: String, url: String): Long = addMutex.withLock {
        val normalizedUrl = url.trim()
        validateUrl(normalizedUrl)
        dao.findByUrl(normalizedUrl)?.let { return@withLock it.id }
        dao.insert(
            SubscriptionEntity(
                name = name.trim().ifBlank { URI(normalizedUrl).host ?: normalizedUrl },
                url = normalizedUrl,
                enabled = true,
                lastUpdatedAt = null
            )
        )
    }

    suspend fun delete(subscription: Subscription) {
        database.withTransaction {
            dao.delete(subscription.toEntity())
            nodes.deleteBySource(subscription.id)
        }
    }

    suspend fun refresh(subscription: Subscription): List<Node> = withContext(Dispatchers.IO) {
        require(subscription.id > 0) { "Subscription must be persisted before refresh" }
        val body = fetch(subscription.url)
        val parsed = ConfigParser.parse(body)
        require(parsed.isNotEmpty()) { "Subscription returned no supported nodes" }
        database.withTransaction {
            nodes.replaceSubscription(subscription.id, parsed)
            dao.markUpdated(subscription.id, System.currentTimeMillis())
        }
        parsed
    }

    private fun fetch(urlText: String): String {
        var current = urlText.trim()
        repeat(MAX_REDIRECTS + 1) { hop ->
            validateUrl(current)
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json, text/yaml, text/plain, */*")
            try {
                when (val code = connection.responseCode) {
                    in 200..299 -> return readLimited(connection)
                    in 300..399 -> {
                        require(hop < MAX_REDIRECTS) { "Too many subscription redirects" }
                        current = connection.getHeaderField("Location")?.let { URI(current).resolve(it).toString() }
                            ?: error("Subscription redirect has no Location header")
                    }
                    else -> {
                        val errorBody = connection.errorStream?.let {
                            BufferedReader(InputStreamReader(it)).use { reader ->
                                reader.readTextLimited(MAX_ERROR_BODY_CHARS)
                            }
                        } ?: ""
                        error("Subscription HTTP $code: ${errorBody.take(300)}")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Subscription fetch failed")
    }

    private fun readLimited(connection: HttpURLConnection): String {
        val declared = connection.contentLengthLong
        require(declared <= MAX_BODY_BYTES || declared < 0) { "Subscription response is too large" }
        val buffer = ByteArray(8192)
        var total = 0L
        val out = java.io.ByteArrayOutputStream()
        connection.inputStream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BODY_BYTES) { "Subscription response is too large" }
                out.write(buffer, 0, read)
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

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
    private fun validateUrl(value: String) {
        val uri = runCatching { URI(value.trim()) }.getOrElse { error("Invalid subscription URL") }
        require(uri.scheme.equals("https", ignoreCase=true)) { "Subscription URL must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Subscription URL host is missing" }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_REDIRECTS = 3
        private const val MAX_BODY_BYTES = 5L * 1024L * 1024L
        private const val USER_AGENT = "OmniVPN/0.1"
    }
}