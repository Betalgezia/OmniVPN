package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RateMeterTest {
    private fun sample(atMs: Long, down: Long, up: Long = 0) =
        StallDetector.Sample(nowMs = atMs, uplinkTotal = up, downlinkTotal = down, connectionsIn = 0, screenOn = true)

    @Test
    fun firstSampleHasNoRateSecondGivesTheRawOne() {
        val meter = RateMeter()
        assertNull(meter.onSample(sample(0, 0)))
        assertEquals(1_000_000L to 50_000L, meter.onSample(sample(1_000, 1_000_000, 50_000)))
    }

    @Test
    fun laterSamplesAreSmoothedHalfway() {
        val meter = RateMeter()
        meter.onSample(sample(0, 0))
        meter.onSample(sample(1_000, 1_000_000)) // 1 MB/s
        // Next second nothing: halfway down, not straight to zero.
        assertEquals(500_000L, meter.onSample(sample(2_000, 1_000_000))!!.first)
        assertEquals(250_000L, meter.onSample(sample(3_000, 1_000_000))!!.first)
    }

    @Test
    fun aGapRestartsTheAverageAndADropInTotalsReadsAsZero() {
        val meter = RateMeter()
        meter.onSample(sample(0, 0))
        meter.onSample(sample(1_000, 1_000_000))
        // 5 s gap (screen-off batching): the new rate stands on its own.
        assertEquals(100_000L, meter.onSample(sample(6_000, 1_500_000))!!.first)
        // Counters restarted (new box): never a negative rate.
        assertEquals(50_000L, meter.onSample(sample(7_000, 10))!!.first)
    }

    @Test
    fun resetForgetsThePreviousTunnel() {
        val meter = RateMeter()
        meter.onSample(sample(0, 0))
        meter.onSample(sample(1_000, 1_000_000))
        meter.reset()
        assertNull(meter.onSample(sample(2_000, 5)))
        assertEquals(1_000L to 0L, meter.onSample(sample(3_000, 1_005)))
    }
}
