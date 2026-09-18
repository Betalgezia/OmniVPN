package com.betalgezia.omnivpn.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import com.betalgezia.omnivpn.data.model.Node
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: VpnEventBus
) {
    val state = eventBus.state
    val events = eventBus.events

    fun prepareIntent(): Intent? = VpnService.prepare(context)

    fun start(node: Node): Result<Unit> =
        startConfig(SingBoxConfigBuilder.build(node))

    fun startConfig(config: String): Result<Unit> {
        return runCatching {
            check(config.isNotBlank()) { "VPN config is empty" }
            check(VpnService.prepare(context) == null) {
                "VPN permission is required"
            }

            VpnConfigStore(context).write(config)

            val intent = Intent(context, OmniVpnService::class.java).apply {
                action = OmniVpnService.ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    fun stop() {
        val intent = Intent(context, OmniVpnService::class.java).apply {
            action = OmniVpnService.ACTION_STOP
        }
        context.startService(intent)
    }
}
