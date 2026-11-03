package com.betalgezia.omnivpn.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.data.model.Node

/** What to start once the system VPN consent dialog comes back with "OK". */
sealed interface PendingStart {
    data object Fastest : PendingStart
    data class Warp(val endpoint: String? = null, val forceNew: Boolean = false) : PendingStart
    data class ToNode(val node: Node) : PendingStart
}

/** Android 13+ shows the VPN's foreground notification only with this permission. */
fun notificationsAllowed(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * The app's ways to start a tunnel, each going through the VPN consent dialog
 * first when Android needs it. One instance per window (see [rememberVpnActions]),
 * shared by every tab, so a start from anywhere behaves the same.
 */
class VpnActions internal constructor(
    private val viewModel: MainViewModel,
    private val askNotifications: (then: () -> Unit) -> Unit,
    private val askPermission: (Intent, PendingStart) -> Unit
) {
    /** Search the subscription servers and connect to the quickest. */
    fun connectFastest() = start(PendingStart.Fastest) { viewModel.connectFastest() }

    /** Connect to [node], or switch a running tunnel over to it. */
    fun connectNode(node: Node) = start(PendingStart.ToNode(node)) { viewModel.connect(node) }

    /**
     * Cloudflare WARP with the saved account and settings; [endpoint] overrides
     * the endpoint for this start, [forceNew] registers a fresh account.
     */
    fun startWarp(endpoint: String? = null, forceNew: Boolean = false) =
        start(PendingStart.Warp(endpoint, forceNew)) { viewModel.startWarp(endpoint, forceNew) }

    fun stop() = viewModel.stop()

    /**
     * Asks for the notification permission the first time it matters (the
     * first start), then runs [then] whatever the answer. Without it Android
     * 13+ hides the VPN's notification from the shade.
     */
    fun withNotificationPermission(then: () -> Unit) = askNotifications(then)

    private fun start(pending: PendingStart, run: () -> Unit) = askNotifications {
        val consent = viewModel.prepareVpn()
        if (consent == null) run() else askPermission(consent, pending)
    }
}

@Composable
fun rememberVpnActions(viewModel: MainViewModel): VpnActions {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<PendingStart?>(null) }
    var afterNotifications by remember { mutableStateOf<(() -> Unit)?>(null) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        val next = afterNotifications
        afterNotifications = null
        next?.invoke()
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val action = pending
        pending = null
        if (result.resultCode != Activity.RESULT_OK) {
            viewModel.showMessage("Без разрешения VPN не запустится")
            return@rememberLauncherForActivityResult
        }
        when (action) {
            PendingStart.Fastest -> viewModel.connectFastest()
            is PendingStart.Warp -> viewModel.startWarp(action.endpoint, action.forceNew)
            is PendingStart.ToNode -> viewModel.connect(action.node)
            null -> Unit
        }
    }
    return remember(viewModel, launcher, notificationLauncher) {
        VpnActions(
            viewModel = viewModel,
            askNotifications = { then ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !notificationsAllowed(context) && !viewModel.notificationsAsked
                ) {
                    viewModel.notificationsAsked = true
                    afterNotifications = then
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    then()
                }
            },
            askPermission = { intent, action ->
                pending = action
                launcher.launch(intent)
            }
        )
    }
}
