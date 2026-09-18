package com.betalgezia.omnivpn.vpn

import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SingBoxEngine @Inject constructor(
    private val eventBus: VpnEventBus
) {

    @Volatile
    private var commandServer: CommandServer? = null

    @Volatile
    private var serviceStarted = false

    private val lifecycleMutex = Mutex()

    suspend fun start(
        config: String,
        platformInterface: PlatformInterface,
        handler: CommandServerHandler,
        shouldStart: () -> Boolean = { true }
    ) {
        lifecycleMutex.withLock {
            eventBus.emit(VpnEvent.Connecting)

            var connected = false

            withContext(Dispatchers.IO) {
                Libbox.checkConfig(config)

                if (!shouldStart()) return@withContext
                check(commandServer == null && !serviceStarted) {
                    "sing-box is already running"
                }

                val server = Libbox.newCommandServer(handler, platformInterface)
                commandServer = server

                try {
                    if (!shouldStart()) {
                        commandServer = null
                        server.close()
                        return@withContext
                    }

                    server.start()

                    if (!shouldStart()) {
                        commandServer = null
                        server.close()
                        return@withContext
                    }

                    server.startOrReloadService(config, OverrideOptions())

                    if (!shouldStart()) {
                        commandServer = null
                        server.close()
                        return@withContext
                    }

                    serviceStarted = true
                    connected = commandServer === server && serviceStarted && shouldStart()
                } catch (t: Throwable) {
                    if (commandServer === server) {
                        commandServer = null
                    }
                    serviceStarted = false
                    runCatching { server.close() }
                    throw t
                }
            }

            if (connected) {
                eventBus.emit(VpnEvent.Connected)
            }
        }
    }

    suspend fun reload(config: String) {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                Libbox.checkConfig(config)
                check(serviceStarted) { "sing-box is not running" }
                val server = commandServer
                    ?: error("sing-box command server is not running")
                server.startOrReloadService(config, OverrideOptions())
            }
        }
    }

    suspend fun stop(emitDisconnected: Boolean = true) {
        lifecycleMutex.withLock {
            val current = commandServer
            commandServer = null
            serviceStarted = false

            if (current != null) {
                withContext(Dispatchers.IO) {
                    current.close()
                }
            }

            if (emitDisconnected) {
                eventBus.emit(VpnEvent.Disconnected)
            }
        }
    }

    fun resetNetwork() {
        if (!serviceStarted) return
        commandServer?.resetNetwork()
    }

    fun isRunning(): Boolean = serviceStarted

    fun closeNow() {
        val current = commandServer
        commandServer = null
        serviceStarted = false
        if (current != null) {
            runCatching { current.close() }
                .onFailure {
                    android.util.Log.w(TAG, "closeNow failed: " + it.message)
                }
        }
    }

    companion object {
        private const val TAG = "SingBoxEngine"
    }
}
