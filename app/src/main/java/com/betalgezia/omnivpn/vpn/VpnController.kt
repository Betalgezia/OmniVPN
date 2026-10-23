package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.betalgezia.omnivpn.data.model.Node
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: VpnEventBus,
    private val dnsSettings: DnsSettings
) {
    val state = eventBus.state
    val events = eventBus.events
    val traffic = eventBus.traffic
    val stats = eventBus.stats
    val lastError = eventBus.lastError

    fun clearError() = eventBus.clearError()

    private val tag = "VpnController"

    fun prepareIntent(): Intent? {
        val intent = VpnService.prepare(context)
        android.util.Log.d(tag, "prepareVpn: permissionRequired=${intent != null}")
        return intent
    }

    /**
     * Starts the tunnel to [node]. If one is already up, the service treats
     * this as a switch and hot-reloads the new config (OmniVpnService.reloadVpn).
     * [label] is what the notification shows next to "Подключено".
     */
    suspend fun start(
        node: Node,
        label: String = node.name,
        quickCheck: Boolean = false,
        alternates: List<Node> = emptyList(),
        // The notification's second line («VLESS · host:port») and whether it
        // offers «Сменить сервер» (not for WARP).
        detail: String = VpnNotification.serverDetail(node),
        switchable: Boolean = true
    ): Result<Unit> = runCatching {
        android.util.Log.i(tag, "start(node): protocol=${node.protocol}, server=${node.server}, port=${node.port}")
        android.util.Log.d(tag, "start(node): currentState=${eventBus.state.value}")
        val dns = dnsSettings.endpoint()
        android.util.Log.d(tag, "start(node): dns=${dns?.text ?: "default"}")
        val config = withContext(Dispatchers.Default) {
            android.util.Log.d(tag, "start(node): building sing-box config (${alternates.size} alternates)")
            val preferred = SingBoxConfigBuilder.build(node, alternates, dns)
            // Nothing optional is in play: the config is just the node.
            if (alternates.isEmpty() && dns == null) return@withContext preferred

            // A user-chosen DNS transport or an alternate outbound can both be
            // rejected by the core (a bad DoH URL, a protocol field the
            // endpoint can't carry, ...). Each is a convenience: a rejected
            // variant must never cost the user the connection itself, so try
            // the cheaper subsets and fall all the way back to the plain config.
            val candidates = listOfNotNull(
                preferred,
                if (alternates.isNotEmpty()) SingBoxConfigBuilder.build(node, emptyList(), dns) else null,
                if (dns != null) SingBoxConfigBuilder.build(node, alternates, null) else null,
                if (dns != null && alternates.isNotEmpty()) SingBoxConfigBuilder.build(node, emptyList(), null) else null
            ).distinct()
            if (candidates.size == 1) return@withContext candidates.first()

            val ready = runCatching {
                com.betalgezia.omnivpn.OmniVpnApplication.libboxReady.await()
            }
            if (ready.isFailure) return@withContext preferred
            for (candidate in candidates) {
                val ok = runCatching { io.nekohasekai.libbox.Libbox.checkConfig(candidate) }
                if (ok.isSuccess) return@withContext candidate
                android.util.Log.w(tag, "start(node): config variant rejected (${ok.exceptionOrNull()?.message})")
            }
            android.util.Log.w(tag, "start(node): every variant rejected - starting without alternates and the custom DNS")
            SingBoxConfigBuilder.build(node)
        }
        android.util.Log.d(tag, "start(node): config built (${config.length} chars)")
        android.util.Log.d(tag, "start(node): full config=$config")
        startConfig(config, label, quickCheck, detail, switchable).getOrThrow()
        android.util.Log.i(tag, "start(node): startConfig completed")
        Unit
    }.onFailure {
        android.util.Log.e(tag, "start(node): FAILED: ${it.message}", it)
    }

    /**
     * [quickCheck]: verify the new tunnel with a single short probe instead of
     * probe + patient retry - used by auto-switch, where a dead candidate
     * should cost ~5 s, not ~11 s.
     */
    suspend fun startConfig(
        config: String,
        label: String? = null,
        quickCheck: Boolean = false,
        detail: String? = null,
        switchable: Boolean = true
    ): Result<Unit> = runCatching {
        check(config.isNotBlank()) { "VPN config is empty" }
        android.util.Log.d(tag, "startConfig: checking VPN permission")
        withContext(Dispatchers.Main.immediate) {
            val permissionIntent = VpnService.prepare(context)
            android.util.Log.d(tag, "startConfig: permissionRequired=${permissionIntent != null}")
            check(permissionIntent == null) {
                "VPN permission is required"
            }
        }

        android.util.Log.d(tag, "startConfig: writing encrypted active config")
        withContext(Dispatchers.IO) {
            VpnConfigStore(context).write(config)
        }
        android.util.Log.d(tag, "startConfig: active config written")

        withContext(Dispatchers.Main.immediate) {
            val intent = Intent(context, OmniVpnService::class.java).apply {
                action = OmniVpnService.ACTION_START
                if (label != null) putExtra(OmniVpnService.EXTRA_LABEL, label)
                if (detail != null) putExtra(OmniVpnService.EXTRA_DETAIL, detail)
                putExtra(OmniVpnService.EXTRA_SWITCHABLE, switchable)
                putExtra(OmniVpnService.EXTRA_QUICK_CHECK, quickCheck)
            }
            android.util.Log.i(tag, "startConfig: calling Context.startService() for VpnService")
            try {
                context.startService(intent)
            } catch (backgroundStart: IllegalStateException) {
                // From the background (reconnecting right after an app update,
                // see update/PackageReplacedReceiver) Android 8+ refuses a
                // plain start; a foreground start is allowed there, and the
                // service goes foreground straight away in startVpn().
                android.util.Log.w(tag, "startConfig: background start refused - starting as foreground service")
                androidx.core.content.ContextCompat.startForegroundService(context, intent)
            }
            android.util.Log.i(tag, "startConfig: Context.startService() returned")
        }
        Unit
    }.onFailure {
        android.util.Log.e(tag, "startConfig: FAILED: ${it.message}", it)
    }
    suspend fun startWarp(licenseKey: String? = null, endpoint: String? = null, forceNew: Boolean = false): Result<Unit> = runCatching {
        android.util.Log.i(tag, "startWarp: begin forceNew=${forceNew}, endpointOverride=${!endpoint.isNullOrBlank()}, licenseProvided=${!licenseKey.isNullOrBlank()}")
        val storage = WarpStorage(context)
        val settings = WarpSettings(context)
        // Per-network tuned junk/decoy profile if the user ran the autotuner
        // on this network, else the built-in default (see WarpObfuscationStore
        // / WarpObfuscationTuner).
        val obfuscation = WarpObfuscationStore(context).currentOrDefault()
        val client = CloudflareWarpClient()
        var account = if (!forceNew) storage.get() else null
        android.util.Log.d(tag, "startWarp: cachedAccount=${account != null}")

        if (account == null) {
            android.util.Log.i(tag, "startWarp: registering WARP account")
            account = client.register(licenseKey, endpoint)
            android.util.Log.i(tag, "startWarp: WARP registration succeeded")
        } else {
            val license = licenseKey?.trim().takeUnless { it.isNullOrEmpty() }
            if (license != null && (!license.equals(account.license, ignoreCase = false) || !account.warpPlus)) {
                android.util.Log.i(tag, "startWarp: applying WARP+ license")
                account = client.applyLicense(account, license)
                android.util.Log.i(tag, "startWarp: WARP+ license applied")
            }
        }

        val normalizedEndpoint = endpoint?.trim().takeUnless { it.isNullOrEmpty() }
        // Self-heal accounts cached before DEFAULT_ENDPOINT switched from a
        // domain name to a literal IP (see WarpAccount.DEFAULT_ENDPOINT) - a
        // domain-name peer hits a confirmed sing-box-lx bug where the UDP
        // socket gets recreated every handshake retry and the handshake
        // never completes. Never overrides an endpoint the caller explicitly
        // asked for, even if it's a hostname.
        val effectiveEndpoint = normalizedEndpoint
            ?: obfuscation.endpoint?.takeIf { WarpAccount.isLiteralIpEndpoint(it) }
            ?: account.endpoint.takeIf { WarpAccount.isLiteralIpEndpoint(it) }
            ?: WarpAccount.DEFAULT_ENDPOINT
        account = account.copy(endpoint = effectiveEndpoint)
        storage.set(account)
        android.util.Log.d(tag, "startWarp: account cached, starting VPN")

        android.util.Log.d(tag, "startWarp: ipv6Enabled=${settings.ipv6Enabled}, obfuscation jc=${obfuscation.jc} jmin=${obfuscation.jmin} jmax=${obfuscation.jmax} tuned=${obfuscation.tunedLatencyMs != null}")
        start(
            account.toNode(includeIpv6 = settings.ipv6Enabled, obfuscation = obfuscation),
            label = "Cloudflare WARP",
            detail = account.endpoint,
            switchable = false
        ).getOrThrow()
        android.util.Log.i(tag, "startWarp: VPN start requested successfully")
        Unit
    }.onFailure {
        android.util.Log.e(tag, "startWarp: FAILED: ${it.message}", it)
    }
    fun stop() {
        android.util.Log.i(tag, "stop: requesting OmniVpnService ACTION_STOP")
        val intent = Intent(context, OmniVpnService::class.java).apply {
            action = OmniVpnService.ACTION_STOP
        }
        runCatching {
            context.startService(intent)
        }.onFailure {
            android.util.Log.w(tag, "stop: ACTION_STOP delivery failed; falling back to stopService", it)
            context.stopService(intent)
        }
    }
}
