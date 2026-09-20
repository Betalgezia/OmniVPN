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
        if (engine.isRunning()) {
            android.util.Log.w(TAG, "startVpn: engine already running, ignoring duplicate start")
            return
        }
        if (!startInProgress.compareAndSet(false, true)) {
            android.util.Log.w(TAG, "startVpn: another start is already in progress")
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
                    VpnNotification.build(this, "Connecting…"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST
                )
            } else {
                startForeground(
                    VpnNotification.NOTIFICATION_ID,
                    VpnNotification.build(this, "Connecting…")
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
                publishNotification("Connected")
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
            }
        }
    }
    private fun stopVpn() {
        if (!stopping.compareAndSet(false, true)) return

        operationGeneration.incrementAndGet()
        recoveryJob?.cancel()
        recoveryJob = null

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

    override fun serviceReload() {
        serviceScope.launch {
            runCatching {
                val config = configStore.read()
                    ?: error("No active sing-box configuration")
                engine.reload(config)
                publishNotification("Connected")
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
                recoveryJob?.cancel()
                recoveryJob = null
                serviceScope.launch {
                    if (!stopping.get()) engine.resetNetwork()
                }
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
        private const val TAG = "OmniVpnService"
    }
}
