package com.betalgezia.omnivpn.vpn

import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchJournalTest {
    @Test
    fun roundTrips() {
        val events = listOf(
            SwitchEvent(SwitchKind.SLOW, "Amsterdam 01", "Frankfurt Reality", 1.6, 12.4, atMs = 1_760_000_000_000),
            SwitchEvent(SwitchKind.DEAD, "Warsaw", "Amsterdam 01", atMs = 1_760_000_100_000),
            SwitchEvent(SwitchKind.SLOW_STAY, "Riga «Vless»", null, 2.25, null, atMs = 1_760_000_200_000)
        )
        assertEquals(events, decodeSwitchJournal(encodeSwitchJournal(events)))
    }

    @Test
    fun badDataNeverCrashes() {
        assertEquals(emptyList(), decodeSwitchJournal(null))
        assertEquals(emptyList(), decodeSwitchJournal("not json"))
        val oneGood = """[{"kind":"NOPE","from":"x","at":1},{"kind":"DEAD","from":"a","to":"b","at":2}]"""
        assertEquals(listOf(SwitchEvent(SwitchKind.DEAD, "a", "b", atMs = 2)), decodeSwitchJournal(oneGood))
    }
}
