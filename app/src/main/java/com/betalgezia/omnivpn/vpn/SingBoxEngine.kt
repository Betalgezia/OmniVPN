package com.betalgezia.omnivpn.vpn

import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // Own scope for the headless log-streaming CommandClient (see SingBoxLogClient) -
    // independent of any single start()/stop() call so it can outlive the suspend
    // functions here without being cancelled by their caller's scope.
    private val logClientScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logClient = SingBoxLogClient(logClientScope)

    suspend fun start(
        config: String,
        platformInterface: PlatformInterface,
        handler: CommandServerHandler,
        shouldStart: () -> Boolean = { true }
    ) {
        lifecycleMutex.withLock {
            android.util.Log.i(TAG, "start: begin, configChars=${config.length}")
            eventBus.emit(VpnEvent.Connecting)
            var connected = false
            withContext(Dispatchers.IO) {
                android.util.Log.i(TAG, "start: Libbox.checkConfig() begin")
                Libbox.checkConfig(config)
                android.util.Log.i(TAG, "start: Libbox.checkConfig() passed")
                if (!shouldStart()) {
                    android.util.Log.i(TAG, "start: cancelled after checkConfig")
                    return@withContext
                }
                check(commandServer == null && !serviceStarted) {
                    "sing-box is already running"
                }
                android.util.Log.i(TAG, "start: Libbox.newCommandServer() begin")
                val server = Libbox.newCommandServer(handler, platformInterface)
                commandServer = server
                android.util.Log.i(TAG, "start: Libbox.newCommandServer() returned")
                try {
                    if (!shouldStart()) {
                        android.util.Log.i(TAG, "start: cancelled before server.start()")
                        commandServer = null
                        server.close()
                        return@withContext
                    }
                    android.util.Log.i(TAG, "start: server.start() begin")
                    server.start()
                    android.util.Log.i(TAG, "start: server.start() returned, ready=${server.ready()}")
                    if (!shouldStart()) {
                        android.util.Log.i(TAG, "start: cancelled after server.start()")
                        commandServer = null
                        server.close()
                        return@withContext
                    }
                    android.util.Log.i(TAG, "start: server.startOrReloadService() begin")
                    server.startOrReloadService(config, OverrideOptions())
                    android.util.Log.i(TAG, "start: server.startOrReloadService() returned, ready=${server.ready()}")
                    if (!shouldStart()) {
                        android.util.Log.i(TAG, "start: cancelled after startOrReloadService()")
                        commandServer = null
                        runCatching { server.closeService() }
                        server.close()
                        return@withContext
                    }
                    serviceStarted = true
                    connected = commandServer === server && serviceStarted && shouldStart()
                    android.util.Log.i(TAG, "start: engine running, connected=$connected, ready=${server.ready()}")
                    if (connected) {
                        android.util.Log.i(TAG, "start: starting log stream client")
                        logClient.start()
                    }
                } catch (t: Throwable) {
                    android.util.Log.e(TAG, "start: engine startup failed", t)
                    if (commandServer === server) commandServer = null
                    serviceStarted = false
                    runCatching { server.closeService() }
                    runCatching { server.close() }
                    throw t
                }
            }
            if (connected) {
                android.util.Log.i(TAG, "start: emitting Connected")
                eventBus.emit(VpnEvent.Connected)
            } else {
                android.util.Log.i(TAG, "start: not connected")
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
            android.util.Log.i(TAG, "stop: begin, running=$serviceStarted")
            logClient.stop()
            val current = commandServer
            commandServer = null
            serviceStarted = false
            if (current != null) {
                withContext(Dispatchers.IO) {
                    runCatching { current.closeService() }
                        .onFailure { android.util.Log.w(TAG, "stop: closeService failed", it) }
                    runCatching { current.close() }
                        .onFailure { android.util.Log.w(TAG, "stop: close failed", it) }
                }
            }
            if (emitDisconnected) eventBus.emit(VpnEvent.Disconnected)
            android.util.Log.i(TAG, "stop: completed")
        }
    }
    fun resetNetwork() {
        if (!serviceStarted) return
        commandServer?.resetNetwork()
    }

    fun isRunning(): Boolean = serviceStarted

    fun closeNow() {
        logClient.stop()
        val current = commandServer
        commandServer = null
        serviceStarted = false
        if (current != null) {
            android.util.Log.i(TAG, "closeNow: closing command server and service")
            runCatching { current.closeService() }
                .onFailure { android.util.Log.w(TAG, "closeNow: closeService failed", it) }
            runCatching { current.close() }
                .onFailure { android.util.Log.w(TAG, "closeNow: close failed", it) }
        }
    }
    companion object {
        private const val TAG = "SingBoxEngine"
    }
}
