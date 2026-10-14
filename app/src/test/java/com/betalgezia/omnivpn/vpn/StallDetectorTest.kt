package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StallDetectorTest {

    private fun sample(t: Long, up: Long, down: Long, conns: Int = 1, screenOn: Boolean = false) =
        StallDetector.Sample(nowMs = t, uplinkTotal = up, downlinkTotal = down, connectionsIn = conns, screenOn = screenOn)

    /** Feeds one sample per second from [from] to [to] (inclusive), returns the first reason. */
    private fun StallDetector.run(from: Long, to: Long, f: (Long) -> StallDetector.Sample): Pair<Long, StallDetector.Reason>? {
        var t = from
        while (t <= to) {
            onSample(f(t))?.let { return t to it }
            t += 1000
        }
        return null
    }

    @Test
    fun silenceAloneNeverTriggersWithScreenOff() {
        val d = StallDetector()
        d.reset(0)
        // Paused video, locked screen: nothing moves at all for 5 minutes.
        assertNull(d.run(1000, 300_000) { sample(it, up = 100, down = 100) })
    }

    @Test
    fun steadyTrafficNeverTriggers() {
        val d = StallDetector()
        d.reset(0)
        assertNull(d.run(1000, 120_000) { sample(it, up = it, down = it * 50) })
    }

    @Test
    fun demandWithoutReplyTriggersAfterStall() {
        val d = StallDetector()
        d.reset(0)
        // Traffic flows for 20 s, then apps keep sending but nothing comes back.
        assertNull(d.run(1000, 20_000) { sample(it, up = it, down = it * 10) })
        val hit = d.run(21_000, 60_000) { sample(it, up = it, down = 200_000) }
        assertEquals(StallDetector.Reason.DEMAND_WITHOUT_REPLY, hit?.second)
        // ~6 s after the reply stopped (last change at 20 s, demand from 21 s).
        assertEquals(27_000L, hit?.first)
    }

    @Test
    fun suddenStopOfFastDownloadTriggersOnce() {
        val d = StallDetector()
        d.reset(0)
        // A video streams at ~100 KB/s, then everything freezes (no demand either).
        assertNull(d.run(1000, 20_000) { sample(it, up = 1000, down = it * 100) })
        val hit = d.run(21_000, 40_000) { sample(it, up = 1000, down = 2_000_000) }
        assertEquals(StallDetector.Reason.SUDDEN_STOP, hit?.second)
        assertEquals(28_000L, hit?.first)
        // Same stop is not re-checked again and again.
        assertNull(d.run(41_000, 120_000) { sample(it, up = 1000, down = 2_000_000) })
    }

    @Test
    fun slowTrickleThenSilenceIsNotASuddenStop() {
        val d = StallDetector()
        d.reset(0)
        // Background keep-alives: a few bytes a second, then quiet.
        assertNull(d.run(1000, 20_000) { sample(it, up = 1000, down = it / 100) })
        assertNull(d.run(21_000, 120_000) { sample(it, up = 1000, down = 200) })
    }

    @Test
    fun checksAreRateLimited() {
        val d = StallDetector()
        d.reset(0)
        // Demand with no reply from the very start: first check only once the
        // minimum gap since the connect-time check has passed.
        val first = d.run(1000, 60_000) { sample(it, up = it, down = 0, conns = (it / 1000).toInt()) }
        assertEquals(15_000L, first?.first)
        val second = d.run(16_000, 60_000) { sample(it, up = it, down = 0, conns = (it / 1000).toInt()) }
        assertEquals(30_000L, second?.first)
    }

    @Test
    fun heartbeatOnlyWithScreenOn() {
        val d = StallDetector()
        d.reset(0)
        assertNull(d.run(1000, 59_000) { sample(it, up = 5, down = 5, screenOn = true) })
        val hit = d.run(60_000, 61_000) { sample(it, up = 5, down = 5, screenOn = true) }
        assertEquals(StallDetector.Reason.HEARTBEAT, hit?.second)
    }
}
