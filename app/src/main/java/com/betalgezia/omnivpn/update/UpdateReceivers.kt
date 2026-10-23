package com.betalgezia.omnivpn.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Dependencies come via a Hilt EntryPoint, as in OmniVpnTileService, so the
// receivers stay plain classes.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UpdaterEntryPoint {
    fun appUpdater(): AppUpdater
}

private fun Context.appUpdater(): AppUpdater =
    EntryPointAccessors.fromApplication(applicationContext, UpdaterEntryPoint::class.java).appUpdater()

/** The system installer's answer to AppUpdater.commitSession: confirm, done or failed. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        android.util.Log.i(
            "UpdateInstallReceiver",
            "status=$status message=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}"
        )
        val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
        context.appUpdater().onInstallStatus(status, confirm)
    }

    companion object {
        const val ACTION_STATUS = "com.betalgezia.omnivpn.UPDATE_INSTALL_STATUS"
    }
}

/**
 * The new version has just replaced the old one. The install killed the old
 * process and the tunnel with it; reconnect what was on (AppUpdater saved it).
 * Starting the VPN service from here is allowed: Android exempts this
 * broadcast from the background-start limits.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val updater = context.appUpdater()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                updater.reconnectAfterUpdate()
            } catch (t: Throwable) {
                android.util.Log.w("PackageReplacedReceiver", "reconnect failed", t)
            } finally {
                pending.finish()
            }
        }
    }
}
