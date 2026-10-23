package com.betalgezia.omnivpn.update

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** What to reconnect to once the new version is installed (see PackageReplacedReceiver). */
sealed interface ReconnectTarget {
    data class Server(val nodeId: Long) : ReconnectTarget
    data object Warp : ReconnectTarget
}

/** The updater's own small state, kept across restarts (and across the update itself). */
@Singleton
class UpdateStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    /** When the app last asked GitHub (successfully or not), wall clock. */
    var lastCheckMs: Long
        get() = prefs.getLong(KEY_LAST_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CHECK, value).apply()

    /** The latest release seen, so the card is there at launch without waiting for the network. */
    var cachedRelease: ReleaseInfo?
        get() = decodeRelease(prefs.getString(KEY_RELEASE, null))
        set(value) = prefs.edit().putString(KEY_RELEASE, value?.let(::encodeRelease)).apply()

    /** «Пропустить эту версию»: the home card stays hidden for this version only. */
    var skippedVersion: String?
        get() = prefs.getString(KEY_SKIPPED, null)
        set(value) = prefs.edit().putString(KEY_SKIPPED, value).apply()

    /** The version handed to the system installer, to say «обновлено» on the next start. */
    var installingVersion: String?
        get() = prefs.getString(KEY_INSTALLING, null)
        set(value) = prefs.edit().putString(KEY_INSTALLING, value).apply()

    fun saveReconnect(target: ReconnectTarget?, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().apply {
            when (target) {
                null -> remove(KEY_RECONNECT).remove(KEY_RECONNECT_AT)
                ReconnectTarget.Warp -> putString(KEY_RECONNECT, "warp").putLong(KEY_RECONNECT_AT, nowMs)
                is ReconnectTarget.Server -> putString(KEY_RECONNECT, "server:${target.nodeId}").putLong(KEY_RECONNECT_AT, nowMs)
            }
        }.commit() // the process is killed moments later by the install
    }

    /** The saved target, once: cleared on read, and ignored when older than [maxAgeMs]. */
    fun takeReconnect(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = RECONNECT_MAX_AGE_MS): ReconnectTarget? {
        val raw = prefs.getString(KEY_RECONNECT, null)
        val at = prefs.getLong(KEY_RECONNECT_AT, 0L)
        saveReconnect(null)
        if (raw == null || nowMs - at !in 0..maxAgeMs) return null
        return when {
            raw == "warp" -> ReconnectTarget.Warp
            raw.startsWith("server:") -> raw.removePrefix("server:").toLongOrNull()?.let { ReconnectTarget.Server(it) }
            else -> null
        }
    }

    private companion object {
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_RELEASE = "release"
        const val KEY_SKIPPED = "skipped_version"
        const val KEY_INSTALLING = "installing_version"
        const val KEY_RECONNECT = "reconnect"
        const val KEY_RECONNECT_AT = "reconnect_at"
        // The install itself takes seconds; anything older is a stale leftover.
        const val RECONNECT_MAX_AGE_MS = 30L * 60 * 1000
    }
}
