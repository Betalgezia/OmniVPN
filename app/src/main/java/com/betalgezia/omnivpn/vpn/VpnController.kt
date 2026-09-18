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

    fun prepareIntent(): Intent? = VpnService.prepare(context)

    suspend fun start(node: Node): Result<Unit> = runCatching {
        val config = withContext(Dispatchers.Default) {
            SingBoxConfigBuilder.build(node)
        }
        startConfig(config).getOrThrow()
    }

    suspend fun startConfig(config: String): Result<Unit> = runCatching {
        check(config.isNotBlank()) { "VPN config is empty" }
        withContext(Dispatchers.Main.immediate) {
            check(VpnService.prepare(context) == null) {
                "VPN permission is required"
            }
        }

        withContext(Dispatchers.IO) {
            VpnConfigStore(context).write(config)
        }

        withContext(Dispatchers.Main.immediate) {
            val intent = Intent(context, OmniVpnService::class.java).apply {
                action = OmniVpnService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
    suspend fun startWarp(licenseKey: String? = null, endpoint: String? = null, forceNew: Boolean = false): Result<Unit> = runCatching {
        val storage = WarpStorage(context)
        val client = CloudflareWarpClient()
        var account = if (!forceNew) storage.get() else null

        if (account == null) {
            account = client.register(licenseKey, endpoint)
        } else {
            val license = licenseKey?.trim().takeUnless { it.isNullOrEmpty() }
            if (license != null && !license.equals(account.license, ignoreCase = false)) {
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
        val intent = Intent(context, OmniVpnService::class.java).apply {
            action = OmniVpnService.ACTION_STOP
        }
        context.startService(intent)
    }
}
