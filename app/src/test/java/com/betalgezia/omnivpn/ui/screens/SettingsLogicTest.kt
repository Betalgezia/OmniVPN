package com.betalgezia.omnivpn.ui.screens

import com.betalgezia.omnivpn.vpn.AwgObfuscation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsLogicTest {
    @Test
    fun warpEndpointFormat() {
        assertTrue(isWarpEndpoint("162.159.192.1:2408"))
        assertTrue(isWarpEndpoint(" 162.159.192.7:500 "))
        assertFalse(isWarpEndpoint("engage.cloudflareclient.com:2408"))
        assertFalse(isWarpEndpoint("162.159.192.1"))
        assertFalse(isWarpEndpoint("[2606:4700:d0::a29f:c001]:2408"))
    }

    @Test
    fun summaries() {
        assertEquals("IPv6 вкл · стандартный endpoint", warpSummary(true, "162.159.192.1:2408"))
        assertEquals("IPv6 выкл · endpoint 162.159.192.7:2408", warpSummary(false, "162.159.192.7:2408"))
        assertEquals("Стандартные", obfuscationSummary(false, 52))
        assertEquals("Подобранные · 52 мс", obfuscationSummary(true, 52))
        assertEquals("Подобранные", obfuscationSummary(true, null))
        assertEquals("Каждые 1 ч", autoUpdateSummary(true, 60))
        assertEquals("Выключено — только вручную", autoUpdateSummary(false, 60))
    }

    @Test
    fun obfuscationTexts() {
        val tuned = AwgObfuscation(jc = 4, jmin = 40, jmax = 70, i1 = "<b 0xc00000000108><r 700>", endpoint = "162.159.192.7:2408")
        assertEquals(
            listOf("jc 4", "jmin 40", "jmax 70", "i1 QUIC", "s1–s4 0", "h1–h4 стд", "162.159.192.7:2408"),
            obfuscationChips(tuned)
        )
        assertEquals("jc 4 · 40–70 · QUIC · 162.159.192.7", tuneCandidateLabel(tuned))
        assertEquals("jc 8 · 64–128 · без i1", tuneCandidateLabel(AwgObfuscation(jc = 8, jmin = 64, jmax = 128, i1 = null)))
    }
}
