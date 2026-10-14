package com.betalgezia.omnivpn

import dagger.hilt.android.AndroidEntryPoint
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.ui.theme.InterFontFamily
import com.betalgezia.omnivpn.ui.theme.Nocturne
import com.betalgezia.omnivpn.ui.theme.OmniVpnTheme
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.AwgObfuscation
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.TestMode
import com.betalgezia.omnivpn.vpn.TrafficStatus
import com.betalgezia.omnivpn.vpn.TuneState
import com.betalgezia.omnivpn.vpn.VpnState
import com.betalgezia.omnivpn.vpn.WarpSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OmniVPN's single screen, redesigned to the Nocturne v2 spec (September 2026
 * handoff). One scrolling column over an aurora background: header, hero power
 * button, the WARP card, the subscription-URL field, and the servers grouped
 * by subscription. Everything else (import, WARP tools, obfuscation tuner,
 * subscription management, auto-update, imported configs) lives in the
 * settings sheet. The system Quick Settings tile (OmniVpnTileService) hands
 * its "turn on" action back here via [EXTRA_TILE_START] when it needs the VPN
 * consent dialog, which only an Activity can show.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // Set when the QS tile bounced its "turn on" here because VPN consent was
    // needed; OmniVpnScreen watches it and runs the same permission flow plus
    // the tile's action (WARP or the fastest server, per Settings).
    private val tileStart = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra(EXTRA_TILE_START, false) == true) tileStart.value = true
        setContent { OmniVpnTheme { OmniVpnScreen(tileStart = tileStart) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TILE_START, false)) tileStart.value = true
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_TILE_START = "com.betalgezia.omnivpn.TILE_START"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun OmniVpnScreen(
        tileStart: androidx.compose.runtime.MutableState<Boolean>,
        viewModel: MainViewModel = hiltViewModel()
    ) {
        val trafficStatus by viewModel.trafficStatus.collectAsStateWithLifecycle()
        val nodes by viewModel.nodes.collectAsStateWithLifecycle()
        val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
        val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
        val busy by viewModel.busy.collectAsStateWithLifecycle()
        val message by viewModel.message.collectAsStateWithLifecycle()
        val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
        val searchingFastest by viewModel.searchingFastest.collectAsStateWithLifecycle()
        val testingAllFull by viewModel.testingAllFull.collectAsStateWithLifecycle()
        val nodeHealth by viewModel.nodeHealth.collectAsStateWithLifecycle()
        val refreshingSubscriptions by viewModel.refreshingSubscriptions.collectAsStateWithLifecycle()
        val connectedSince by viewModel.connectedSince.collectAsStateWithLifecycle()
        val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
        val autoUpdateInterval by viewModel.autoUpdateIntervalMinutes.collectAsStateWithLifecycle()
        val collapsedSubscriptions by viewModel.collapsedSubscriptions.collectAsStateWithLifecycle()
        val subscriptionTest by viewModel.subscriptionTest.collectAsStateWithLifecycle()
        val canStartVpn = vpnState == VpnState.DISCONNECTED ||
            vpnState == VpnState.ERROR ||
            vpnState == VpnState.REVOKED
        val subscriptionNodes = nodes.filter { it.sourceId != null }

        var subscriptionUrlInput by remember { mutableStateOf("") }
        var input by remember { mutableStateOf("") }
        var warpEndpointInput by remember { mutableStateOf("") }
        var confirmResetWarp by remember { mutableStateOf(false) }
        var pendingDeleteNode by remember { mutableStateOf<Node?>(null) }
        var sheetVisible by remember { mutableStateOf(false) }

        var pendingNode by remember { mutableStateOf<Node?>(null) }
        var pendingWarp by remember { mutableStateOf(false) }
        var pendingWarpForceNew by remember { mutableStateOf(false) }
        var pendingFastest by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val node = pendingNode
            val warp = pendingWarp
            val warpForceNew = pendingWarpForceNew
            val fastest = pendingFastest
            pendingNode = null
            pendingWarp = false
            pendingWarpForceNew = false
            pendingFastest = false
            try {
                if (result.resultCode != RESULT_OK) {
                    viewModel.showMessage("VPN permission denied")
                    return@rememberLauncherForActivityResult
                }
                when {
                    node != null -> viewModel.connect(node)
                    warp -> viewModel.startWarp(warpEndpointInput, forceNew = warpForceNew)
                    fastest -> viewModel.connectFastest()
                    else -> viewModel.showMessage("VPN permission returned without a pending action")
                }
            } catch (t: Throwable) {
                viewModel.showMessage(t.message ?: "VPN permission callback failed")
            }
        }

        val fileLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode != RESULT_OK) return@rememberLauncherForActivityResult
            val data = result.data ?: return@rememberLauncherForActivityResult
            val uri = data.data ?: return@rememberLauncherForActivityResult
            readImportedFile(uri) { importResult ->
                importResult.fold(
                    onSuccess = viewModel::import,
                    onFailure = { viewModel.showMessage(it.message ?: "Unable to read import file") }
                )
            }
        }

        // While a tunnel is up this switches to [node] in place (see
        // MainViewModel.connect); tapping the server we're already on is a no-op.
        fun connectToNode(node: Node, afterLaunch: () -> Unit = {}) {
            val current = activeConnection as? ActiveConnection.Server
            if (current?.node?.id == node.id &&
                (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING)
            ) {
                afterLaunch()
                return
            }
            val intent = viewModel.prepareVpn()
            if (intent != null) {
                pendingNode = node
                permissionLauncher.launch(intent)
            } else {
                viewModel.connect(node)
            }
            afterLaunch()
        }

        val connectFastestFlow: () -> Unit = {
            val intent = viewModel.prepareVpn()
            if (intent != null) {
                pendingFastest = true
                permissionLauncher.launch(intent)
            } else {
                viewModel.connectFastest()
            }
        }
        val onHeroClick: () -> Unit = {
            when {
                vpnState == VpnState.CONNECTED -> viewModel.stop()
                searchingFastest -> viewModel.cancelTests()
                vpnState == VpnState.CONNECTING -> viewModel.stop()
                else -> connectFastestFlow()
            }
        }

        val isWarpActiveOrConnecting = activeConnection is ActiveConnection.Warp &&
            (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING)
        val startWarpFlow: () -> Unit = {
            val intent = viewModel.prepareVpn()
            if (intent != null) {
                pendingWarp = true
                pendingWarpForceNew = false
                permissionLauncher.launch(intent)
            } else {
                viewModel.startWarp()
            }
        }
        val onWarpClick: () -> Unit = {
            if (isWarpActiveOrConnecting) viewModel.stop() else startWarpFlow()
        }

        // The QS tile handed us its "turn on" because VPN consent was needed:
        // run the same permission flow, then the tile's action from Settings.
        LaunchedEffect(tileStart.value) {
            if (tileStart.value) {
                tileStart.value = false
                if (canStartVpn) {
                    if (viewModel.quickTileStartsWarp.value) startWarpFlow() else connectFastestFlow()
                }
            }
        }

        val clipboardManager = LocalClipboardManager.current
        val hero = heroVisualsFor(vpnState, searchingFastest, activeConnection, trafficStatus)

        Box(modifier = Modifier.fillMaxSize().background(Nocturne.bg)) {
            AuroraBackground(hero)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 17.dp)
            ) {
                // Header: mark + name, hamburger opens settings.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MarkGlyph(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "OmniVPN",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Nocturne.text,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable(onClick = { sheetVisible = true }),
                        contentAlignment = Alignment.Center
                    ) {
                        HamburgerGlyph(color = Nocturne.text, modifier = Modifier.size(22.dp))
                    }
                }

                HeroBlock(
                    hero = hero,
                    connectedSince = connectedSince,
                    onClick = onHeroClick
                )

                Spacer(Modifier.height(4.dp))

                WarpCard(
                    vpnState = vpnState,
                    isWarp = activeConnection is ActiveConnection.Warp,
                    connectedSince = connectedSince,
                    enabled = !busy,
                    onClick = onWarpClick
                )

                Spacer(Modifier.height(11.dp))

                SubscriptionUrlField(
                    value = subscriptionUrlInput,
                    onValueChange = { subscriptionUrlInput = it },
                    enabled = !busy,
                    onAdd = {
                        viewModel.import(subscriptionUrlInput)
                        subscriptionUrlInput = ""
                    },
                    onPaste = {
                        clipboardManager.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            subscriptionUrlInput = it
                        }
                    }
                )

                ServerListHeader(
                    count = subscriptionNodes.size,
                    refreshingAny = subscriptions.any { refreshingSubscriptions.contains(it.id) },
                    testingAll = testingAllFull,
                    refreshEnabled = subscriptions.isNotEmpty(),
                    testEnabled = !busy && canStartVpn && subscriptionNodes.isNotEmpty(),
                    onRefreshAll = { viewModel.refreshAll() },
                    onTestAll = { viewModel.testAllSubscriptionsFull() }
                )

                if (subscriptionNodes.isEmpty()) {
                    EmptyServersState()
                } else {
                    SubscriptionGroups(
                        subscriptions = subscriptions,
                        nodes = subscriptionNodes,
                        nodeHealth = nodeHealth,
                        refreshing = refreshingSubscriptions,
                        autoUpdateEnabled = autoUpdateEnabled,
                        autoUpdateIntervalMinutes = autoUpdateInterval,
                        activeConnection = activeConnection,
                        vpnState = vpnState,
                        // Also while connected: a tap switches to that server.
                        connectable = !busy && (canStartVpn || vpnState == VpnState.CONNECTED),
                        collapsedSubscriptions = collapsedSubscriptions,
                        subscriptionTest = subscriptionTest,
                        testEnabled = !busy && canStartVpn,
                        onConnect = { connectToNode(it) },
                        onTest = { viewModel.checkNode(it) },
                        onRefresh = { viewModel.refresh(it) },
                        onDeleteRequest = { pendingDeleteNode = it },
                        onToggleCollapsed = { viewModel.toggleSubscriptionCollapsed(it) },
                        onTestSubscription = { subscription, mode -> viewModel.testSubscription(subscription, mode) }
                    )
                }

                Spacer(Modifier.height(28.dp))
            }

            // Snackbar-style message overlay.
            SnackbarOverlay(
                message = message,
                onShown = { viewModel.consumeMessage() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (sheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { sheetVisible = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color.Transparent,
                dragHandle = null
            ) {
                SettingsSheet(
                    viewModel = viewModel,
                    input = input,
                    onInputChange = { input = it },
                    onImport = { viewModel.import(input); input = "" },
                    onPickFile = {
                        fileLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                type = "*/*"
                                addCategory(Intent.CATEGORY_OPENABLE)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    },
                    warpEndpointInput = warpEndpointInput,
                    onWarpEndpointChange = { warpEndpointInput = it },
                    onWarpWithOverride = {
                        val intent = viewModel.prepareVpn()
                        if (intent != null) {
                            pendingWarp = true
                            pendingWarpForceNew = false
                            permissionLauncher.launch(intent)
                        } else {
                            viewModel.startWarp(warpEndpointInput)
                        }
                        sheetVisible = false
                    },
                    onFindWarpEndpoint = { viewModel.findWarpEndpoint() },
                    onResetWarpRequested = { confirmResetWarp = true },
                    onConnectNode = { node -> connectToNode(node) { sheetVisible = false } },
                    onClose = { sheetVisible = false }
                )
            }
        }

        if (confirmResetWarp) {
            OmniDialog(
                title = "Сбросить аккаунт WARP?",
                body = "Сохранённый аккаунт WARP будет удалён, и в Cloudflare зарегистрируется новый. " +
                    "Обычное нажатие «Cloudflare WARP» всегда использует сохранённый аккаунт — " +
                    "сбрасывайте, только если WARP перестал подключаться.",
                confirmLabel = "Сбросить",
                onConfirm = {
                    confirmResetWarp = false
                    val intent = viewModel.prepareVpn()
                    if (intent != null) {
                        pendingWarp = true
                        pendingWarpForceNew = true
                        permissionLauncher.launch(intent)
                    } else {
                        viewModel.startWarp(warpEndpointInput, forceNew = true)
                    }
                },
                onDismiss = { confirmResetWarp = false }
            )
        }

        pendingDeleteNode?.let { node ->
            val subName = subscriptions.firstOrNull { it.id == node.sourceId }?.name ?: ""
            OmniDialog(
                title = "Удалить сервер?",
                body = "«${node.name}» будет удалён из подписки «$subName». " +
                    "При следующем обновлении подписки он может появиться снова.",
                confirmLabel = "Удалить",
                onConfirm = {
                    pendingDeleteNode = null
                    if (activeConnection is ActiveConnection.Server &&
                        (activeConnection as ActiveConnection.Server).node.id == node.id
                    ) {
                        viewModel.stop()
                    }
                    viewModel.deleteNode(node)
                },
                onDismiss = { pendingDeleteNode = null }
            )
        }
    }
}


