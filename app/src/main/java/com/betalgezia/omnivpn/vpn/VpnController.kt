package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
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
        android.util.Log.d(tag, "prepareVpn: permissionRequired="+D+"{intent != null}")
        return intent
    }

    suspend fun start(node: Node): Result<Unit> = runCatching {
        android.util.Log.i(tag, "start(node): protocol="+D+"{node.protocol}, server="+D+"{node.server}, port="+D+"{node.port}")
        android.util.Log.d(tag, "start(node): currentState="+D+"{eventBus.state.value}")
        val config = withContext(Dispatchers.Default) {
            android.util.Log.d(tag, "start(node): building sing-box config")
            SingBoxConfigBuilder.build(node)
        }
        android.util.Log.d(tag, "start(node): config built ("+D+"{config.length} chars)")
        startConfig(config).getOrThrow()
        android.util.Log.i(tag, "start(node): startConfig completed")
    }.onFailure {
        android.util.Log.e(tag, "start(node): FAILED: "+D+"{it.message}", it)
    }

    suspend fun startConfig(config: String): Result<Unit> = runCatching {
        check(config.isNotBlank()) { "VPN config is empty" }
        android.util.Log.d(tag, "startConfig: checking VPN permission")
        withContext(Dispatchers.Main.immediate) {
            val permissionIntent = VpnService.prepare(context)
            android.util.Log.d(tag, "startConfig: permissionRequired="+D+"{permissionIntent != null}")
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
            android.util.Log.i(tag, "startConfig: calling ContextCompat.startForegroundService()")
            ContextCompat.startForegroundService(context, intent)
            android.util.Log.i(tag, "startConfig: ContextCompat.startForegroundService() returned")
        }
    }.onFailure {
        android.util.Log.e(tag, "startConfig: FAILED: "+D+"{it.message}", it)
    }
    suspend fun startWarp(licenseKey: String? = null, endpoint: String? = null, forceNew: Boolean = false): Result<Unit> = runCatching {
        val storage = WarpStorage(context)
        val client = CloudflareWarpClient()
        var account = if (!forceNew) storage.get() else null

        if (account == null) {
            account = client.register(licenseKey, endpoint)
        } else {
            val license = licenseKey?.trim().takeUnless { it.isNullOrEmpty() }
            if (license != null && (!license.equals(account.license, ignoreCase = false) || !account.warpPlus)) {
                account = client.applyLicense(account, license)
            }
        }

        val normalizedEndpoint = endpoint?.trim().takeUnless { it.isNullOrEmpty() }
        val effectiveEndpoint = normalizedEndpoint ?: account.endpoint
        account = account.copy(endpoint = effectiveEndpoint)
        storage.set(account)

        start(account.toNode()).getOrThrow()
    }
    fun stop() {
        android.util.Log.i(tag, "stop: requesting OmniVpnService stop")
        context.stopService(Intent(context, OmniVpnService::class.java))
    }
}
