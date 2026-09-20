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
    private val eventBus: VpnEventBus
) {
    val state = eventBus.state
    val events = eventBus.events

    private val tag = "VpnController"

    fun prepareIntent(): Intent? {
        val intent = VpnService.prepare(context)
        android.util.Log.d(tag, "prepareVpn: permissionRequired=${intent != null}")
        return intent
    }

    suspend fun start(node: Node): Result<Unit> = runCatching {
        android.util.Log.i(tag, "start(node): protocol=${node.protocol}, server=${node.server}, port=${node.port}")
        android.util.Log.d(tag, "start(node): currentState=${eventBus.state.value}")
        val config = withContext(Dispatchers.Default) {
            android.util.Log.d(tag, "start(node): building sing-box config")
            SingBoxConfigBuilder.build(node)
        }
        android.util.Log.d(tag, "start(node): config built (${config.length} chars)")
        startConfig(config).getOrThrow()
        android.util.Log.i(tag, "start(node): startConfig completed")
        Unit
    }.onFailure {
        android.util.Log.e(tag, "start(node): FAILED: ${it.message}", it)
    }

    suspend fun startConfig(config: String): Result<Unit> = runCatching {
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
            }
            android.util.Log.i(tag, "startConfig: calling Context.startService() for VpnService")
            context.startService(intent)
            android.util.Log.i(tag, "startConfig: Context.startService() returned")
        }
        Unit
    }.onFailure {
        android.util.Log.e(tag, "startConfig: FAILED: ${it.message}", it)
    }
    suspend fun startWarp(licenseKey: String? = null, endpoint: String? = null, forceNew: Boolean = false): Result<Unit> = runCatching {
        android.util.Log.i(tag, "startWarp: begin forceNew=${forceNew}, endpointOverride=${!endpoint.isNullOrBlank()}, licenseProvided=${!licenseKey.isNullOrBlank()}")
        val storage = WarpStorage(context)
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
        val effectiveEndpoint = normalizedEndpoint ?: account.endpoint
        account = account.copy(endpoint = effectiveEndpoint)
        storage.set(account)
        android.util.Log.d(tag, "startWarp: account cached, starting VPN")

        start(account.toNode()).getOrThrow()
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