// ============================ Hero =====================================

private data class HeroVisuals(
    val title: String,
    val subtitle: String?,
    val accent: Color,
    val titleColor: Color,
    val ringColors: List<Color>,
    val ringAlpha: Float,
    val glowColor: Color,
    val glowAlpha: Float,
    val aurora1: Color,
    val aurora2: Color,
    val busy: Boolean,
    val connectedSpin: Boolean,
    val showMeta: Boolean,
    val metaColor: Color,
    val protoLabel: String?
)

@androidx.compose.runtime.Composable
private fun heroVisualsFor(
    vpnState: VpnState,
    searching: Boolean,
    active: ActiveConnection,
    traffic: TrafficStatus
): HeroVisuals {
    // Post-connect probe result (see TrafficProbe): "Подключено" alone used to
    // be shown even when not a byte got through.
    val connectedTitle = when (traffic) {
        TrafficStatus.NONE -> "Нет трафика"
        TrafficStatus.DNS_BROKEN -> "Нет DNS"
        TrafficStatus.OK -> "Подключено"
        else -> "Проверка…"
    }
    val isWarp = active is ActiveConnection.Warp
    val activeName = when (active) {
        is ActiveConnection.Server -> active.node.name
        ActiveConnection.Warp -> "Cloudflare WARP"
        ActiveConnection.None -> null
    }
    val trafficProblem = when (traffic) {
        TrafficStatus.NONE -> (activeName ?: "Сервер") + " не отвечает · выберите другой"
        TrafficStatus.DNS_BROKEN -> "Сайты не открываются: не работает DNS"
        else -> null
    }
    val protoLabel = when (active) {
        is ActiveConnection.Server -> protocolLabel(active.node.protocol)
        ActiveConnection.Warp -> "WARP"
        ActiveConnection.None -> null
    }
    val live = vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING
    val metaColor = when (active) {
        is ActiveConnection.Server -> protocolColor(active.node.protocol)
        ActiveConnection.Warp -> Nocturne.warp
        ActiveConnection.None -> Nocturne.accent400
    }
    return when {
        searching -> HeroVisuals(
            "Поиск…", "Проверяем серверы подписок",
            accent = Nocturne.accent400, titleColor = Nocturne.accent400,
            ringColors = listOf(Nocturne.accent800, Nocturne.vless, Nocturne.accent400, Nocturne.accent800),
            ringAlpha = 1f, glowColor = Nocturne.accent400, glowAlpha = 0.28f,
            aurora1 = Nocturne.vless, aurora2 = Nocturne.accent800,
            busy = true, connectedSpin = false,
            showMeta = false, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.CONNECTING && isWarp -> HeroVisuals(
            "Подключение…", "Cloudflare WARP",
            accent = Nocturne.warp, titleColor = Nocturne.warp,
            ringColors = listOf(Color(0xFF5A3A22), Nocturne.warp, Nocturne.warpAmber, Color(0xFF5A3A22)),
            ringAlpha = 1f, glowColor = Nocturne.warp, glowAlpha = 0.28f,
            aurora1 = Nocturne.warp, aurora2 = Nocturne.warpAmber,
            busy = true, connectedSpin = false,
            showMeta = live, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.CONNECTING -> HeroVisuals(
            "Подключение…", activeName,
            accent = Nocturne.accent400, titleColor = Nocturne.accent400,
            ringColors = listOf(Nocturne.accent800, Nocturne.vless, Nocturne.accent400, Nocturne.accent800),
            ringAlpha = 1f, glowColor = Nocturne.accent400, glowAlpha = 0.28f,
            aurora1 = Nocturne.vless, aurora2 = Nocturne.accent800,
            busy = true, connectedSpin = false,
            showMeta = live, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.CONNECTED && trafficProblem != null -> HeroVisuals(
            connectedTitle, trafficProblem,
            accent = Nocturne.warning, titleColor = Nocturne.warning,
            ringColors = listOf(Nocturne.warning, Nocturne.warpPink, Nocturne.warning),
            ringAlpha = 1f, glowColor = Nocturne.warning, glowAlpha = 0.28f,
            aurora1 = Nocturne.warning, aurora2 = Nocturne.warpPink,
            busy = false, connectedSpin = false,
            showMeta = true, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.CONNECTED && isWarp -> HeroVisuals(
            connectedTitle, "Cloudflare WARP",
            accent = Nocturne.warp, titleColor = Nocturne.warp,
            ringColors = listOf(Nocturne.warp, Nocturne.warpAmber, Nocturne.warpPink, Nocturne.warp),
            ringAlpha = 1f, glowColor = Nocturne.warp, glowAlpha = 0.45f,
            aurora1 = Nocturne.warp, aurora2 = Nocturne.warpAmber,
            busy = false, connectedSpin = true,
            showMeta = true, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.CONNECTED -> HeroVisuals(
            connectedTitle, activeName ?: "Защищённое соединение",
            accent = Nocturne.connected, titleColor = Nocturne.connected,
            ringColors = listOf(Nocturne.connected, Nocturne.connectedSoft, Nocturne.vless, Nocturne.connected),
            ringAlpha = 1f, glowColor = Nocturne.connected, glowAlpha = 0.45f,
            aurora1 = Nocturne.connected, aurora2 = Nocturne.vless,
            busy = false, connectedSpin = true,
            showMeta = true, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.ERROR -> HeroVisuals(
            "Ошибка подключения", "Нажмите, чтобы повторить",
            accent = Nocturne.error, titleColor = Nocturne.error,
            ringColors = listOf(Nocturne.error, Nocturne.warpPink, Nocturne.error),
            ringAlpha = 1f, glowColor = Nocturne.error, glowAlpha = 0.28f,
            aurora1 = Nocturne.error, aurora2 = Nocturne.warpPink,
            busy = false, connectedSpin = false,
            showMeta = false, metaColor = metaColor, protoLabel = protoLabel
        )
        vpnState == VpnState.REVOKED -> HeroVisuals(
            "Разрешение отозвано", "Нажмите, чтобы переподключиться",
            accent = Nocturne.warning, titleColor = Nocturne.warning,
            ringColors = listOf(Nocturne.warning, Nocturne.warpPink, Nocturne.warning),
            ringAlpha = 1f, glowColor = Nocturne.warning, glowAlpha = 0.28f,
            aurora1 = Nocturne.warning, aurora2 = Nocturne.warpPink,
            busy = false, connectedSpin = false,
            showMeta = false, metaColor = metaColor, protoLabel = protoLabel
        )
        else -> HeroVisuals(
            "Подключиться", "Самый быстрый доступный сервер",
            accent = Nocturne.neutral300, titleColor = Nocturne.text,
            ringColors = listOf(Nocturne.neutral800, Nocturne.accent700, Nocturne.vless, Nocturne.accent700, Nocturne.neutral800),
            ringAlpha = 0.55f, glowColor = Color.Transparent, glowAlpha = 0f,
            aurora1 = Nocturne.accent900, aurora2 = Nocturne.neutral900,
            busy = false, connectedSpin = false,
            showMeta = false, metaColor = metaColor, protoLabel = protoLabel
        )
    }
}

@androidx.compose.runtime.Composable
private fun AuroraBackground(hero: HeroVisuals) {
    val c1 by animateColorAsState(hero.aurora1, tween(1000), label = "aurora1")
    val c2 by animateColorAsState(hero.aurora2, tween(1000), label = "aurora2")
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-120).dp, y = (-60).dp)
                .background(Brush.radialGradient(listOf(c1.copy(alpha = 0.55f), Color.Transparent)), CircleShape)
        )
        Box(
            modifier = Modifier
                .size(260.dp)
                .align(Alignment.TopEnd)
                .offset(x = 120.dp, y = 120.dp)
                .background(Brush.radialGradient(listOf(c2.copy(alpha = 0.40f), Color.Transparent)), CircleShape)
        )
    }
}

@androidx.compose.runtime.Composable
private fun HeroBlock(
    hero: HeroVisuals,
    connectedSince: Long?,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "OMNIVPN",
            fontSize = 78.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = (-2.3).sp,
            maxLines = 1,
            color = Nocturne.text.copy(alpha = 0.04f),
            modifier = Modifier.align(Alignment.CenterStart).offset(x = (-8).dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(17.dp)
        ) {
            HeroPowerButton(hero = hero, onClick = onClick)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    hero.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 25.sp,
                    letterSpacing = (-0.3).sp,
                    color = hero.titleColor,
                    // Soft title glow in every state except disconnected
                    // (glowAlpha 0 there) - handoff: "text-glow 18dp @45%".
                    style = if (hero.glowAlpha > 0f) {
                        TextStyle(
                            shadow = Shadow(
                                color = hero.glowColor.copy(alpha = 0.45f),
                                blurRadius = with(LocalDensity.current) { 18.dp.toPx() }
                            )
                        )
                    } else TextStyle.Default
                )
                if (hero.subtitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(hero.subtitle, fontSize = 13.sp, color = Nocturne.neutral300)
                }
                if (hero.showMeta) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (hero.protoLabel != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(hero.metaColor.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(hero.metaColor))
                                Text(hero.protoLabel, fontSize = 10.5.sp, color = hero.metaColor)
                            }
                        }
                        val timer = sessionTimer(connectedSince)
                        if (timer != null) {
                            Text(timer, fontSize = 12.sp, color = Nocturne.neutral400, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HeroPowerButton(hero: HeroVisuals, onClick: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "hero")
    val spinAngle by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(if (hero.busy) 1200 else 10000, easing = LinearEasing), RepeatMode.Restart
        ),
        label = "spin"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.88f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val glowPulse by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
        label = "glowPulse"
    )
    val ringAngle = if (hero.busy || hero.connectedSpin) spinAngle else 0f

    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        if (hero.glowAlpha > 0f) {
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(if (hero.busy) pulse else 1f)
                    .alpha(if (hero.busy) glowPulse else 1f)
                    .background(
                        Brush.radialGradient(
                            0.0f to hero.glowColor.copy(alpha = hero.glowAlpha),
                            0.68f to Color.Transparent
                        ),
                        CircleShape
                    )
            )
        }
        Box(
            modifier = Modifier
                .size(130.dp)
                .rotate(ringAngle)
                .alpha(hero.ringAlpha)
                .clip(CircleShape)
                .background(Brush.sweepGradient(hero.ringColors))
        )
        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFF2C2F40), Nocturne.bg)))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            PowerGlyph(color = hero.accent, modifier = Modifier.size(44.dp))
        }
    }
}

