package com.betalgezia.omnivpn

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@HiltAndroidApp
class OmniVpnApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        applicationScope.launch {
            runCatching {
                initializeLibbox()
            }.onSuccess {
                libboxReady.complete(Unit)
            }.onFailure {
                libboxReady.completeExceptionally(it)
            }
        }
    }

    private fun initializeLibbox() {
        runCatching {
            Libbox.setLocale(
                Locale.getDefault().toLanguageTag().replace("-", "_")
            )
        }.onFailure {
            android.util.Log.w(TAG, "libbox locale setup failed: " + it.message)
        }

        val baseDir = filesDir.also { it.mkdirs() }
        val tempDir = cacheDir.also { it.mkdirs() }

        Libbox.setup(
            SetupOptions().apply {
                basePath = baseDir.absolutePath
                workingPath = baseDir.absolutePath
                tempPath = tempDir.absolutePath
            }
        )
    }

    override fun onTerminate() {
        applicationScope.cancel()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "OmniVpnApplication"

        @Volatile
        lateinit var instance: OmniVpnApplication
            private set

        val libboxReady: CompletableDeferred<Unit> = CompletableDeferred()
    }
}
