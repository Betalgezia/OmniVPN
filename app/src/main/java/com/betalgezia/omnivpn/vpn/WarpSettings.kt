package com.betalgezia.omnivpn.vpn

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Plain (unencrypted) app-level toggles for WARP connection behaviour -
 * distinct from WarpStorage, which persists the account's own credentials
 * and is encrypted. Nothing stored here is sensitive, so a plain
 * SharedPreferences file is enough (compare VpnConfigStore/WarpStorage,
 * which both need the Keystore-backed AES layer because they hold actual
 * secrets).
 *
 * @Inject constructor rather than manual construction everywhere: lets
 * MainViewModel take this as a normal Hilt dependency, while VpnController
 * is still free to just `WarpSettings(context)` it inline at the one call
 * site that needs it (same as it already does for WarpStorage) - both
 * forms work since @ApplicationContext Context is already a standard Hilt
 * binding.
 */
class WarpSettings @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether WARP's own AmneziaWG peer advertises an IPv6 local address/
     * allowed_ips alongside IPv4 (see WarpAccount.toSingBoxEndpoint).
     * Defaults to true - that's the account's actual registered dual-stack
     * address, and what "Get WARP" used before 2026-09-21.
     *
     * Was hard-dropped that day (commit 6d32a3c) alongside the peer's
     * `reserved` field, as a blind debugging move: matching a manually-
     * imported reference config that stayed connected by removing every
     * remaining structural difference, never confirmed afterward as the
     * thing that actually mattered (see the comment on
     * WarpAccount.toSingBoxEndpoint for the full history). A router-side
     * logcat capture on 2026-09-26 then showed the cost of leaving it off
     * by default: sing-box rejects any connection whose destination
     * resolves to IPv6 outright ("missing IPv6 local address"), which
     * silently breaks ordinary Google/YouTube-style traffic on any network
     * that hands out real IPv6 (many home routers do; Russian mobile data
     * mostly doesn't, which is why this never showed up there).
     *
     * Brought back as a toggle rather than just unconditionally on: if a
     * given network's own IPv6 path turns out to be the broken part (unlike
     * this bug, where the client was the problem), turning it back off is
     * one tap instead of a rebuild.
     */
    var ipv6Enabled: Boolean
        get() = prefs.getBoolean(KEY_IPV6_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_IPV6_ENABLED, value).apply()

    /**
     * Whether subscriptions refresh themselves in the background (see
     * SubscriptionAutoUpdateWorker). Defaults to on, per the Nocturne v2
     * handoff. The interval below is only meaningful while this is true.
     */
    var autoUpdateEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, value).apply()

    /**
     * Auto-update period in minutes. Default 60; the UI offers
     * 15/30/60/180/360/720/1440 and 15 is the floor (WorkManager's own
     * minimum, which the handoff also sets as the product minimum).
     */
    var autoUpdateIntervalMinutes: Int
        get() = prefs.getInt(KEY_AUTO_UPDATE_INTERVAL, DEFAULT_AUTO_UPDATE_INTERVAL)
        set(value) = prefs.edit().putInt(KEY_AUTO_UPDATE_INTERVAL, value).apply()

    /**
     * Quick Settings tile default action when nothing is connected: true =
     * start WARP (the handoff default), false = connect to the fastest
     * subscription server. Optional tweak from the prototype.
     */
    var quickTileStartsWarp: Boolean
        get() = prefs.getBoolean(KEY_QUICK_TILE_WARP, true)
        set(value) = prefs.edit().putBoolean(KEY_QUICK_TILE_WARP, value).apply()

    /**
     * When a subscription server stops passing traffic (connect-time check or
     * the running watchdog), switch to another server of the same subscription
     * by itself - see ConnectionManager. On by default; WARP is never switched
     * away from automatically, only reported.
     */
    var autoSwitchEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SWITCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SWITCH, value).apply()

    /**
     * The Settings sheet's "Режим проверки" choice, used by the per-server
     * "Тест" tap. Used to live only in memory and reset to FULL on every
     * launch - so after any restart (including every reinstall) a "Тест" tap
     * silently ran the slow full test even though "Быстрая" had been picked.
     */
    var testMode: TestMode
        get() = runCatching { TestMode.valueOf(prefs.getString(KEY_TEST_MODE, null).orEmpty()) }
            .getOrDefault(TestMode.FULL)
        set(value) = prefs.edit().putString(KEY_TEST_MODE, value.name).apply()

    /** Subscription groups the user collapsed on the main screen. */
    var collapsedSubscriptionIds: Set<Long>
        get() = prefs.getStringSet(KEY_COLLAPSED_SUBS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()
        set(value) = prefs.edit().putStringSet(KEY_COLLAPSED_SUBS, value.map { it.toString() }.toSet()).apply()

    /**
     * User-chosen subscription order (ids, first = top of the list). Kept here
     * rather than as a Room column so no schema migration is needed:
     * subscriptions missing from it (added since) sort after the ordered ones
     * in their original order, and ids of deleted subscriptions are ignored.
     */
    var subscriptionOrder: List<Long>
        get() = prefs.getString(KEY_SUB_ORDER, null)
            ?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()
        set(value) = prefs.edit().putString(KEY_SUB_ORDER, value.joinToString(",")).apply()

    companion object {
        private const val PREFS = "omnivpn_settings"
        private const val KEY_IPV6_ENABLED = "warp_ipv6_enabled"
        private const val KEY_AUTO_UPDATE_ENABLED = "sub_auto_update_enabled"
        private const val KEY_AUTO_UPDATE_INTERVAL = "sub_auto_update_interval_min"
        private const val KEY_QUICK_TILE_WARP = "quick_tile_starts_warp"
        private const val KEY_AUTO_SWITCH = "auto_switch_dead_server"
        private const val KEY_TEST_MODE = "server_test_mode"
        private const val KEY_COLLAPSED_SUBS = "collapsed_subscription_ids"
        private const val KEY_SUB_ORDER = "subscription_order"
        const val DEFAULT_AUTO_UPDATE_INTERVAL = 60
        val AUTO_UPDATE_INTERVALS = listOf(15, 30, 60, 180, 360, 720, 1440)
    }
}