/** HH:MM:SS since [connectedSince], recomputed every second, or null. */
@androidx.compose.runtime.Composable
private fun sessionTimer(connectedSince: Long?): String? {
    if (connectedSince == null) return null
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(connectedSince) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsed = ((now - connectedSince) / 1000).coerceAtLeast(0)
    val h = elapsed / 3600
    val m = (elapsed % 3600) / 60
    val s = elapsed % 60
    return "%02d:%02d:%02d".format(h, m, s)
}


// ============================ WARP card ================================

@androidx.compose.runtime.Composable
private fun WarpCard(
    vpnState: VpnState,
    isWarp: Boolean,
    connectedSince: Long?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val warpActive = isWarp && vpnState == VpnState.CONNECTED
    val warpConnecting = isWarp && vpnState == VpnState.CONNECTING
    val lit = warpActive || warpConnecting

    val bgBrush = if (lit) {
        Brush.linearGradient(listOf(Color(0xFF3B2718), Color(0xFF2C1F2A)))
    } else {
        Brush.linearGradient(listOf(Nocturne.surface, Nocturne.surface))
    }
    val borderBrush = if (lit) {
        Brush.linearGradient(listOf(Nocturne.warp, Nocturne.warpAmber, Nocturne.warpPink))
    } else {
        Brush.linearGradient(
            listOf(Nocturne.warp.copy(alpha = 0.53f), Nocturne.warpAmber.copy(alpha = 0.27f), Nocturne.warpPink.copy(alpha = 0.40f))
        )
    }
    val plateBrush = if (lit) {
        Brush.linearGradient(listOf(Nocturne.warp, Nocturne.warpAmber))
    } else {
        Brush.linearGradient(listOf(Nocturne.warp.copy(alpha = 0.16f), Nocturne.warp.copy(alpha = 0.16f)))
    }
    val iconColor = if (lit) Nocturne.warpInk else Nocturne.warp
    val title = when {
        warpActive -> "WARP подключён"
        warpConnecting -> "Подключаем WARP…"
        else -> "Cloudflare WARP"
    }
    val timer = sessionTimer(connectedSince)
    val subtitle = when {
        warpActive -> "Нажмите, чтобы отключить" + (timer?.let { " · $it" } ?: "")
        warpConnecting -> "Регистрация и рукопожатие"
        else -> "Подключение в одно касание"
    }
    val subColor = if (warpActive) Nocturne.warpAmber else Nocturne.neutral400

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            // Card is wider than the 17dp column: it bleeds 5.6dp past each
            // side (handoff: "выход за padding на 5.6dp с каждой стороны").
            // Measured wider than the incoming width and shifted left, while
            // still reporting the normal width so the column stacks unaffected.
            .layout { measurable, constraints ->
                val bleed = 5.6.dp.roundToPx()
                val w = constraints.maxWidth + bleed * 2
                val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bgBrush)
            .border(1.5.dp, borderBrush, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(plateBrush),
            contentAlignment = Alignment.Center
        ) {
            CloudGlyph(color = iconColor, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = Nocturne.text)
            Text(subtitle, fontSize = 12.sp, color = subColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        if (warpActive) {
            val blinkTransition = rememberInfiniteTransition(label = "warpPill")
            val dotAlpha by blinkTransition.animateFloat(
                initialValue = 1f, targetValue = 0.35f,
                animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
                label = "warpDot"
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.linearGradient(listOf(Nocturne.warp, Nocturne.warpAmber)))
                    .padding(horizontal = 9.dp, vertical = 4.dp)
            ) {
                Box(Modifier.size(6.dp).alpha(dotAlpha).clip(CircleShape).background(Nocturne.warpInk))
                Text("ВКЛ", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = Nocturne.warpInk)
            }
        } else {
            ArrowGlyph(color = Nocturne.warp, modifier = Modifier.size(16.dp))
        }
    }
}

// ============================ URL field ================================

@androidx.compose.runtime.Composable
private fun CompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    minHeight: Int = 40,
    singleLine: Boolean = true,
    mono: Boolean = false,
    leading: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    trailing: (@androidx.compose.runtime.Composable () -> Unit)? = null
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(
            color = Nocturne.text,
            fontSize = if (mono) 12.sp else 13.sp,
            fontFamily = if (mono) FontFamily.Monospace else InterFontFamily
        ),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Nocturne.accent400),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp)
            .clip(RoundedCornerShape(if (minHeight >= 60) 12.dp else 8.dp))
            .background(Nocturne.surface.copy(alpha = 0.8f))
            .border(1.dp, Nocturne.text.copy(alpha = 0.16f), RoundedCornerShape(if (minHeight >= 60) 12.dp else 8.dp)),
        decorationBox = { inner ->
            Row(
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                modifier = Modifier.padding(horizontal = 11.dp, vertical = if (singleLine) 0.dp else 10.dp)
            ) {
                if (leading != null) { leading(); Spacer(Modifier.width(9.dp)) }
                Box(modifier = Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            fontSize = if (mono) 12.sp else 13.sp,
                            color = Nocturne.neutral600,
                            fontFamily = if (mono) FontFamily.Monospace else InterFontFamily
                        )
                    }
                    inner()
                }
                if (trailing != null) { Spacer(Modifier.width(6.dp)); trailing() }
            }
        }
    )
}

