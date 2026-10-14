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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that mirrors the VPN state and toggles it in one tap.
 * The "turn on" action is chosen in Settings: WARP (default) or the fastest
 * subscription server - see [WarpSettings.quickTileStartsWarp] and
 * [ConnectionManager.startTileAction].
 *
 * The tile itself only renders and dispatches. The actual work runs in
 * ConnectionManager's app-wide scope: the tile's own scope is cancelled in
 * onStopListening, i.e. as soon as the shade closes, which used to be able to
 * kill a start half-way through.
 *
 * Dependencies come via a Hilt [EntryPoint] rather than @AndroidEntryPoint so
 * the tile stays a plain TileService with no generated base class.
 */
class OmniVpnTileService : TileService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TileEntryPoint {
        fun vpnController(): VpnController
        fun eventBus(): VpnEventBus
        fun warpSettings(): WarpSettings
        fun connectionManager(): ConnectionManager
    }

    private var scope: CoroutineScope? = null

    private val deps by lazy {
        EntryPointAccessors.fromApplication(applicationContext, TileEntryPoint::class.java)
    }

    private data class TileModel(
        val state: VpnState,
        val traffic: TrafficStatus,
        val active: ActiveConnection,
        val searching: Boolean,
        val notice: String?
    )

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        val manager = deps.connectionManager()
        val bus = deps.eventBus()
        s.launch {
            combine(
                bus.state,
                bus.traffic,
                manager.activeConnection,
                manager.tileSearching,
                manager.tileNotice
            ) { state, traffic, active, searching, notice ->
                TileModel(state, traffic, active, searching, notice)
            }.collect { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val manager = deps.connectionManager()
        when {
            manager.tileSearching.value -> manager.cancelTileAction()
            deps.eventBus().state.value == VpnState.CONNECTED ||
                deps.eventBus().state.value == VpnState.CONNECTING -> manager.stop()
            else -> turnOn(manager)
        }
    }

    private fun turnOn(manager: ConnectionManager) {
        // Starting the tunnel needs VPN consent the first time. If it's already
        // granted we start straight from here; otherwise the consent dialog
        // must come from an Activity, so hand off to MainActivity, which runs
        // its permission flow and then the same action.
        if (deps.vpnController().prepareIntent() == null) {
            manager.startTileAction()
            return
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(MainActivity.EXTRA_TILE_START, true)
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

    private fun render(model: TileModel) {
        val tile = qsTile ?: return
        val startsWarp = deps.warpSettings().quickTileStartsWarp
        val live = model.state == VpnState.CONNECTED || model.state == VpnState.CONNECTING
        tile.state = if (live || model.searching) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "OmniVPN"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val name = when (val active = model.active) {
                ActiveConnection.Warp -> "WARP"
                is ActiveConnection.Server -> active.node.name
                ActiveConnection.None -> null
            }
            tile.subtitle = when {
                model.searching -> "Поиск сервера…"
                model.state == VpnState.CONNECTING -> "Подключение…"
                model.state == VpnState.CONNECTED -> when (model.traffic) {
                    TrafficStatus.NONE -> "Нет трафика" + (name?.let { " · $it" } ?: "")
                    TrafficStatus.DNS_BROKEN -> "Нет DNS" + (name?.let { " · $it" } ?: "")
                    TrafficStatus.OK -> name ?: "Подключено"
                    else -> "Проверка…" + (name?.let { " · $it" } ?: "")
                }
                model.notice != null -> model.notice
                model.state == VpnState.ERROR -> "Ошибка · нажмите ещё раз"
                else -> if (startsWarp) "Нажмите — WARP" else "Нажмите — быстрый сервер"
            }
        }
        tile.updateTile()
    }
}
