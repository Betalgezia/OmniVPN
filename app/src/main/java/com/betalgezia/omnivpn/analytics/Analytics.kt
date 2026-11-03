package com.betalgezia.omnivpn.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.betalgezia.omnivpn.data.model.Protocol
import com.posthog.PostHog
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

/**
 * The app's only bridge to PostHog.
 *
 * Base lifecycle events (Application Installed / Opened / Backgrounded) are
 * captured by the SDK itself; this object adds the VPN product events and
 * deliberately sends nothing else: no node names or addresses, subscription
 * URLs, configuration contents, keys, DNS queries or traffic.
 *
 * Every event gets `build_type` through a before-send hook, including the
 * SDK's automatic lifecycle events, so debug and release traffic can be
 * separated in PostHog reports.
 */
object Analytics {
    /** Public Project API key (write-only), not a secret. */
    const val POSTHOG_PROJECT_TOKEN = "phc_np87BFUYxSpvFLu3fhx32jyEYdfSk54pcphc5uuDFEWq"

    /** EU Cloud ingestion host. */
    const val POSTHOG_HOST = "https://eu.i.posthog.com"

    const val WARP_PROTOCOL = "warp"

    private const val TAG = "OmniVpnPostHog"
    private const val KEY_BUILD_TYPE = "build_type"

    // Event names (lowercase snake_case, stable - dashboards are built on them).
    private const val EVENT_CONNECT_ATTEMPT = "vpn_connect_attempt"
    private const val EVENT_CONNECTED = "vpn_connected"
    private const val EVENT_DISCONNECTED = "vpn_disconnected"
    private const val EVENT_WARP_CONNECTED = "warp_connected"

    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_REASON = "reason"

    @Volatile
    private var active = false

    /**
     * Called once from [com.betalgezia.omnivpn.OmniVpnApplication.onCreate],
     * before any screen or VPN code runs. A failure here disables analytics
     * for this process but never crashes the app.
     */
    fun setup(context: Context) {
        if (active) return
        runCatching {
            val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
            val buildType = if (debuggable) "debug" else "release"

            val config = PostHogAndroidConfig(
                projectToken = POSTHOG_PROJECT_TOKEN,
                host = POSTHOG_HOST
            ).apply {
                // Lifecycle autocapture replaces the old manual app_launch.
                captureApplicationLifecycleEvents = true
                // Only the events listed below plus lifecycle; no screen,
                // deep-link, push, interaction, rage/dead-click autocapture.
                captureDeepLinks = false
                captureScreenViews = false
                capturePushNotificationSubscriptions = false
                capturePushNotificationOpened = false
                captureElementInteractions = false
                captureRageClicks = false
                captureDeadClicks = false
                // Explicitly off: replay, flags, surveys and error tracking.
                sessionReplay = false
                preloadFeatureFlags = false
                sendFeatureFlagEvent = false
                surveys = false
                errorTrackingConfig.autoCapture = false
                errorTrackingConfig.captureNativeCrashes = false

                debug = debuggable
                // Debug builds flush quickly so a connected phone can be
                // verified from logcat; release keeps the SDK defaults.
                flushAt = if (debuggable) 1 else 20
                flushIntervalSeconds = if (debuggable) 5 else 30

                addBeforeSend { event ->
                    val properties = event.properties ?: mutableMapOf()
                    properties[KEY_BUILD_TYPE] = buildType
                    if (debuggable) {
                        Log.d(TAG, "event=${event.event} $KEY_BUILD_TYPE=$buildType")
                    }
                    event.copy(properties = properties)
                }
            }

            PostHogAndroid.setup(context.applicationContext, config)
            // Also covers any event that might bypass before-send in a future
            // SDK release; super properties are attached by the SDK itself.
            PostHog.register(KEY_BUILD_TYPE, buildType)
            active = true
            Log.i(TAG, "PostHog setup complete (host=$POSTHOG_HOST, build_type=$buildType)")
        }.onFailure {
            Log.e(TAG, "PostHog setup failed; analytics disabled", it)
        }
    }

    /** A new VPN start was requested, for any protocol. */
    fun connectAttempt(protocol: String) =
        report(EVENT_CONNECT_ATTEMPT, mapOf(KEY_PROTOCOL to protocol))

    /** The tunnel reached CONNECTED. [protocol] is "warp", "vless", … */
    fun connected(protocol: String) {
        report(EVENT_CONNECTED, mapOf(KEY_PROTOCOL to protocol))
        if (protocol == WARP_PROTOCOL) report(EVENT_WARP_CONNECTED)
    }

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

    private fun report(event: String, properties: Map<String, Any> = emptyMap()) {
        if (!active) return
        runCatching {
            PostHog.capture(event = event, properties = properties)
        }.onFailure {
            // PostHog queues events locally and retries; a failure here is
            // diagnostic only and must never affect the VPN.
            Log.w(TAG, "event '$event' was not accepted: ${it.message}")
        }
    }
}