@androidx.compose.runtime.Composable
private fun SubscriptionUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onAdd: () -> Unit,
    onPaste: () -> Unit
) {
    val addEnabled = enabled && value.isNotBlank()
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 11.dp)) {
        CompactTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "URL подписки",
            enabled = enabled,
            leading = { LinkGlyph(color = Nocturne.neutral500, modifier = Modifier.size(15.dp)) },
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Вставить",
                        fontSize = 12.5.sp,
                        color = Nocturne.accent400,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(enabled = enabled, onClick = onPaste)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            // Before background, so the whole button dims when disabled.
                            .alpha(if (addEnabled) 1f else 0.4f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Brush.linearGradient(listOf(Nocturne.vless, Nocturne.accent400)))
                            .clickable(enabled = addEnabled, onClick = onAdd),
                        contentAlignment = Alignment.Center
                    ) {
                        PlusGlyph(color = Nocturne.bg, modifier = Modifier.size(15.dp))
                    }
                }
            }
        )
    }
}

// ============================ Server list header =======================

@androidx.compose.runtime.Composable
private fun ServerListHeader(
    count: Int,
    refreshingAny: Boolean,
    testingAll: Boolean,
    refreshEnabled: Boolean,
    testEnabled: Boolean,
    onRefreshAll: () -> Unit,
    onTestAll: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 3.dp)
    ) {
        Text(
            "СЕРВЕРЫ · $count",
            fontSize = 11.sp,
            letterSpacing = 0.9.sp,
            color = Nocturne.neutral400,
            modifier = Modifier.weight(1f)
        )
        GhostAction(
            label = "Обновить",
            color = Nocturne.vless,
            enabled = refreshEnabled,
            spinning = refreshingAny,
            onClick = onRefreshAll
        ) { spin -> RefreshGlyph(color = Nocturne.vless, modifier = Modifier.size(14.dp).rotate(spin)) }
        Spacer(Modifier.width(4.dp))
        GhostAction(
            label = if (testingAll) "Проверка…" else "Проверить",
            color = Nocturne.connectedSoft,
            enabled = testEnabled,
            spinning = false,
            onClick = onTestAll
        ) { FullTestGlyph(color = Nocturne.connectedSoft, modifier = Modifier.size(14.dp)) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SwipeHintGlyph(color = Nocturne.neutral600, modifier = Modifier.size(13.dp))
        Text("Свайп влево — удалить сервер", fontSize = 11.sp, color = Nocturne.neutral600)
    }
}

@androidx.compose.runtime.Composable
private fun GhostAction(
    label: String,
    color: Color,
    enabled: Boolean,
    spinning: Boolean,
    onClick: () -> Unit,
    icon: @androidx.compose.runtime.Composable (spin: Float) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ghostSpin")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "a"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 7.dp, vertical = 4.dp)
    ) {
        icon(if (spinning) angle else 0f)
        Text(label, fontSize = 12.sp, color = color)
    }
}

@androidx.compose.runtime.Composable
private fun EmptyServersState() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 40.dp)) {
        LinkGlyph(color = Nocturne.vless, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(6.dp))
        Text("Серверов из подписок пока нет", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Nocturne.text)
        Spacer(Modifier.height(4.dp))
        Text(
            "Вставьте ссылку на подписку выше — серверы появятся здесь.",
            fontSize = 13.sp, color = Nocturne.neutral400, modifier = Modifier.widthIn(max = 260.dp)
        )
    }
}


// ============================ Server groups ============================

private val GROUP_GRADIENTS = listOf(
    listOf(Color(0xFF7C9EFF), Color(0xFFB5ABFC)),
    listOf(Color(0xFFE88AE0), Color(0xFFFFC658)),
    listOf(Color(0xFF6EE7B7), Color(0xFF7C9EFF)),
    listOf(Color(0xFFFF9B4A), Color(0xFFE88AE0))
)

private fun fmtFreq(minutes: Int): String = when {
    minutes < 60 -> "$minutes мин"
    minutes < 1440 -> "${minutes / 60} ч"
    else -> "24 ч"
}

private fun formatUpdatedAgo(lastUpdatedAt: Long?): String {
    if (lastUpdatedAt == null) return "ещё не обновлялась"
    val diffMin = ((System.currentTimeMillis() - lastUpdatedAt) / 60000L).coerceAtLeast(0)
    return when {
        diffMin < 1 -> "обновлено только что"
        diffMin < 60 -> "обновлено $diffMin мин назад"
        diffMin < 1440 -> "обновлено ${diffMin / 60} ч назад"
        else -> "обновлено ${diffMin / 1440} дн назад"
    }
}

@androidx.compose.runtime.Composable
private fun SubscriptionGroups(
    subscriptions: List<Subscription>,
    nodes: List<Node>,
    nodeHealth: Map<Long, NodeHealth>,
    refreshing: Set<Long>,
    autoUpdateEnabled: Boolean,
    autoUpdateIntervalMinutes: Int,
    activeConnection: ActiveConnection,
    vpnState: VpnState,
    connectable: Boolean,
    collapsedSubscriptions: Set<Long>,
    subscriptionTest: Pair<Long, TestMode>?,
    testEnabled: Boolean,
    onConnect: (Node) -> Unit,
    onTest: (Node) -> Unit,
    onRefresh: (Subscription) -> Unit,
    onDeleteRequest: (Node) -> Unit,
    onToggleCollapsed: (Subscription) -> Unit,
    onTestSubscription: (Subscription, TestMode) -> Unit
) {
    val live = vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING
    val activeNodeId = (activeConnection as? ActiveConnection.Server)?.node?.id
    Column(modifier = Modifier.fillMaxWidth()) {
        subscriptions.forEachIndexed { index, subscription ->
            val groupNodes = nodes.filter { it.sourceId == subscription.id }
            if (groupNodes.isEmpty()) return@forEachIndexed
            val meta = buildString {
                append("${groupNodes.size} серв. · ${formatUpdatedAgo(subscription.lastUpdatedAt)}")
                if (autoUpdateEnabled) append(" · авто ${fmtFreq(autoUpdateIntervalMinutes)}")
            }
            val spinning = refreshing.contains(subscription.id)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Brush.linearGradient(GROUP_GRADIENTS[index % GROUP_GRADIENTS.size])),
                    contentAlignment = Alignment.Center
                ) {
                    LinkGlyph(color = Nocturne.bg, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(subscription.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(meta, fontSize = 11.sp, color = Nocturne.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                SpinIconButton(spinning = spinning, enabled = true, onClick = { onRefresh(subscription) }) { angle ->
                    RefreshGlyph(color = Nocturne.neutral300, modifier = Modifier.size(16.dp).rotate(angle))
                }
                TestIconButton(
                    testing = subscriptionTest == (subscription.id to TestMode.QUICK),
                    enabled = testEnabled,
                    color = Nocturne.warning,
                    onClick = { onTestSubscription(subscription, TestMode.QUICK) }
                ) { LightningGlyph(color = Nocturne.warning, modifier = Modifier.size(13.dp)) }
                TestIconButton(
                    testing = subscriptionTest == (subscription.id to TestMode.FULL),
                    enabled = testEnabled,
                    color = Nocturne.connectedSoft,
                    onClick = { onTestSubscription(subscription, TestMode.FULL) }
                ) { FullTestGlyph(color = Nocturne.connectedSoft, modifier = Modifier.size(13.dp)) }
                CollapseChevronButton(
                    collapsed = subscription.id in collapsedSubscriptions,
                    onClick = { onToggleCollapsed(subscription) }
                )
            }
            AnimatedVisibility(
                visible = subscription.id !in collapsedSubscriptions,
                modifier = Modifier.fillMaxWidth(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(7.dp))
                    groupNodes.forEach { node ->
                        ServerRow(
                            node = node,
                            health = nodeHealth[node.id] ?: NodeHealth.Unknown,
                            active = activeNodeId == node.id && live,
                            connectable = connectable,
                            onConnect = { onConnect(node) },
                            onTest = { onTest(node) },
                            onDelete = { onDeleteRequest(node) }
                        )
                        Spacer(Modifier.height(7.dp))
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SpinIconButton(
    spinning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @androidx.compose.runtime.Composable (angle: Float) -> Unit
) {
    val transition = rememberInfiniteTransition(label = "spinBtn")
    val angle by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart), label = "a"
    )
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content(if (spinning) angle else 0f) }
}

/**
 * Small icon button for a subscription group's own quick/full test (see the
 * September 2026 collapse/reorder/per-group-test addition) - shows a small
 * spinner in place of [icon] while [testing], rather than spinning the icon
 * itself the way [SpinIconButton] does for refresh: a lightning bolt or
 * checkmark spinning in place reads as broken, not busy.
 */
@androidx.compose.runtime.Composable
private fun TestIconButton(
    testing: Boolean,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    icon: @androidx.compose.runtime.Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled && !testing, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (testing) {
            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp, color = color)
        } else {
            icon()
        }
    }
}

/**
 * Chevron that rotates from pointing right (collapsed) to pointing down
 * (expanded) - the main screen's per-group collapse toggle.
 */
@androidx.compose.runtime.Composable
private fun CollapseChevronButton(collapsed: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(if (collapsed) 0f else 90f, tween(200), label = "chevron")
    Box(
        modifier = Modifier.size(32.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ArrowGlyph(color = Nocturne.neutral400, modifier = Modifier.size(13.dp).rotate(rotation))
    }
}

@androidx.compose.runtime.Composable
private fun ServerRow(
    node: Node,
    health: NodeHealth,
    active: Boolean,
    connectable: Boolean,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    val proto = protocolColor(node.protocol)
    SwipeToDeleteRow(onDelete = onDelete, modifier = Modifier.fillMaxWidth()) {
        val rowBg = if (active) {
            Brush.horizontalGradient(listOf(Nocturne.connected.copy(alpha = 0.12f), Nocturne.surface))
        } else {
            Brush.linearGradient(listOf(Nocturne.surface.copy(alpha = 0.88f), Nocturne.surface.copy(alpha = 0.88f)))
        }
        val border = if (active) Nocturne.connected else Color.White.copy(alpha = 0.04f)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(rowBg)
                .border(1.dp, border, RoundedCornerShape(12.dp))
                .clickable(enabled = connectable, onClick = onConnect)
                .padding(11.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(proto, proto.copy(alpha = 0.35f))))
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${node.server}:${node.port}",
                    fontSize = 11.sp, color = Nocturne.neutral500, fontFamily = FontFamily.Monospace,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            ProtocolChip(node.protocol)
            Spacer(Modifier.width(8.dp))
            HealthIndicator(health = health, onTest = onTest)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ProtocolChip(protocol: Protocol) {
    val color = protocolColor(protocol)
    Text(
        protocolLabel(protocol),
        fontSize = 10.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    )
}

@androidx.compose.runtime.Composable
private fun HealthIndicator(health: NodeHealth, onTest: () -> Unit) {
    val dim = Nocturne.neutral800
    val label: String
    val color: Color
    val bars: List<Color>
    when (health) {
        is NodeHealth.Unknown -> { label = "Тест"; color = Nocturne.accent400; bars = listOf(dim, dim, dim) }
        is NodeHealth.Checking -> { label = "···"; color = Nocturne.neutral400; bars = listOf(dim, dim, dim) }
        is NodeHealth.Reachable -> {
            label = "${health.latencyMs} мс"
            val ms = health.latencyMs
            color = when { ms < 60 -> Nocturne.connected; ms < 100 -> Nocturne.warning; else -> Nocturne.warp }
            val k = when { ms < 60 -> 3; ms < 100 -> 2; else -> 1 }
            bars = (0 until 3).map { if (it < k) color else dim }
        }
        is NodeHealth.Unreachable -> { label = "Нет связи"; color = Nocturne.error; bars = listOf(Nocturne.error, dim, dim) }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onTest).padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(label, fontSize = 12.sp, color = color, fontFamily = FontFamily.Monospace)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val heights = listOf(4.dp, 7.dp, 11.dp)
            bars.forEachIndexed { i, c ->
                Box(Modifier.width(3.dp).height(heights[i]).clip(RoundedCornerShape(1.dp)).background(c))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun protocolColor(protocol: Protocol): Color = when (protocol) {
    Protocol.VLESS -> Nocturne.vless
    Protocol.TROJAN -> Nocturne.trojan
    Protocol.HYSTERIA2 -> Nocturne.hysteria2
    Protocol.AMNEZIAWG -> Nocturne.amneziaWg
    Protocol.WARP -> Nocturne.warp
    Protocol.SHADOWSOCKS -> Nocturne.shadowsocks
    Protocol.VMESS -> Nocturne.vmess
    Protocol.TUIC -> Nocturne.tuic
}

// The protocol badge's text: the enum name, except SHADOWSOCKS - too long for the chip,
// and "SS" is what every client calls it anyway.
private fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.SHADOWSOCKS -> "SS"
    else -> protocol.name
}

// ============================ Swipe-to-delete ==========================

@androidx.compose.runtime.Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    val density = LocalDensity.current
    val maxSwipePx = remember(density) { with(density) { 140.dp.toPx() } }
    val thresholdPx = remember(density) { with(density) { 90.dp.toPx() } }
    var offsetX by remember { mutableStateOf(0f) }
    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-maxSwipePx, 0f)
    }
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                // alpha has to come BEFORE background: placed after it, it only
                // faded the "Удалить" label and left the red gradient fully
                // painted under every row at rest (visible as a maroon tint
                // through the rows' 88%-opaque surface).
                .alpha((-offsetX / with(density) { 60.dp.toPx() }).coerceIn(0f, 1f))
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF3A1F24), Nocturne.error))),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(end = 20.dp)
            ) {
                DeleteGlyph(color = Color(0xFF1D0F10), modifier = Modifier.size(17.dp))
                Text("Удалить", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D0F10))
            }
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (offsetX < -thresholdPx) onDelete()
                        androidx.compose.animation.core.animate(offsetX, 0f, animationSpec = tween(250)) { v, _ -> offsetX = v }
                    }
                )
        ) { content() }
    }
}

