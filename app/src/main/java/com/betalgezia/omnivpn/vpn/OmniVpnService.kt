package com.betalgezia.omnivpn.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
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
class OmniVpnService : VpnService() {

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
        if (engine.isRunning()) return
        if (!startInProgress.compareAndSet(false, true)) return
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
                val config = configStore.read()
                    ?: error("No active sing-box configuration")

                engine.start(config, platformInterface)

                if (operationGeneration.get() != generation || stopping.get()) {
                    runCatching { engine.stop(emitDisconnected = false) }
                    closeTun()
                    return@launch
                }

                publishNotification("Connected")
            } catch (t: Throwable) {
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
        operationGeneration.incrementAndGet()
        unregisterNetworkCallback()
        recoveryJob?.cancel()
        recoveryJob = null
        engine.closeNow()
        closeTun()
        serviceScope.cancel()

        if (eventBus.state.value != VpnState.REVOKED) {
            eventBus.emit(VpnEvent.Disconnected)
        }

        super.onDestroy()
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
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
