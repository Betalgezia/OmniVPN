package com.betalgezia.omnivpn.vpn

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.betalgezia.omnivpn.MainActivity
import com.betalgezia.omnivpn.R
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that mirrors the VPN state and toggles it in one tap
 * (see the Nocturne v2 handoff §4). Default off-state action is "start WARP";
 * an optional setting switches it to "connect to the fastest server".
 *
 * Dependencies come via a Hilt [EntryPoint] rather than @AndroidEntryPoint so
 * the tile stays a plain TileService with no generated base class - it only
 * needs three singletons.
 */
class OmniVpnTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun vpnController(): VpnController
        fun eventBus(): VpnEventBus
        fun warpSettings(): WarpSettings
    }

    private var scope: CoroutineScope? = null
    private var watchJob: Job? = null

    private val deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)
    }

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        // Keep the tile in sync for as long as it's showing.
        watchJob = s.launch {
            deps.eventBus().state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        watchJob?.cancel()
        watchJob = null
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val controller = deps.vpnController()
        when (deps.eventBus().state.value) {
            VpnState.CONNECTED, VpnState.CONNECTING -> controller.stop()
            else -> startDefaultAction(controller)
        }
    }

    private fun startDefaultAction(controller: VpnController) {
        // Starting the tunnel needs VPN consent the first time. If it's already
        // granted we can start straight from here; otherwise the consent dialog
        // must come from an Activity, so hand off to MainActivity (which runs
        // the existing permission flow and then starts WARP).
        val permissionIntent = controller.prepareIntent()
        if (permissionIntent == null) {
            val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
            s.launch {
                runCatching { controller.startWarp() }
                    .onFailure { android.util.Log.e(TAG, "tile startWarp failed", it) }
            }
        } else {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_START_WARP, true)
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val pi = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pi)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun render(state: VpnState) {
        val tile = qsTile ?: return
        val startsWarp = deps.warpSettings().quickTileStartsWarp
        tile.state = when (state) {
            VpnState.CONNECTED, VpnState.CONNECTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = "OmniVPN"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (state) {
                VpnState.CONNECTING -> "Подключение…"
                VpnState.CONNECTED -> "Подключено"
                VpnState.ERROR -> "Ошибка · нажмите, чтобы повторить"
                else -> if (startsWarp) "Нажмите — включить WARP" else "Нажмите — самый быстрый сервер"
            }
        }
        tile.updateTile()
    }

    companion object {
        private const val TAG = "OmniVpnTileService"
    }
}