// ============================ Snackbar =================================

@androidx.compose.runtime.Composable
private fun SnackbarOverlay(
    message: String?,
    onShown: () -> Unit,
    modifier: Modifier = Modifier
) {
    var visibleText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            visibleText = message
            onShown()
            delay(2400)
            visibleText = null
        }
    }
    val text = visibleText ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .navigationBarsPadding()
            .padding(horizontal = 17.dp)
            .padding(bottom = 34.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nocturne.snackbar)
            .border(1.dp, Nocturne.neutral800, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Nocturne.connectedSoft))
        Text(text, fontSize = 13.sp, color = Nocturne.neutral100)
    }
}


// ============================ Settings sheet ===========================

private val AUTO_UPDATE_FREQS = listOf(15, 30, 60, 180, 360, 720, 1440)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SettingsSheet(
    viewModel: MainViewModel,
    input: String,
    onInputChange: (String) -> Unit,
    onImport: () -> Unit,
    onPickFile: () -> Unit,
    warpEndpointInput: String,
    onWarpEndpointChange: (String) -> Unit,
    onWarpWithOverride: () -> Unit,
    onFindWarpEndpoint: () -> Unit,
    onResetWarpRequested: () -> Unit,
    onConnectNode: (Node) -> Unit,
    onClose: () -> Unit
) {
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val nodeHealth by viewModel.nodeHealth.collectAsStateWithLifecycle()
    val testMode by viewModel.testMode.collectAsStateWithLifecycle()
    val ipv6Enabled by viewModel.ipv6Enabled.collectAsStateWithLifecycle()
    val quickTileStartsWarp by viewModel.quickTileStartsWarp.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshingSubscriptions.collectAsStateWithLifecycle()
    val autoUpdateEnabled by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateInterval by viewModel.autoUpdateIntervalMinutes.collectAsStateWithLifecycle()
    val canStartVpn = vpnState == VpnState.DISCONNECTED || vpnState == VpnState.ERROR || vpnState == VpnState.REVOKED
    val importedNodes = nodes.filter { it.sourceId == null }
    var renamingSubscription by remember { mutableStateOf<Subscription?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Brush.verticalGradient(listOf(Nocturne.sheetTop, Nocturne.sheetBottom)))
    ) {
        // Accent hairline + grabber.
        Box(
            modifier = Modifier
                .padding(horizontal = 60.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Nocturne.vless, Nocturne.warpPink, Nocturne.warpAmber, Color.Transparent)
                    )
                )
        )
        Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 3.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Nocturne.neutral700))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 17.dp, vertical = 6.dp)
        ) {
            // 1. Title
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Настройки", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Nocturne.text, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center
                ) { CloseGlyph(color = Nocturne.neutral300, modifier = Modifier.size(18.dp)) }
            }

            // 2. Busy indicator
            if (busy) {
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Nocturne.accent400.copy(alpha = 0.14f))
                        .padding(horizontal = 11.dp, vertical = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Nocturne.accent400)
                    Text("Выполняется…", fontSize = 13.sp, color = Color(0xFFE7E5FE), modifier = Modifier.weight(1f))
                    Text(
                        "Отмена",
                        fontSize = 13.sp, color = Nocturne.accent400,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { viewModel.cancelTests() }.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 3. Auto-update card
            Spacer(Modifier.height(22.dp))
            AutoUpdateCard(
                enabled = autoUpdateEnabled,
                intervalMinutes = autoUpdateInterval,
                onToggle = { viewModel.setAutoUpdateEnabled(it) },
                onPick = { viewModel.setAutoUpdateInterval(it) }
            )

            // 4. Test mode
            Spacer(Modifier.height(22.dp))
            SheetSectionLabel("Режим проверки")
            Spacer(Modifier.height(8.dp))
            TestModeButtons(mode = testMode, enabled = !busy, onChange = { viewModel.setTestMode(it) })
            Spacer(Modifier.height(6.dp))
            Text(
                if (testMode == TestMode.QUICK) "TCP-рукопожатие с сервером — быстро, но без проверки протокола."
                else "Реальный запрос через sing-box — дольше, но точнее.",
                fontSize = 12.sp, color = Nocturne.neutral500
            )

            // 4a. Quick Settings tile action
            Spacer(Modifier.height(22.dp))
            SheetSectionLabel("Плитка в шторке включает")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                SelectableChip(
                    label = "WARP",
                    selected = quickTileStartsWarp,
                    selectedColor = Nocturne.warp,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setQuickTileStartsWarp(true) }
                )
                SelectableChip(
                    label = "Самый быстрый сервер",
                    selected = !quickTileStartsWarp,
                    selectedColor = Nocturne.vless,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.setQuickTileStartsWarp(false) }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (quickTileStartsWarp) "Нажатие на плитку включает Cloudflare WARP."
                else "Плитка быстро проверяет серверы подписок и подключается к самому быстрому.",
                fontSize = 12.sp, color = Nocturne.neutral500
            )

            // 5. Subscriptions
            if (subscriptions.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                SheetSectionLabel("Подписки · ${subscriptions.size}")
                Spacer(Modifier.height(8.dp))
                subscriptions.forEachIndexed { index, subscription ->
                    SubscriptionSheetRow(
                        subscription = subscription,
                        index = index,
                        spinning = refreshing.contains(subscription.id),
                        enabled = !busy,
                        testEnabled = !busy && canStartVpn,
                        canMoveUp = index > 0,
                        canMoveDown = index < subscriptions.lastIndex,
                        onRefresh = { viewModel.refresh(subscription) },
                        onTest = { viewModel.checkSubscriptionNodes(subscription) },
                        onDelete = { viewModel.delete(subscription) },
                        onMoveUp = { viewModel.moveSubscription(subscription, -1) },
                        onMoveDown = { viewModel.moveSubscription(subscription, 1) },
                        onRenameRequest = { renamingSubscription = subscription }
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // 6. Import configuration
            Spacer(Modifier.height(22.dp))
            SheetSectionLabel("Импорт конфигурации")
            Spacer(Modifier.height(8.dp))
            CompactTextField(
                value = input,
                onValueChange = onInputChange,
                placeholder = "vless://… · trojan://… · JSON · YAML",
                enabled = !busy,
                minHeight = 72,
                singleLine = false,
                mono = true
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                OutlineButton(
                    label = "Импортировать",
                    color = Nocturne.accent400,
                    enabled = !busy && input.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    onClick = onImport
                ) { DownloadGlyph(color = Nocturne.accent400, modifier = Modifier.size(15.dp)) }
                SecondaryButton(
                    label = "Из файла",
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                    onClick = onPickFile
                ) { FileGlyph(color = Nocturne.neutral300, modifier = Modifier.size(15.dp)) }
            }

            // 7. Cloudflare WARP
            Spacer(Modifier.height(22.dp))
            SheetSectionLabel("Cloudflare WARP", color = Nocturne.warp)
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                CompactTextField(
                    value = warpEndpointInput,
                    onValueChange = onWarpEndpointChange,
                    placeholder = "Свой endpoint · 162.159.192.1:2408",
                    enabled = !busy,
                    trailing = {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                // Before background, so the whole button dims when disabled.
                                .alpha(if (!busy && canStartVpn) 1f else 0.4f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Brush.linearGradient(listOf(Nocturne.warp, Nocturne.warpAmber)))
                                .clickable(enabled = !busy && canStartVpn, onClick = onWarpWithOverride),
                            contentAlignment = Alignment.Center
                        ) { ArrowGlyph(color = Nocturne.warpInk, modifier = Modifier.size(14.dp)) }
                    }
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.18f))
            ) {
                SettingsListRow(
                    glyph = { TargetGlyph(color = Nocturne.warpAmber, modifier = Modifier.size(18.dp)) },
                    title = "Найти рабочий endpoint",
                    enabled = !busy && canStartVpn,
                    trailing = { ArrowGlyph(color = Nocturne.neutral600, modifier = Modifier.size(14.dp)) },
                    onClick = onFindWarpEndpoint
                )
                Box(Modifier.padding(horizontal = 11.dp).fillMaxWidth().height(1.dp).background(Nocturne.text.copy(alpha = 0.16f)))
                SettingsListRow(
                    glyph = { WarningGlyph(color = Nocturne.error, modifier = Modifier.size(18.dp)) },
                    title = "Сбросить аккаунт WARP",
                    enabled = !busy && canStartVpn,
                    trailing = { ArrowGlyph(color = Nocturne.neutral600, modifier = Modifier.size(14.dp)) },
                    onClick = onResetWarpRequested
                )
            }
            Spacer(Modifier.height(8.dp))
            // IPv6 toggle
            SettingsCardRow(
                glyph = { GlobeGlyph(color = Nocturne.warp, modifier = Modifier.size(18.dp)) },
                tint = Nocturne.warp,
                title = "IPv6 для WARP",
                subtitle = if (ipv6Enabled) "Вкл — для сетей с родным IPv6. Выключите, если сломан именно IPv6 сети."
                else "Выкл — только IPv4",
                trailing = {
                    Switch(
                        checked = ipv6Enabled,
                        onCheckedChange = { viewModel.setIpv6Enabled(it) },
                        enabled = !busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFF3F5FE),
                            checkedTrackColor = Nocturne.vless,
                            uncheckedThumbColor = Color(0xFFF3F5FE),
                            uncheckedTrackColor = Nocturne.neutral800,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            )
            Spacer(Modifier.height(8.dp))

            // 7a. AmneziaWG obfuscation autotune
            ObfuscationCard(viewModel = viewModel, busy = busy, canStartVpn = canStartVpn)

            // 8. Imported configs
            Spacer(Modifier.height(22.dp))
            SheetSectionLabel("Импортированные конфигурации")
            Spacer(Modifier.height(8.dp))
            if (importedNodes.isEmpty()) {
                Text(
                    "Конфигурации, вставленные текстом или из файла (не из подписки), появляются здесь.",
                    fontSize = 13.sp, color = Nocturne.neutral400
                )
            } else {
                importedNodes.forEach { node ->
                    ImportedConfigRow(node = node, onConnect = { onConnectNode(node) })
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }

    renamingSubscription?.let { subscription ->
        RenameSubscriptionDialog(
            initialName = subscription.name,
            onConfirm = { newName ->
                viewModel.renameSubscription(subscription, newName)
                renamingSubscription = null
            },
            onDismiss = { renamingSubscription = null }
        )
    }
}

@androidx.compose.runtime.Composable
private fun SheetSectionLabel(text: String, color: Color = Nocturne.neutral400) {
    Text(text, fontSize = 11.sp, letterSpacing = 0.9.sp, color = color)
}

@androidx.compose.runtime.Composable
private fun AutoUpdateCard(
    enabled: Boolean,
    intervalMinutes: Int,
    onToggle: (Boolean) -> Unit,
    onPick: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(listOf(Nocturne.vless.copy(alpha = 0.12f), Nocturne.connectedSoft.copy(alpha = 0.08f))))
            .border(1.dp, Nocturne.vless.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(Nocturne.vless, Nocturne.connectedSoft))),
                contentAlignment = Alignment.Center
            ) { RefreshGlyph(color = Nocturne.bg, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Автообновление подписок", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Nocturne.text)
                Text(
                    if (enabled) "Каждые ${fmtFreq(intervalMinutes)}" else "Выключено — только вручную",
                    fontSize = 12.sp, color = Nocturne.neutral400
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFFF3F5FE),
                    checkedTrackColor = Nocturne.connectedSoft,
                    uncheckedThumbColor = Color(0xFFF3F5FE),
                    uncheckedTrackColor = Nocturne.neutral800,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
        Spacer(Modifier.height(11.dp))
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.4f)) {
            SheetSectionLabel("Частота")
            Spacer(Modifier.height(7.dp))
            AUTO_UPDATE_FREQS.chunked(4).forEach { rowFreqs ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    rowFreqs.forEach { m ->
                        SelectableChip(
                            label = fmtFreq(m),
                            selected = intervalMinutes == m,
                            selectedColor = Nocturne.vless,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(m) }
                        )
                    }
                    repeat(4 - rowFreqs.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
            Text("Минимальный интервал — 15 минут.", fontSize = 11.5.sp, color = Nocturne.neutral500)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SelectableChip(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val border = if (selected) selectedColor else Color.White.copy(alpha = 0.08f)
    val bg = if (selected) selectedColor.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.15f)
    val fg = if (selected) selectedColor else Nocturne.neutral300
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.5.sp, color = fg)
    }
}

