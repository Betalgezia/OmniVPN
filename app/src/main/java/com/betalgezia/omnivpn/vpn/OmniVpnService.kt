package com.betalgezia.omnivpn.vpn

import com.betalgezia.omnivpn.OmniVpnApplication

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.SystemProxyStatus
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class OmniVpnService : VpnService(), CommandServerHandler {

    @Inject
    lateinit var eventBus: VpnEventBus

    @Inject
    lateinit var engine: SingBoxEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeTun = AtomicReference<ParcelFileDescriptor?>(null)
    private val stopping = AtomicBoolean(false)
    private val startInProgress = AtomicBoolean(false)
    private val operationGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private lateinit var platformInterface: AndroidPlatformInterface
    private lateinit var configStore: VpnConfigStore
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var recoveryJob: kotlinx.coroutines.Job? = null

    // A start that arrived while another start was still in progress: the
    // newer config is already in configStore, so reload it once that start ends.
    private val pendingReload = AtomicBoolean(false)
    private var trafficJob: kotlinx.coroutines.Job? = null
    private var watchdog: TrafficWatchdog? = null

    // Outbound/endpoint tag the active config routes through (route.final).
    @Volatile
    private var probeTag: String? = null

    // Set per start from EXTRA_QUICK_CHECK (auto-switch): one short probe.
    @Volatile
    private var quickCheckNext = false

    // Shown in the notification ("Подключено · <label>"), from EXTRA_LABEL.
    @Volatile
    private var connectionLabel: String? = null

    override fun onCreate() {
        super.onCreate()

        VpnNotification.createChannel(this)
        configStore = VpnConfigStore(this)
        platformInterface = AndroidPlatformInterface(this) { pfd ->
            replaceTun(pfd)
        }

        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.i(
            TAG,
            "onStartCommand: action=${intent?.action}, flags=${flags}, startId=${startId}, " +
                "engineRunning=${engine.isRunning()}, stopping=${stopping.get()}"
        )
        return when (intent?.action) {
            ACTION_START, null -> {
                intent?.getStringExtra(EXTRA_LABEL)?.let { connectionLabel = it }
                quickCheckNext = intent?.getBooleanExtra(EXTRA_QUICK_CHECK, false) ?: false
                startVpn()
                START_NOT_STICKY
            }

            ACTION_STOP -> {
                stopVpn()
                START_NOT_STICKY
            }

            else -> START_NOT_STICKY
        }
    }

    private fun startVpn() {
        android.util.Log.i(TAG, "startVpn: requested")
        // A start while the tunnel is up is a switch to the config VpnController
        // just wrote (another server, or WARP) - it used to be dropped as a
        // "duplicate start", so picking another server did nothing until the
        // user disconnected by hand.
        if (engine.isRunning()) {
            android.util.Log.i(TAG, "startVpn: engine running - switching via hot reload")
            reloadVpn()
            return
        }
        if (!startInProgress.compareAndSet(false, true)) {
            android.util.Log.i(TAG, "startVpn: start in progress - will reload the newer config after it")
            pendingReload.set(true)
            return
        }
        val generation = operationGeneration.incrementAndGet()

        if (VpnService.prepare(this) != null) {
            startInProgress.set(false)
            eventBus.emit(VpnEvent.Error("VPN permission is required"))
            stopSelf()
            return
        }

        stopping.set(false)

        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    VpnNotification.NOTIFICATION_ID,
                    VpnNotification.build(this, "Подключение…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                startForeground(
                    VpnNotification.NOTIFICATION_ID,
                    VpnNotification.build(this, "Подключение…")
                )
            }
        }.onFailure {
            startInProgress.set(false)
            eventBus.emit(
                VpnEvent.Error(
                    "Unable to start VPN foreground service: " + it.message
                )
            )
            stopSelf()
            return
        }

        serviceScope.launch {
            try {
                if (operationGeneration.get() != generation || stopping.get()) return@launch
                OmniVpnApplication.libboxReady.await()
                android.util.Log.d(TAG, "startVpn: reading active configuration")
                val config = configStore.read()
                    ?: error("No active sing-box configuration")
                android.util.Log.d(TAG, "startVpn: active configuration loaded (${config.length} chars)")
                probeTag = probeTagOf(config)

                android.util.Log.i(TAG, "startVpn: starting SingBoxEngine")
                engine.start(config, platformInterface, this@OmniVpnService) {
                    operationGeneration.get() == generation && !stopping.get()
                }

                if (operationGeneration.get() != generation || stopping.get()) {
                    runCatching { engine.stop(emitDisconnected = false) }
                    closeTun()
                    return@launch
                }

                android.util.Log.i(TAG, "startVpn: SingBoxEngine completed, engineRunning=${engine.isRunning()}")
                publishNotification(connectedText(TrafficStatus.CHECKING))
                startTrafficCheck(generation)
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "startVpn: service startup failed", t)
                if (operationGeneration.get() == generation) {
                    eventBus.emit(
                        VpnEvent.Error(
                            t.message?.takeIf { it.isNotBlank() } ?: "sing-box start failed"
                        )
                    )
                    runCatching { engine.stop(emitDisconnected = false) }
                    closeTun()

                    withContext(Dispatchers.Main) {
                        stopForegroundCompat()
                        stopSelf()
                    }
                } else {
                    runCatching { engine.stop(emitDisconnected = false) }
                    closeTun()
                }
            } finally {
                startInProgress.set(false)
                if (pendingReload.getAndSet(false) && engine.isRunning() && !stopping.get()) {
                    withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) { reloadVpn() }
                }
            }
        }
    }
    private fun stopVpn() {
        if (!stopping.compareAndSet(false, true)) return

        operationGeneration.incrementAndGet()
        recoveryJob?.cancel()
        recoveryJob = null
        trafficJob?.cancel()
        stopWatchdog()
        pendingReload.set(false)

        serviceScope.launch {
            runCatching { engine.stop() }
                .onFailure {
                    eventBus.emit(
                        VpnEvent.Error(
                            it.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "sing-box stop failed"
                        )
                    )
                }

            closeTun()

            withContext(Dispatchers.Main) {
                stopForegroundCompat()
                stopSelf()
            }
        }
    }
    override fun onRevoke() {
        operationGeneration.incrementAndGet()
        stopping.set(true)
        recoveryJob?.cancel()
        recoveryJob = null
        trafficJob?.cancel()
        stopWatchdog()
        pendingReload.set(false)

        // Revoke is a terminal Android lifecycle callback. Do the native
        // engine/TUN teardown synchronously so onDestroy cannot cancel an
        // in-flight cleanup coroutine before the VPN fd is released.
        engine.closeNow()
        closeTun()
        eventBus.emit(VpnEvent.Revoked)

        runCatching { stopForegroundCompat() }
        stopSelf()
        super.onRevoke()
    }
    override fun onDestroy() {
        android.util.Log.i(TAG, "onDestroy called, engineRunning=${engine.isRunning()}, state=${eventBus.state.value}")
        operationGeneration.incrementAndGet()
        stopping.set(true)
        unregisterNetworkCallback()
        recoveryJob?.cancel()
        recoveryJob = null
        trafficJob?.cancel()
        stopWatchdog()
        pendingReload.set(false)
        engine.closeNow()
        closeTun()

        if (eventBus.state.value != VpnState.REVOKED) {
            eventBus.emit(VpnEvent.Disconnected)
        }

        runCatching { stopForegroundCompat() }
        stopSelf()
        serviceScope.cancel()
        android.util.Log.i(TAG, "onDestroy: cleanup complete, state=${eventBus.state.value}")
        super.onDestroy()
    }

    /**
     * Switches a running tunnel to whatever config VpnController just wrote,
     * without tearing the VPN down: sing-box's startOrReloadService closes the
     * old box and starts the new one in the same command server, and the new
     * TUN replaces the old fd through replaceTun(). This is how a tap on
     * another server (or on WARP) moves the connection over instead of being
     * ignored as a "duplicate start". If the hot reload fails, falls back to a
     * full stop + start so the user still ends up on the server they picked.
     */
    private fun reloadVpn() {
        if (stopping.get()) {
            android.util.Log.w(TAG, "reloadVpn: stop in progress, ignoring")
            return
        }
        val generation = operationGeneration.incrementAndGet()
        trafficJob?.cancel()
        stopWatchdog()
        serviceScope.launch {
            try {
                eventBus.emit(VpnEvent.Connecting)
                publishNotification("Переключение…")
                val config = configStore.read() ?: error("No active sing-box configuration")
                probeTag = probeTagOf(config)
                android.util.Log.i(TAG, "reloadVpn: hot-reloading engine (${config.length} chars)")
                engine.reload(config)
                if (operationGeneration.get() != generation || stopping.get()) return@launch
                eventBus.emit(VpnEvent.Connected)
                publishNotification(connectedText(TrafficStatus.CHECKING))
                startTrafficCheck(generation)
                android.util.Log.i(TAG, "reloadVpn: done")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (operationGeneration.get() != generation || stopping.get()) return@launch
                android.util.Log.e(TAG, "reloadVpn: hot reload failed, restarting the engine", t)
                runCatching { engine.stop(emitDisconnected = false) }
                closeTun()
                withContext(Dispatchers.Main) { startVpn() }
            }
        }
    }

    /**
     * Confirms traffic actually flows and reports it on the event bus (app,
     * tile, ConnectionManager's auto-switch) and in the notification. While
     * traffic is OK a [TrafficWatchdog] keeps watching; while it is not, the
     * tunnel is re-checked every [TRAFFIC_RECHECK_DELAY_MS] so a hiccup heals
     * by itself. Everything here is dropped if another start/reload/stop
     * happened meanwhile.
     */
    private fun startTrafficCheck(generation: Long) {
        trafficJob?.cancel()
        stopWatchdog()
        eventBus.setTraffic(TrafficStatus.CHECKING)
        val quick = quickCheckNext
        trafficJob = serviceScope.launch {
            // Auto-switch candidates get one short probe (~5 s for a dead one);
            // a server the user picked gets the patient check (~11 s worst).
            val status = probeTraffic(initial = !quick)
            if (!stillCurrent(generation)) return@launch
            android.util.Log.i(TAG, "traffic check: $status (label=$connectionLabel)")
            reportTraffic(status, generation)
        }
    }

    private suspend fun reportTraffic(status: TrafficStatus, generation: Long) {
        eventBus.setTraffic(status)
        publishNotification(connectedText(status))
        if (status == TrafficStatus.OK) {
            startWatchdog(generation)
            return
        }
        // No watchdog while traffic is down, so nothing would update these.
        eventBus.setRates(null, null)
        // Not OK: keep re-checking quietly. A switch to another server (auto
        // or by hand) bumps the generation and ends this loop.
        while (stillCurrent(generation)) {
            delay(TRAFFIC_RECHECK_DELAY_MS)
            if (!stillCurrent(generation)) return
            val again = probeTraffic(initial = false)
            if (!stillCurrent(generation)) return
            if (again != eventBus.traffic.value) {
                android.util.Log.i(TAG, "traffic re-check: $again (label=$connectionLabel)")
                eventBus.setTraffic(again)
                publishNotification(connectedText(again))
            }
            if (again == TrafficStatus.OK) {
                startWatchdog(generation)
                return
            }
        }
    }

    /**
     * Primary check: through sing-box's own outbound ([CoreProbe]), immune to
     * Android routing. Falls back to [TrafficProbe] (HTTP over the VPN network)
     * only if the core can't test this tag.
     */
    private suspend fun probeTraffic(initial: Boolean): TrafficStatus {
        val tag = probeTag
        if (tag != null) {
            val outcome = if (initial) {
                CoreProbe.check(tag)
            } else {
                CoreProbe.check(tag, retryTimeoutMs = 0)
            }
            when (outcome) {
                is CoreProbe.Outcome.Ok -> {
                    // The home screen's latency figure: refreshed by every
                    // check that passes (connect, re-checks, watchdog).
                    eventBus.setLatency(outcome.latencyMs)
                    return TrafficStatus.OK
                }
                is CoreProbe.Outcome.Dead -> return TrafficStatus.NONE
                is CoreProbe.Outcome.Unsupported ->
                    android.util.Log.w(TAG, "core probe can't test '$tag' (${outcome.reason}) - using HTTP probe")
            }
        }
        return TrafficProbe.check(this).toStatus()
    }

    private fun startWatchdog(generation: Long) {
        stopWatchdog()
        if (!stillCurrent(generation)) return
        watchdog = TrafficWatchdog(
            context = this,
            scope = serviceScope,
            probe = {
                // A stale watchdog must never declare the NEW tunnel dead.
                !stillCurrent(generation) || probeTraffic(initial = false) == TrafficStatus.OK
            },
            onDead = {
                if (stillCurrent(generation)) {
                    android.util.Log.w(TAG, "watchdog: traffic stopped (label=$connectionLabel)")
                    trafficJob?.cancel()
                    trafficJob = serviceScope.launch { reportTraffic(TrafficStatus.NONE, generation) }
                }
            },
            onSlow = { mbps ->
                if (stillCurrent(generation)) eventBus.reportSlow(mbps)
            },
            onRates = { down, up ->
                if (stillCurrent(generation)) eventBus.setRates(down, up)
            }
        ).also { it.start() }
    }

    private fun stopWatchdog() {
        watchdog?.stop()
        watchdog = null
    }

    /** The tag that carries the tunnel's traffic: the config's route.final. */
    private fun probeTagOf(config: String): String? = runCatching {
        org.json.JSONObject(config).optJSONObject("route")?.optString("final")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun stillCurrent(generation: Long): Boolean =
        operationGeneration.get() == generation && !stopping.get() && engine.isRunning()

    private fun TrafficProbe.Result.toStatus(): TrafficStatus = when (this) {
        TrafficProbe.Result.OK -> TrafficStatus.OK
        TrafficProbe.Result.DNS_BROKEN -> TrafficStatus.DNS_BROKEN
        TrafficProbe.Result.NONE -> TrafficStatus.NONE
    }

    private fun connectedText(traffic: TrafficStatus): String {
        val suffix = connectionLabel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
        return when (traffic) {
            TrafficStatus.NONE -> "Нет трафика — сервер не отвечает$suffix"
            TrafficStatus.DNS_BROKEN -> "Сайты не открываются: не работает DNS$suffix"
            TrafficStatus.OK -> "Подключено$suffix"
            else -> "Проверка соединения…$suffix"
        }
    }

    override fun serviceReload() {
        serviceScope.launch {
            runCatching {
                val config = configStore.read()
                    ?: error("No active sing-box configuration")
                engine.reload(config)
                publishNotification("Подключено")
            }.onFailure {
                android.util.Log.e(TAG, "CommandServer serviceReload failed", it)
                eventBus.emit(
                    VpnEvent.Error(
                        it.message?.takeIf(String::isNotBlank)
                            ?: "sing-box reload failed"
                    )
                )
            }
        }
    }

    override fun serviceStop() {
        stopVpn()
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        // System proxy mode is intentionally unsupported by OmniVPN.
    }

    override fun connectSSHAgent(): Int =
        throw UnsupportedOperationException("SSH agent not supported on Android")

    override fun triggerNativeCrash() {
        // Deliberate native-crash hook is intentionally disabled.
    }

    override fun writeDebugMessage(message: String) {
        // NOTE: this CommandServerHandler callback is a low-volume native-debug hook,
        // not sing-box's regular per-connection log stream - it does not fire for
        // ordinary dial/routing/handshake log lines regardless of "log.level" in the
        // generated config, which is why this tag alone never showed anything useful
        // while chasing "connects but no traffic passes". The actual log stream is
        // now handled separately by SingBoxEngine's SingBoxLogClient, which forwards
        // it to logcat under the "SingBoxLog" tag for the life of the VPN session.
        runCatching { android.util.Log.d(TAG, "[libbox] $message") }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun replaceTun(newPfd: ParcelFileDescriptor) {
        activeTun.getAndSet(newPfd)?.runCatching { close() }
    }

    private fun closeTun() {
        activeTun.getAndSet(null)?.runCatching { close() }
    }

    private fun publishNotification(text: String) {
        NotificationManagerCompat.from(this).notify(
            VpnNotification.NOTIFICATION_ID,
            VpnNotification.build(this, text)
        )
    }

    private fun registerNetworkCallback() {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val request = android.net.NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Do NOT call engine.resetNetwork() here. AndroidPlatformInterface's
                // own startDefaultInterfaceMonitor (the libbox-sanctioned channel for
                // auto_detect_interface) already calls notifyDefaultInterface() for
                // every network that becomes available/changes, including this one.
                // Having this second, independent callback also force a full
                // commandServer.resetNetwork() on the same transition raced the two
                // mechanisms against each other - plausibly the source of the
                // DNS "resNetworkResult ETIMEDOUT" bursts and SELinux
                // netlink_route_socket bind denials seen right after reconnects on
                // real devices. Cancelling any pending recovery loop is still
                // correct here: a network is back, so the retry-with-backoff below
                // no longer needs to keep firing.
                recoveryJob?.cancel()
                recoveryJob = null
            }

            override fun onLost(network: Network) {
                scheduleNetworkRecovery()
            }
        }

        runCatching {
            connectivity.registerNetworkCallback(request, callback)
            networkCallback = callback
        }.onFailure {
            android.util.Log.w(TAG, "Network callback registration failed: " + it.message)
        }
    }

    private fun scheduleNetworkRecovery() {
        if (recoveryJob?.isActive == true) return

        recoveryJob = serviceScope.launch {
            var delayMs = 5_000L

            while (isActive && engine.isRunning() && !stopping.get()) {
                delay(delayMs)
                if (!engine.isRunning() || stopping.get()) return@launch

                if (hasValidatedNetwork()) {
                    engine.resetNetwork()
                    return@launch
                }

                engine.resetNetwork()
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun hasValidatedNetwork(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.any { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@any false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        val connectivity = getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val ACTION_START = "com.betalgezia.omnivpn.action.START"
        const val ACTION_STOP = "com.betalgezia.omnivpn.action.STOP"
        const val EXTRA_LABEL = "com.betalgezia.omnivpn.extra.LABEL"
        const val EXTRA_QUICK_CHECK = "com.betalgezia.omnivpn.extra.QUICK_CHECK"
        private const val TRAFFIC_RECHECK_DELAY_MS = 15_000L
        private const val TAG = "OmniVpnService"
    }
}
