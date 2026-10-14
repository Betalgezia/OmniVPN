package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.os.PowerManager
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.DnsQuery
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides WHEN to re-check a running tunnel, from sing-box's traffic counters.
 *
 * "No traffic" alone is not a failure - a paused video or a locked screen is
 * also zero bytes - so silence never triggers a switch by itself. What
 * triggers a (cheap) check through the core is a sign of breakage:
 *  - DEMAND_WITHOUT_REPLY: apps keep sending / opening connections but not a
 *    single byte has come back for [stallMs];
 *  - SUDDEN_STOP: data was flowing fast (a video, a download) and then stopped
 *    dead for [suddenStopMs] - once per stop;
 *  - HEARTBEAT: every [heartbeatMs] while the screen is on, as a backstop.
 * Checks are at least [minProbeGapMs] apart. Pure logic, no Android, so it is
 * unit-tested directly (StallDetectorTest).
 */
class StallDetector(
    private val stallMs: Long = 6_000,
    private val suddenStopMs: Long = 8_000,
    private val flowingBytesPerSec: Long = 10_000,
    private val minProbeGapMs: Long = 15_000,
    private val heartbeatMs: Long = 60_000
) {
    data class Sample(
        val nowMs: Long,
        val uplinkTotal: Long,
        val downlinkTotal: Long,
        val connectionsIn: Int,
        val screenOn: Boolean
    )

    enum class Reason { DEMAND_WITHOUT_REPLY, SUDDEN_STOP, HEARTBEAT }

    private var last: Sample? = null
    private var lastDownChangeAt = 0L
    private var flowingBeforeStall = false
    private var suddenStopChecked = false
    private var demandSince: Long? = null
    private var lastProbeAt = 0L

    /** Call once when watching starts - the connect-time check just passed. */
    fun reset(nowMs: Long) {
        last = null
        lastDownChangeAt = nowMs
        flowingBeforeStall = false
        suddenStopChecked = false
        demandSince = null
        lastProbeAt = nowMs
    }

    /** Feeds one counters sample; returns why to check now, or null. */
    fun onSample(s: Sample): Reason? {
        val prev = last
        last = s
        if (prev == null) return heartbeat(s)

        if (s.downlinkTotal != prev.downlinkTotal) {
            val dtMs = (s.nowMs - prev.nowMs).coerceAtLeast(1)
            val rate = (s.downlinkTotal - prev.downlinkTotal).coerceAtLeast(0) * 1000 / dtMs
            lastDownChangeAt = s.nowMs
            flowingBeforeStall = rate >= flowingBytesPerSec
            suddenStopChecked = false
            demandSince = null
            return heartbeat(s)
        }

        val demand = s.uplinkTotal != prev.uplinkTotal || s.connectionsIn > prev.connectionsIn
        if (demand && demandSince == null) demandSince = s.nowMs

        if (s.nowMs - lastProbeAt < minProbeGapMs) return null

        demandSince?.let { since ->
            if (s.nowMs - since >= stallMs) {
                demandSince = null
                return probe(s, Reason.DEMAND_WITHOUT_REPLY)
            }
        }
        if (flowingBeforeStall && !suddenStopChecked && s.nowMs - lastDownChangeAt >= suddenStopMs) {
            suddenStopChecked = true
            return probe(s, Reason.SUDDEN_STOP)
        }
        return heartbeat(s)
    }

    private fun heartbeat(s: Sample): Reason? =
        if (s.screenOn && s.nowMs - lastProbeAt >= heartbeatMs) probe(s, Reason.HEARTBEAT) else null

    private fun probe(s: Sample, reason: Reason): Reason {
        lastProbeAt = s.nowMs
        return reason
    }
}

/**
 * Watches a running tunnel (see [StallDetector]) and calls [onDead] once two
 * checks in a row fail. [probe] must return true when traffic flows. Runs
 * until [stop] or until it has reported a dead tunnel.
 *
 * Timing (server dies mid-video): ~8 s of silence, check up to ~5 s, confirm
 * ~5 s - so about 15-20 s before a switch, without reacting to short mobile
 * hiccups.
 */