@androidx.compose.runtime.Composable
private fun TestModeButtons(mode: TestMode, enabled: Boolean, onChange: (TestMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        val quick = mode == TestMode.QUICK
        val full = mode == TestMode.FULL
        ModeButton("Быстрая", quick, Nocturne.warning, enabled, Modifier.weight(1f), { onChange(TestMode.QUICK) }) {
            LightningGlyph(color = if (quick) Nocturne.warning else Nocturne.neutral300, modifier = Modifier.size(15.dp))
        }
        ModeButton("Полная", full, Nocturne.connectedSoft, enabled, Modifier.weight(1f), { onChange(TestMode.FULL) }) {
            FullTestGlyph(color = if (full) Nocturne.connectedSoft else Nocturne.neutral300, modifier = Modifier.size(15.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    selectedColor: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @androidx.compose.runtime.Composable () -> Unit
) {
    val border = if (selected) selectedColor else Color.White.copy(alpha = 0.08f)
    val bg = if (selected) selectedColor.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.15f)
    val fg = if (selected) selectedColor else Nocturne.neutral300
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
    ) {
        icon()
        Text(label, fontSize = 13.sp, color = fg)
    }
}

@androidx.compose.runtime.Composable
private fun SettingsListRow(
    glyph: @androidx.compose.runtime.Composable () -> Unit,
    title: String,
    enabled: Boolean,
    trailing: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(11.dp)
    ) {
        glyph()
        Text(title, fontSize = 14.sp, color = Nocturne.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@androidx.compose.runtime.Composable
private fun SettingsCardRow(
    glyph: @androidx.compose.runtime.Composable () -> Unit,
    tint: Color,
    title: String,
    subtitle: String,
    trailing: @androidx.compose.runtime.Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .padding(14.dp)
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { glyph() }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Nocturne.text)
            Text(subtitle, fontSize = 12.sp, color = Nocturne.neutral400)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/**
 * Compact up/down stack for reordering a subscription in the Settings list
 * (see the September 2026 collapse/reorder/per-group-test addition) - two
 * small independently-clickable arrows rather than drag handles, since the
 * list is short and this needs no extra gesture-handling dependency.
 */
@androidx.compose.runtime.Composable
private fun ReorderArrows(
    enabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 16.dp)
                .alpha(if (enabled && canMoveUp) 1f else 0.3f)
                .clickable(enabled = enabled && canMoveUp, onClick = onMoveUp),
            contentAlignment = Alignment.Center
        ) {
            ArrowGlyph(color = Nocturne.neutral300, modifier = Modifier.size(11.dp).rotate(-90f))
        }
        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 16.dp)
                .alpha(if (enabled && canMoveDown) 1f else 0.3f)
                .clickable(enabled = enabled && canMoveDown, onClick = onMoveDown),
            contentAlignment = Alignment.Center
        ) {
            ArrowGlyph(color = Nocturne.neutral300, modifier = Modifier.size(11.dp).rotate(90f))
        }
    }
}

