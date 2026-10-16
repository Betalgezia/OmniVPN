package com.betalgezia.omnivpn.vpn

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThroughputMonitorTest {

    /** Feeds one counters sample per second, like the status client does. */
    private class Feed(val monitor: ThroughputMonitor = ThroughputMonitor()) {
        var t = 0L
        var up = 0L
        var down = 0L
        val verdicts = mutableListOf<Pair<Long, Double>>()

        init {
            monitor.reset(0)
            monitor.onSample(StallDetector.Sample(0, 0, 0, 0, true))
        }

        fun run(seconds: Int, downPerSec: Long, upPerSec: Long = downPerSec / 50, screenOn: Boolean = true) {
            repeat(seconds) {
                t += 1_000
                down += downPerSec
                up += upPerSec
                monitor.onSample(StallDetector.Sample(t, up, down, 0, screenOn))?.let { verdicts += t to it }
            }
        }
    }

    @Test
    fun steadySlowDownloadIsReportedOnceWarmupIsOver() {
        val feed = Feed()
        feed.run(120, downPerSec = 200_000) // 1.6 Mbit/s, e.g. a video starved by the server
        assertEquals(1, feed.verdicts.size)
        val (at, mbps) = feed.verdicts.single()
        assertEquals(60_000L, at)
        assertTrue(abs(mbps - 1.6) < 0.01, "got $mbps")
    }

    @Test
    fun nothingIsJudgedDuringWarmup() {
        val feed = Feed()
        feed.run(59, downPerSec = 200_000)
        assertTrue(feed.verdicts.isEmpty())
        feed.run(1, downPerSec = 200_000)
        assertEquals(1, feed.verdicts.size)
    }

    @Test
    fun fastBurstsAreNotSlow() {
        // YouTube on a good server: fills its buffer at full speed, then idles.
        val feed = Feed()
        repeat(15) {
            feed.run(3, downPerSec = 1_500_000)
            feed.run(7, downPerSec = 2_000)
        }
        assertTrue(feed.verdicts.isEmpty())
    }

    @Test
    fun videoCallIsNeverASlowServer() {
        val feed = Feed()
        feed.run(150, downPerSec = 150_000, upPerSec = 150_000)
        assertTrue(feed.verdicts.isEmpty())
    }

    @Test
    fun lightTrafficIsNotJudged() {
        val feed = Feed()
        feed.run(150, downPerSec = 30_000) // messengers, keep-alives
        assertTrue(feed.verdicts.isEmpty())
    }

    @Test
    fun screenOffBreaksTheSeries() {
        val feed = Feed()
        repeat(10) {
            feed.run(15, downPerSec = 200_000)
            feed.run(1, downPerSec = 200_000, screenOn = false)
        }
        assertTrue(feed.verdicts.isEmpty())
    }

    @Test
    fun staysQuietForTheCooldownAfterAVerdict() {
        val feed = Feed()
        feed.run(600, downPerSec = 200_000)
        assertEquals(1, feed.verdicts.size)
        feed.run(100, downPerSec = 200_000)
        assertEquals(2, feed.verdicts.size)
        assertEquals(660_000L, feed.verdicts[1].first)
    }

    @Test
    fun fastEnoughSteadyDownloadIsNotSlow() {
        val feed = Feed()
        feed.run(150, downPerSec = 500_000) // 4 Mbit/s - above the 3 Mbit/s line
        assertTrue(feed.verdicts.isEmpty())
    }
}
