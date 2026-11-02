package com.betalgezia.omnivpn.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.betalgezia.omnivpn.data.model.Protocol
import io.appmetrica.analytics.AppMetrica
import io.appmetrica.analytics.AppMetricaConfig

/**
 * The app's only bridge to Yandex AppMetrica.
 *
 * AppMetrica itself collects the base audience reports the dashboard needs
 * (first launches, active users, sessions, retention). This object adds the
 * product events OmniVPN cares about and deliberately sends nothing else:
 * no node names or addresses, subscription URLs, configuration contents,
 * keys, DNS queries or traffic.
 *
 * Every call is wrapped: analytics must never be able to break the VPN, and
 * AppMetrica queues reports offline, so a failed/flaky network only delays
 * delivery.
 */
object Analytics {
    /** The AppMetrica API key is a public app identifier, not a secret. */
    const val API_KEY = "886320c8-885b-4a7b-91e5-14443f34e5d8"

    const val WARP_PROTOCOL = "warp"

    private const val TAG = "OmniVpnAnalytics"

    // Event names (lowercase snake_case, stable - dashboards are built on them).
    private const val EVENT_APP_LAUNCH = "app_launch"
    private const val EVENT_CONNECT_ATTEMPT = "vpn_connect_attempt"
    private const val EVENT_CONNECTED = "vpn_connected"
    private const val EVENT_CONNECT_ERROR = "vpn_connect_error"
    private const val EVENT_DISCONNECTED = "vpn_disconnected"
    private const val EVENT_WARP_CONNECTED = "warp_connected"
    private const val EVENT_AMNEZIAWG_CONNECTED = "amneziawg_connected"

    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_PHASE = "phase"
    private const val KEY_REASON = "reason"

    @Volatile
    private var active = false

    /**
     * Called once from [com.betalgezia.omnivpn.OmniVpnApplication.onCreate],
     * before any screen or VPN code runs. A failure here disables analytics
     * for this process but never crashes the app.
     */
    fun activate(context: Context) {
        if (active) return
        runCatching {
            val builder = AppMetricaConfig.newConfigBuilder(API_KEY)
                // Privacy: no location, no advertising ID, no revenue auto
                // collection. The corresponding SDK modules are excluded in
                // app/build.gradle.kts as well.
                .withLocationTracking(false)
                .withAdvIdentifiersTracking(false)
                .withRevenueAutoTrackingEnabled(false)
                // Usage analytics only: no extra crash/ANR interception and no
                // global uncaught-exception handler around the VPN code.
                .withCrashReporting(false)
                .withNativeCrashReporting(false)
                .withAnrMonitoring(false)
                // Sessions/first launches/retention are what the base reports
                // are built from; keep the automatic tracking explicit.
                .withSessionsAutoTrackingEnabled(true)
                .withAppOpenTrackingEnabled(true)

            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (debuggable) builder.withLogs()

            AppMetrica.activate(context.applicationContext, builder.build())
            active = true
            Log.i(TAG, "AppMetrica activated")
        }.onFailure {
            Log.e(TAG, "AppMetrica activation failed; analytics disabled", it)
        }
    }

    /** A cold UI start (the SDK's own first-launch/session reports are separate). */
    fun appLaunch() = report(EVENT_APP_LAUNCH)

    /** A new VPN start was requested, for any protocol. */
    fun connectAttempt(protocol: String) =
        report(EVENT_CONNECT_ATTEMPT, mapOf(KEY_PROTOCOL to protocol))

    /** The tunnel reached CONNECTED. [protocol] is "warp", "amneziawg", … */
    fun connected(protocol: String) {
        report(EVENT_CONNECTED, mapOf(KEY_PROTOCOL to protocol))
        when (protocol) {
            WARP_PROTOCOL -> report(EVENT_WARP_CONNECTED)
            "amneziawg" -> report(EVENT_AMNEZIAWG_CONNECTED)
        }
    }

    /** The attempt failed, or an established tunnel failed. No error text is sent. */
    fun connectionError(protocol: String, phase: String) =
        report(EVENT_CONNECT_ERROR, mapOf(KEY_PROTOCOL to protocol, KEY_PHASE to phase))

    /** A previously connected tunnel went down. */
    fun disconnected(protocol: String, reason: String) =
        report(EVENT_DISCONNECTED, mapOf(KEY_PROTOCOL to protocol, KEY_REASON to reason))

    /** Stable, non-personal protocol label for event parameters. */
    fun protocolName(protocol: Protocol): String = when (protocol) {
        Protocol.VLESS -> "vless"
        Protocol.TROJAN -> "trojan"
        Protocol.HYSTERIA2 -> "hysteria2"
        Protocol.AMNEZIAWG -> "amneziawg"
        Protocol.SHADOWSOCKS -> "shadowsocks"
        Protocol.VMESS -> "vmess"
        Protocol.TUIC -> "tuic"
        Protocol.WARP -> WARP_PROTOCOL
    }

    private fun report(event: String, params: Map<String, Any> = emptyMap()) {
        if (!active) return
        runCatching {
            if (params.isEmpty()) {
                AppMetrica.reportEvent(event)
            } else {
                AppMetrica.reportEvent(event, params)
            }
        }.onFailure {
            // Includes the offline path: AppMetrica persists queued events and
            // retries by itself, so a failure here is only diagnostic.
            Log.w(TAG, "event '$event' was not accepted: ${it.message}")
        }
    }
}
