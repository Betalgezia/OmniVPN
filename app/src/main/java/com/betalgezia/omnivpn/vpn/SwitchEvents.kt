package com.betalgezia.omnivpn.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray
import org.json.JSONObject

/** What an automatic switch decision was about (see ConnectionManager). */
enum class SwitchKind {
    /** Left a slow server for a faster one of the same subscription. */
    SLOW,
    /** The server was slow, but nothing in the subscription was faster - stayed. */
    SLOW_STAY,
    /** The server stopped passing traffic - moved to the next one. */
    DEAD
}

/**
 * One automatic switch decision, for the home screen card and the
 * «Переключения» journal. Names, not node ids: a refreshed subscription
 * re-creates its nodes, and the journal should still read the same.
 */
data class SwitchEvent(
    val kind: SwitchKind,
    val from: String,
    val to: String? = null,
    val fromMbps: Double? = null,
    val toMbps: Double? = null,
    /** Wall-clock time (System.currentTimeMillis). */
    val atMs: Long
)

/** A slow-server check under way: the current server ([candidate] null) or a candidate. */
data class SpeedMeasuring(val from: String, val candidate: String?)

internal fun encodeSwitchJournal(events: List<SwitchEvent>): String {
    val array = JSONArray()
    events.forEach { e ->
        array.put(
            JSONObject().apply {
                put("kind", e.kind.name)
                put("from", e.from)
                e.to?.let { put("to", it) }
                e.fromMbps?.let { put("fromMbps", it) }
                e.toMbps?.let { put("toMbps", it) }
                put("at", e.atMs)
            }
        )
    }
    return array.toString()
}

/** Tolerant: an unreadable entry (or the whole value) is dropped, never a crash. */
internal fun decodeSwitchJournal(json: String?): List<SwitchEvent> {
    if (json.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        runCatching {
            val o = array.getJSONObject(i)
            SwitchEvent(
                kind = SwitchKind.valueOf(o.getString("kind")),
                from = o.getString("from"),
                to = o.optString("to").takeIf { o.has("to") && it.isNotBlank() },
                fromMbps = if (o.has("fromMbps")) o.getDouble("fromMbps") else null,
                toMbps = if (o.has("toMbps")) o.getDouble("toMbps") else null,
                atMs = o.getLong("at")
            )
        }.getOrNull()
    }
}

/** Keeps the last [MAX] switch events across app restarts. */
@Singleton
class SwitchJournalStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("switch_journal", Context.MODE_PRIVATE)

    fun load(): List<SwitchEvent> = decodeSwitchJournal(prefs.getString(KEY, null)).take(MAX)

    fun save(events: List<SwitchEvent>) {
        prefs.edit().putString(KEY, encodeSwitchJournal(events.take(MAX))).apply()
    }

    companion object {
        const val MAX = 20
        private const val KEY = "events"
    }
}