@androidx.compose.runtime.Composable
private fun SubscriptionSheetRow(
    subscription: Subscription,
    index: Int,
    spinning: Boolean,
    enabled: Boolean,
    testEnabled: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRefresh: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRenameRequest: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.18f)).padding(start = 11.dp, top = 8.dp, bottom = 8.dp, end = 6.dp)
    ) {
        ReorderArrows(
            enabled = enabled,
            canMoveUp = canMoveUp,
            canMoveDown = canMoveDown,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown
        )
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier.size(24.dp).clip(RoundedCornerShape(7.dp)).background(Brush.linearGradient(GROUP_GRADIENTS[index % GROUP_GRADIENTS.size])),
            contentAlignment = Alignment.Center
        ) { LinkGlyph(color = Nocturne.bg, modifier = Modifier.size(13.dp)) }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onRenameRequest)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(subscription.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                PencilGlyph(color = Nocturne.neutral600, modifier = Modifier.size(11.dp))
            }
            Text(subscription.url, fontSize = 11.5.sp, color = Nocturne.neutral500, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SpinIconButton(spinning = spinning, enabled = enabled, onClick = onRefresh) { a ->
            RefreshGlyph(color = Nocturne.vless, modifier = Modifier.size(16.dp).rotate(a))
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = testEnabled, onClick = onTest).alpha(if (testEnabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
            FullTestGlyph(color = Nocturne.connectedSoft, modifier = Modifier.size(16.dp))
        }
        Box(Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onDelete), contentAlignment = Alignment.Center) {
            DeleteGlyph(color = Nocturne.error, modifier = Modifier.size(16.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun ImportedConfigRow(node: Node, onConnect: () -> Unit) {
    val proto = protocolColor(node.protocol)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.18f)).clickable(onClick = onConnect).padding(11.dp)
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Brush.radialGradient(listOf(proto, proto.copy(alpha = 0.35f)))))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(node.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Nocturne.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${node.server}:${node.port}", fontSize = 11.sp, color = Nocturne.neutral500, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        ProtocolChip(node.protocol)
    }
}

@androidx.compose.runtime.Composable
private fun OutlineButton(
    label: String,
    color: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @androidx.compose.runtime.Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .height(38.dp)
            // Before border, so a disabled button dims as a whole, border included.
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        Box(Modifier.weight(1f, fill = false)) { icon() }
        Text(label, fontSize = 13.sp, color = color)
    }
}

@androidx.compose.runtime.Composable
private fun SecondaryButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    icon: @androidx.compose.runtime.Composable () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .height(38.dp)
            // Before border, so a disabled button dims as a whole, border included.
            .alpha(if (enabled) 1f else 0.4f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, Nocturne.text.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        icon()
        Text(label, fontSize = 13.sp, color = Nocturne.neutral200)
    }
}


// ============================ Obfuscation card =========================

private fun obfLabel(obf: AwgObfuscation): String {
    val i1 = if (obf.i1.isNullOrBlank()) "—" else "QUIC"
    val ep = obf.endpoint?.substringBefore(':')?.let { " · $it" } ?: ""
    return "jc ${obf.jc} · ${obf.jmin}–${obf.jmax} · i1 $i1$ep"
}

@androidx.compose.runtime.Composable
private fun ObfuscationCard(viewModel: MainViewModel, busy: Boolean, canStartVpn: Boolean) {
    val tuneState by viewModel.tuneState.collectAsStateWithLifecycle()
    val obf by viewModel.currentObfuscation.collectAsStateWithLifecycle()
    val isCustom by viewModel.obfuscationIsCustom.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.18f))
            .border(1.dp, Nocturne.warp.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Nocturne.warp.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { WandGlyph(color = Nocturne.warp, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Обфускация AmneziaWG", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Nocturne.text)
                Text(
                    if (isCustom) "Подобранные" + (obf.tunedLatencyMs?.let { " · $it мс при подборе" } ?: "") else "Стандартные параметры",
                    fontSize = 12.sp, color = if (isCustom) Nocturne.warpAmber else Nocturne.neutral400
                )
            }
        }
        Spacer(Modifier.height(11.dp))
        // Current-value chips
        val chips = buildList {
            add("jc ${obf.jc}"); add("jmin ${obf.jmin}"); add("jmax ${obf.jmax}")
            add("i1 ${if (obf.i1.isNullOrBlank()) "нет" else "QUIC"}")
            add("s1–s4 0"); add("h1–h4 стд")
            obf.endpoint?.let { add("ep ${it.substringBefore(':')}") }
        }
        MonoChipRow(chips)
        Spacer(Modifier.height(11.dp))

        when (val st = tuneState) {
            is TuneState.Idle -> {
                OutlineButton(
                    label = "Подобрать параметры",
                    color = Nocturne.warp,
                    enabled = !busy && canStartVpn,
                    modifier = Modifier.fillMaxWidth().height(40.dp),
                    onClick = { viewModel.startTune() }
                ) { WandGlyph(color = Nocturne.warp, modifier = Modifier.size(15.dp)) }
                if (isCustom) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Сбросить к стандартным",
                        fontSize = 13.sp, color = Nocturne.neutral300,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { viewModel.resetObfuscation() }.padding(8.dp),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    "Подбор запускается только вручную: около 15 вариантов проверяются на 3 лучших адресах WARP. " +
                        "Пока вы его не запустите, используются стандартные параметры.",
                    fontSize = 11.5.sp, color = Nocturne.neutral500
                )
            }
            is TuneState.Running -> {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Вариант ${st.step} из ${st.total}", fontSize = 12.5.sp, color = Nocturne.text, modifier = Modifier.weight(1f))
                    if (st.okCount > 0) Text("рабочих: ${st.okCount}", fontSize = 12.5.sp, color = Nocturne.warpAmber, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(8.dp))
                val fraction = if (st.total > 0) st.step.toFloat() / st.total else 0f
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Nocturne.neutral800)) {
                    Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Brush.horizontalGradient(listOf(Nocturne.warp, Nocturne.warpAmber))))
                }
                Spacer(Modifier.height(8.dp))
                st.log.forEach { line ->
                    val ok = line.latencyMs != null
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(vertical = 1.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(if (ok) Nocturne.connected else Nocturne.neutral600))
                        Text(obfLabel(line.candidate), fontSize = 11.sp, color = Nocturne.neutral400, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(if (ok) "${line.latencyMs} мс" else "нет рукопожатия", fontSize = 11.sp, color = if (ok) Nocturne.connected else Nocturne.neutral600, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Остановить", fontSize = 13.sp, color = Nocturne.neutral300, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { viewModel.cancelTune() }.padding(vertical = 4.dp))
            }
            is TuneState.Found -> {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nocturne.connected.copy(alpha = 0.10f)).border(1.dp, Nocturne.connected.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(11.dp)
                ) {
                    Text("Найден рабочий вариант · ${st.latencyMs} мс", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Nocturne.connected)
                    Spacer(Modifier.height(6.dp))
                    Text(obfLabel(st.best), fontSize = 11.5.sp, color = Nocturne.neutral200, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(4.dp))
                    Text("Рабочих вариантов: ${st.okCount} из ${st.total}. Выбран самый быстрый.", fontSize = 11.5.sp, color = Nocturne.neutral400)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlineButton(label = "Применить", color = Nocturne.connected, enabled = true, modifier = Modifier.weight(1f), onClick = { viewModel.applyTune() }) {}
                        SecondaryButton(label = "Оставить текущие", enabled = true, modifier = Modifier.weight(1f), onClick = { viewModel.dismissTune() }) {}
                    }
                }
            }
            is TuneState.NotFound -> {
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Nocturne.error.copy(alpha = 0.10f)).padding(11.dp)
                ) {
                    Text("Рабочих вариантов не найдено", fontSize = 13.sp, color = Nocturne.error)
                    Spacer(Modifier.height(4.dp))
                    Text("Текущие параметры не изменены.", fontSize = 11.5.sp, color = Nocturne.neutral400)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        SecondaryButton(label = "Повторить", enabled = !busy && canStartVpn, modifier = Modifier.weight(1f), onClick = { viewModel.startTune() }) {}
                        SecondaryButton(label = "Закрыть", enabled = true, modifier = Modifier.weight(1f), onClick = { viewModel.dismissTune() }) {}
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MonoChipRow(chips: List<String>) {
    // Simple wrap: lay out in rows of up to 3 mono chips.
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        chips.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { chip ->
                    Text(
                        chip,
                        fontSize = 11.sp, color = Nocturne.neutral200, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

// ============================ Dialog ===================================

@androidx.compose.runtime.Composable
private fun OmniDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.surface,
        titleContentColor = Nocturne.text,
        textContentColor = Nocturne.neutral300,
        icon = {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Nocturne.error.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) { WarningGlyph(color = Nocturne.error, modifier = Modifier.size(20.dp)) }
        },
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium) },
        text = { Text(body, fontSize = 14.sp) },
        confirmButton = {
            Text(
                confirmLabel,
                fontSize = 14.sp, color = Nocturne.error,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Nocturne.error, RoundedCornerShape(8.dp)).clickable(onClick = onConfirm).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                "Отмена",
                fontSize = 14.sp, color = Nocturne.neutral300,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    )
}

