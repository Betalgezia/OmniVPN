package com.betalgezia.omnivpn.vpn

import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Streams sing-box's own internal log lines - per-connection dial attempts,
 * routing decisions, handshake failures - into logcat under the "SingBoxLog"
 * tag, for as long as a VPN session is active.
 *
 * This is deliberately separate from CommandServerHandler.writeDebugMessage
 * (which OmniVpnService implements, surfaced as "[libbox] ..." lines): that
 * callback is a low-volume native-debug hook and is NOT where sing-box's
 * regular per-connection log lines go, no matter what SingBoxConfigBuilder's
 * "log.level" is set to. The real log stream is only ever delivered to a
 * CommandClient that explicitly subscribes with Libbox.CommandLog over the
 * local command socket - exactly the mechanism the official sing-box Android
 * app's "Logs" screen uses. Without a client doing that, nothing sing-box
 * logs internally is ever observable, which is why bumping the log level
 * alone never produced any evidence. We run one headlessly so the app
 * finally has real per-connection diagnostics.
 *
 * CommandClient.connect() dials the socket and returns quickly; the
 * subscription and the writeLogs()/disconnected() callbacks all fire later,
 * asynchronously, from Libbox's own goroutines - so this class never blocks
 * the caller. It just owns the client's lifecycle and reconnects with a
 * short backoff if the stream drops while it's still supposed to be running
 * (e.g. across a service reload).
 */
class SingBoxLogClient(private val scope: CoroutineScope) {

    @Volatile
    private var client: CommandClient? = null
    private var job: Job? = null
    private val wantRunning = AtomicBoolean(false)

    fun start() {
        if (wantRunning.getAndSet(true)) return
        job = scope.launch(Dispatchers.IO) { connectLoop() }
    }

    fun stop() {
        wantRunning.set(false)
        job?.cancel()
        job = null
        runCatching { client?.disconnect() }
        client = null
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (isActive && wantRunning.get()) {
            attempt++
            val disconnectedSignal = CompletableDeferred<Unit>()
            val handler = object : CommandClientHandler {
                override fun connected() {
                    android.util.Log.i(TAG, "log stream connected (attempt $attempt)")
                }

                override fun disconnected(message: String) {
                    android.util.Log.i(TAG, "log stream disconnected: $message")
                    disconnectedSignal.complete(Unit)
                }

                override fun writeLogs(messages: LogIterator) {
                    while (messages.hasNext()) {
                        android.util.Log.i(LOG_TAG, messages.next().message)
                    }
                }

                override fun clearLogs() {}
                override fun initializeClashMode(modeList: StringIterator, currentMode: String) {}
                override fun updateClashMode(newMode: String) {}
                override fun setDefaultLogLevel(level: Int) {}
                override fun writeConnectionEvents(events: ConnectionEvents) {}
                override fun writeDNSQuery(query: DnsQuery) {}
                override fun writeGroups(groups: OutboundGroupIterator) {}
                override fun writeOutbounds(outbounds: OutboundGroupItemIterator) {}
                override fun writeStatus(status: StatusMessage) {}
            }
            val options = CommandClientOptions().apply { addCommand(Libbox.CommandLog) }
            val newClient = Libbox.newCommandClient(handler, options)
            client = newClient
            try {
                newClient.connect()
                disconnectedSignal.await()
            } catch (t: Throwable) {
                if (!wantRunning.get()) return
                android.util.Log.w(TAG, "log stream connect failed (attempt $attempt)", t)
            } finally {
                client = null
            }
            if (!wantRunning.get() || !isActive) return
            delay(RETRY_DELAY_MS)
        }
    }

    private companion object {
        private const val TAG = "SingBoxLogClient"
        private const val LOG_TAG = "SingBoxLog"
        private const val RETRY_DELAY_MS = 1000L
    }
}
