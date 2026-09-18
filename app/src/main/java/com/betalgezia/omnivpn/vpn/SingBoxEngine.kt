package com.betalgezia.omnivpn.vpn

import io.nekohasekai.libbox.BoxService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SingBoxEngine @Inject constructor(
    private val eventBus: VpnEventBus
) {

    @Volatile
    private var service: BoxService? = null

    private val lifecycleMutex = Mutex()

    suspend fun start(
        config: String,
        platformInterface: PlatformInterface,
        shouldStart: () -> Boolean = { true }
    ) {
        lifecycleMutex.withLock {
            eventBus.emit(VpnEvent.Connecting)

            var startedNormally = false
            withContext(Dispatchers.IO) {
                OmniVpnApplication.libboxReady.await()
                Libbox.checkConfig(config)
                if (!shouldStart()) return@withContext
                check(service == null) { "sing-box is already running" }

                val created = Libbox.newService(config, platformInterface)
                if (!shouldStart()) {
                    runCatching { created.close() }
                    return@withContext
                }
                service = created
                try {
                    if (!shouldStart()) {
                        service = null
                        runCatching { created.close() }
                        return@withContext
                    }
                    created.start()
                    startedNormally = service === created && shouldStart()
                } catch (t: Throwable) {
                    if (service === created) service = null
                    runCatching { created.close() }
                    throw t
                }
            }

            if (!startedNormally) return@withLock
            eventBus.emit(VpnEvent.Connected)
        }
    }
    suspend fun stop(emitDisconnected: Boolean = true) {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                val current = service ?: return@withContext
                service = null
                current.close()
            }

            if (emitDisconnected) {
                eventBus.emit(VpnEvent.Disconnected)
            }
        }
    }
    fun resetNetwork() {
        val current = service ?: return
        runCatching { current.resetNetwork() }
            .onFailure {
                android.util.Log.w(TAG, "resetNetwork failed: " + it.message)
            }
    }

    fun isRunning(): Boolean = service != null

    fun closeNow() {
        val current = service ?: return
        service = null
        runCatching { current.close() }
            .onFailure {
                android.util.Log.w(TAG, "closeNow failed: " + it.message)
            }
    }

    companion object {
        private const val TAG = "SingBoxEngine"
    }
}