/**
 * Lets the user give a subscription a real name (see the September 2026 fix
 * for every subscription showing the same literal "Subscription" -
 * MainViewModel.import() used to hardcode that instead of leaving the name
 * blank for SubscriptionRepository.add()'s own URL-host fallback to fill in).
 */
@androidx.compose.runtime.Composable
private fun RenameSubscriptionDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nocturne.surface,
        titleContentColor = Nocturne.text,
        icon = {
            Box(
                modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Nocturne.vless.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) { LinkGlyph(color = Nocturne.vless, modifier = Modifier.size(18.dp)) }
        },
        title = { Text("Переименовать подписку", fontSize = 20.sp, fontWeight = FontWeight.Medium) },
        text = {
            CompactTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Название подписки",
                enabled = true
            )
        },
        confirmButton = {
            Text(
                "Сохранить",
                fontSize = 14.sp, color = Nocturne.accent400,
                modifier = Modifier
                    .alpha(if (text.isNotBlank()) 1f else 0.4f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Nocturne.accent400, RoundedCornerShape(8.dp))
                    .clickable(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) })
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        },
        dismissButton = {
            Text(
                "Отмена",
                fontSize = 14.sp, color = Nocturne.neutral300,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDismiss).padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    )
}

// ============================ Glyphs (hand-drawn) ======================

@androidx.compose.runtime.Composable
private fun MarkGlyph(color: Color = Nocturne.text, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        val r = size.minDimension / 2f * 0.72f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = color, startAngle = -10f, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val dotAngle = Math.toRadians(-45.0)
        val dot = Offset(c.x + (r * kotlin.math.cos(dotAngle)).toFloat(), c.y + (r * kotlin.math.sin(dotAngle)).toFloat())
        drawCircle(color = Nocturne.accent400, radius = stroke * 1.1f, center = dot)
    }
}

@androidx.compose.runtime.Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.11f
        val r = size.minDimension / 2f * 0.62f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = color, startAngle = -60f, sweepAngle = 300f, useCenter = false,
            topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2f, r * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(color = color, start = Offset(c.x, c.y - r * 1.35f), end = Offset(c.x, c.y - r * 0.1f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun HamburgerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.11f
        val left = size.width * 0.14f
        val right = size.width * 0.86f
        listOf(0.3f, 0.5f, 0.7f).forEach { fy ->
            drawLine(color, Offset(left, size.height * fy), Offset(right, size.height * fy), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@androidx.compose.runtime.Composable
private fun CloudGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        drawCircle(color, radius = h * 0.24f, center = Offset(w * 0.36f, h * 0.52f))
        drawCircle(color, radius = h * 0.30f, center = Offset(w * 0.60f, h * 0.46f))
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.18f, h * 0.52f),
            size = Size(w * 0.64f, h * 0.26f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * 0.13f)
        )
    }
}

@androidx.compose.runtime.Composable
private fun PlusGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.16f
        val half = size.minDimension * 0.32f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y - half), Offset(c.x, c.y + half), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun ArrowGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.16f
        val c = Offset(size.width / 2f, size.height / 2f)
        val half = size.minDimension * 0.28f
        drawLine(color, Offset(c.x - half * 0.5f, c.y - half), Offset(c.x + half * 0.6f, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.x + half * 0.6f, c.y), Offset(c.x - half * 0.5f, c.y + half), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun LinkGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.13f
        val r = size.minDimension * 0.26f
        val cy = size.height / 2f
        drawCircle(color, radius = r, center = Offset(size.width * 0.36f, cy), style = Stroke(width = stroke))
        drawCircle(color, radius = r, center = Offset(size.width * 0.64f, cy), style = Stroke(width = stroke))
    }
}

@androidx.compose.runtime.Composable
private fun RefreshGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val r = size.minDimension / 2f * 0.7f
        val tl = Offset(size.width / 2f - r, size.height / 2f - r)
        val arc = Size(r * 2f, r * 2f)
        drawArc(color, -150f, 210f, false, tl, arc, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawArc(color, 30f, 210f, false, tl, arc, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@androidx.compose.runtime.Composable
private fun FullTestGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val r = size.minDimension / 2f * 0.82f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(color, radius = r, center = c, style = Stroke(width = stroke))
        drawLine(color, Offset(c.x - r * 0.45f, c.y), Offset(c.x - r * 0.05f, c.y + r * 0.4f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c.x - r * 0.05f, c.y + r * 0.4f), Offset(c.x + r * 0.5f, c.y - r * 0.35f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun SwipeHintGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val c = size.height / 2f
        listOf(0.6f, 0.35f).forEach { fx ->
            drawLine(color, Offset(size.width * (fx + 0.2f), c - size.height * 0.22f), Offset(size.width * fx, c), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color, Offset(size.width * fx, c), Offset(size.width * (fx + 0.2f), c + size.height * 0.22f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@androidx.compose.runtime.Composable
private fun DeleteGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.14f
        val inset = size.minDimension * 0.24f
        drawLine(color, Offset(inset, inset), Offset(size.width - inset, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width - inset, inset), Offset(inset, size.height - inset), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun CloseGlyph(color: Color, modifier: Modifier = Modifier) = DeleteGlyph(color, modifier)

@androidx.compose.runtime.Composable
private fun PencilGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.16f
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.85f), Offset(size.width * 0.7f, size.height * 0.2f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.7f, size.height * 0.2f), Offset(size.width * 0.88f, size.height * 0.08f), strokeWidth = stroke * 0.75f, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun TargetGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * 0.86f
        drawCircle(color, radius = r, center = c, style = Stroke(width = stroke))
        drawCircle(color, radius = r * 0.5f, center = c, style = Stroke(width = stroke))
        drawCircle(color, radius = r * 0.14f, center = c)
    }
}

@androidx.compose.runtime.Composable
private fun WarningGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.13f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawLine(color, Offset(c.x, size.height * 0.2f), Offset(c.x, size.height * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(color, radius = stroke * 0.6f, center = Offset(c.x, size.height * 0.79f))
    }
}

@androidx.compose.runtime.Composable
private fun GlobeGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.1f
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f * 0.82f
        drawCircle(color, radius = r, center = c, style = Stroke(width = stroke))
        drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawOval(color, topLeft = Offset(c.x - r * 0.42f, c.y - r), size = Size(r * 0.84f, r * 2f), style = Stroke(width = stroke))
    }
}

@androidx.compose.runtime.Composable
private fun FileGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.09f
        val bw = size.width * 0.64f; val bh = size.height * 0.82f
        val tl = Offset((size.width - bw) / 2f, (size.height - bh) / 2f)
        drawRoundRect(color, topLeft = tl, size = Size(bw, bh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.12f), style = Stroke(width = stroke))
        val inset = bw * 0.2f
        drawLine(color, Offset(tl.x + inset, tl.y + bh * 0.42f), Offset(tl.x + bw - inset, tl.y + bh * 0.42f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        drawLine(color, Offset(tl.x + inset, tl.y + bh * 0.64f), Offset(tl.x + bw - inset * 1.9f, tl.y + bh * 0.64f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun DownloadGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        val c = size.width / 2f
        drawLine(color, Offset(c, size.height * 0.18f), Offset(c, size.height * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c - size.width * 0.16f, size.height * 0.44f), Offset(c, size.height * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(c + size.width * 0.16f, size.height * 0.44f), Offset(c, size.height * 0.6f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.24f, size.height * 0.8f), Offset(size.width * 0.76f, size.height * 0.8f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@androidx.compose.runtime.Composable
private fun LightningGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(size.width * 0.55f, size.height * 0.1f)
            lineTo(size.width * 0.3f, size.height * 0.55f)
            lineTo(size.width * 0.5f, size.height * 0.55f)
            lineTo(size.width * 0.45f, size.height * 0.9f)
            lineTo(size.width * 0.72f, size.height * 0.42f)
            lineTo(size.width * 0.5f, size.height * 0.42f)
            close()
        }
        drawPath(path, color)
    }
}

@androidx.compose.runtime.Composable
private fun WandGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = size.minDimension * 0.12f
        // wand
        drawLine(color, Offset(size.width * 0.3f, size.height * 0.8f), Offset(size.width * 0.66f, size.height * 0.34f), strokeWidth = stroke, cap = StrokeCap.Round)
        // sparkle at tip
        val t = Offset(size.width * 0.72f, size.height * 0.24f)
        val s = size.minDimension * 0.14f
        drawLine(color, Offset(t.x - s, t.y), Offset(t.x + s, t.y), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        drawLine(color, Offset(t.x, t.y - s), Offset(t.x, t.y + s), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
    }
}

// ============================ File import ==============================

private fun MainActivity.readImportedFile(uri: Uri, onResult: (Result<String>) -> Unit) {
    lifecycleScope.launch(Dispatchers.IO) {
        val result = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_IMPORT_BYTES) {
                        "Import file is too large (maximum ${MAX_IMPORT_BYTES / (1024 * 1024)} MiB)"
                    }
                    out.write(buffer, 0, count)
                }
                out.toString(Charsets.UTF_8.name())
            } ?: error("Unable to open selected file")
        }
        lifecycleScope.launch(Dispatchers.Main) { onResult(result) }
    }
}

private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024