class TrafficWatchdog(
    private val context: Context,
    private val scope: CoroutineScope,
    private val probe: suspend () -> Boolean,
    private val onDead: () -> Unit
) {
    private var job: Job? = null
    @Volatile
    private var client: CommandClient? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { client?.disconnect() }
        client = null
    }

    private suspend fun run() {
        val samples = Channel<StallDetector.Sample>(Channel.CONFLATED)
        val power = context.getSystemService(PowerManager::class.java)
        val handler = object : CommandClientHandler {
            override fun writeStatus(status: StatusMessage) {
                // Read the Go-backed object right here; it isn't ours to keep.
                samples.trySend(
                    StallDetector.Sample(
                        nowMs = android.os.SystemClock.elapsedRealtime(),
                        uplinkTotal = status.uplinkTotal,
                        downlinkTotal = status.downlinkTotal,
                        connectionsIn = status.connectionsIn,
                        screenOn = power?.isInteractive != false
                    )
                )
            }

            override fun connected() {}
            override fun disconnected(message: String) {}
            override fun clearLogs() {}
            override fun initializeClashMode(modeList: StringIterator, currentMode: String) {}
            override fun updateClashMode(newMode: String) {}
            override fun setDefaultLogLevel(level: Int) {}
            override fun writeConnectionEvents(events: ConnectionEvents) {}
            override fun writeDNSQuery(query: DnsQuery) {}
            override fun writeGroups(groups: OutboundGroupIterator) {}
            override fun writeLogs(messages: LogIterator) {}
            override fun writeOutbounds(outbounds: OutboundGroupItemIterator) {}
        }
        runCatching {
            val options = CommandClientOptions().apply {
                addCommand(Libbox.CommandStatus)
                statusInterval = STATUS_INTERVAL_NS
            }
            Libbox.newCommandClient(handler, options).also {
                client = it
                it.connect()
            }
        }.onFailure {
            android.util.Log.w(TAG, "status stream unavailable - heartbeat checks only", it)
        }

        val detector = StallDetector()
        detector.reset(android.os.SystemClock.elapsedRealtime())
        var lastSample: StallDetector.Sample? = null
        var gotStatus = false
        android.util.Log.i(TAG, "watching")
        try {
            while (currentCoroutineContext().isActive) {
                // Without status messages (stream down) keep ticking on the last
                // known totals, so at least the screen-on heartbeat still runs.
                val received = withTimeoutOrNull(FALLBACK_TICK_MS) { samples.receive() }
                if (received != null && !gotStatus) {
                    gotStatus = true
                    android.util.Log.i(TAG, "status stream live (trafficAvailable via totals)")
                }
                val sample = received ?: StallDetector.Sample(
                    nowMs = android.os.SystemClock.elapsedRealtime(),
                    uplinkTotal = lastSample?.uplinkTotal ?: 0,
                    downlinkTotal = lastSample?.downlinkTotal ?: 0,
                    connectionsIn = lastSample?.connectionsIn ?: 0,
                    screenOn = power?.isInteractive != false
                )
                lastSample = sample
                val reason = detector.onSample(sample) ?: continue

                android.util.Log.i(TAG, "check: $reason (down=${sample.downlinkTotal} up=${sample.uplinkTotal} in=${sample.connectionsIn})")
                if (probe()) continue
                delay(CONFIRM_DELAY_MS)
                if (probe()) {
                    android.util.Log.i(TAG, "check: first failed, confirm passed - keeping")
                    continue
                }
                android.util.Log.w(TAG, "check: two failures in a row - tunnel is dead")
                onDead()
                return
            }
        } finally {
            runCatching { client?.disconnect() }
            client = null
        }
    }

    private companion object {
        private const val TAG = "TrafficWatchdog"
        // libbox takes a Go time.Duration: nanoseconds.
        private const val STATUS_INTERVAL_NS = 1_000_000_000L
        private const val FALLBACK_TICK_MS = 3_000L
        private const val CONFIRM_DELAY_MS = 1_000L
    }
}
