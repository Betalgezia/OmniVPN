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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.ui.theme.OmniVpnStatusColors
import com.betalgezia.omnivpn.ui.theme.OmniVpnTheme
import com.betalgezia.omnivpn.ui.theme.ProtocolAmneziaWgDark
import com.betalgezia.omnivpn.ui.theme.ProtocolAmneziaWgLight
import com.betalgezia.omnivpn.ui.theme.ProtocolHysteria2Dark
import com.betalgezia.omnivpn.ui.theme.ProtocolHysteria2Light
import com.betalgezia.omnivpn.ui.theme.ProtocolTrojanDark
import com.betalgezia.omnivpn.ui.theme.ProtocolTrojanLight
import com.betalgezia.omnivpn.ui.theme.ProtocolVlessDark
import com.betalgezia.omnivpn.ui.theme.ProtocolVlessLight
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.TestMode
import com.betalgezia.omnivpn.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OmniVpnTheme { OmniVpnScreen() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun OmniVpnScreen(viewModel: MainViewModel = hiltViewModel()) {
        val nodes by viewModel.nodes.collectAsStateWithLifecycle()
        val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
        val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
        val busy by viewModel.busy.collectAsStateWithLifecycle()
        val message by viewModel.message.collectAsStateWithLifecycle()
        val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
        val searchingFastest by viewModel.searchingFastest.collectAsStateWithLifecycle()
        val nodeHealth by viewModel.nodeHealth.collectAsStateWithLifecycle()
        val canStartVpn = vpnState == VpnState.DISCONNECTED ||
            vpnState == VpnState.ERROR ||
            vpnState == VpnState.REVOKED
        // The main screen only ever shows servers that came from a subscription
        // - a manually pasted/imported config has sourceId == null and lives in
        // the Settings sheet instead (see the September 2026 redesign). This is
        // also exactly what connectFastest() scans, so what the hero button
        // promises to search matches what's actually on screen.
        val subscriptionNodes = nodes.filter { it.sourceId != null }

        var subscriptionUrlInput by remember { mutableStateOf("") }
        var input by remember { mutableStateOf("") }
        // Cloudflare WARP's usual anycast IP can itself end up blocked
        // independently of protocol-level DPI (reported by other
        // AmneziaWG+WARP users under the same kind of blocking); read
        // directly by the permission-launcher callback below, which fires
        // later, so whatever is typed here at "Get WARP" time is what's
        // used even if permission had to be requested first.
        var warpEndpointInput by remember { mutableStateOf("") }
        var confirmResetWarp by remember { mutableStateOf(false) }
        // Everything besides the hero button, the WARP pill and the
        // subscription server list - importing a raw config/file, WARP
        // troubleshooting tools, subscription management and configs
        // imported outside a subscription - lives in this sheet instead of
        // being permanently on screen.
        var sheetVisible by remember { mutableStateOf(false) }

        var pendingNode by remember { mutableStateOf<Node?>(null) }
        var pendingWarp by remember { mutableStateOf(false) }
        // Carries the "Reset WARP account" intent through the VPN-permission
        // detour below: forceNew=true discards the cached account and
        // registers a brand new one with Cloudflare (VpnController.startWarp)
        // instead of the normal "Get WARP" behaviour of reusing whatever is
        // cached. Kept separate from pendingWarp so a plain "Get WARP" click
        // never accidentally forces a new registration.
        var pendingWarpForceNew by remember { mutableStateOf(false) }
        // Carries the hero button's "connect to fastest" intent through the
        // same VPN-permission detour as a specific node or WARP.
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
            android.util.Log.i(
                TAG,
                "VPN permission result: code=${result.resultCode}, dataPresent=${result.data != null}, " +
                    "pendingNode=${node?.name}, pendingWarp=${warp}, pendingWarpForceNew=${warpForceNew}, " +
                    "pendingFastest=${fastest}"
            )
            try {
                if (result.resultCode != RESULT_OK) {
                    android.util.Log.w(TAG, "VPN permission denied/cancelled")
                    viewModel.showMessage("VPN permission denied")
                    return@rememberLauncherForActivityResult
                }

                // VpnService.prepare() does not require a result Intent payload.
                when {
                    node != null -> {
                        android.util.Log.i(TAG, "VPN permission granted -> connecting node=${node.name}")
                        viewModel.connect(node)
                    }
                    warp -> {
                        android.util.Log.i(TAG, "VPN permission granted -> starting WARP flow, forceNew=${warpForceNew}")
                        viewModel.startWarp(warpEndpointInput, forceNew = warpForceNew)
                    }
                    fastest -> {
                        android.util.Log.i(TAG, "VPN permission granted -> connecting to fastest server")
                        viewModel.connectFastest()
                    }
                    else -> {
                        android.util.Log.w(TAG, "VPN permission result received without pending action")
                        viewModel.showMessage("VPN permission returned without a pending action")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "VPN permission callback failed", t)
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
                    onFailure = {
                        viewModel.showMessage(
                            it.message ?: "Unable to read import file"
                        )
                    }
                )
            }
        }

        // Shared by the main screen's server list and the Settings sheet's
        // imported-configs list, so the VPN-permission detour is only written
        // once. afterLaunch runs regardless of whether permission had to be
        // requested first - only the sheet needs it, to dismiss itself.
        fun connectToNode(node: Node, afterLaunch: () -> Unit = {}) {
            android.util.Log.i(TAG, "Connect clicked: node=${node.name}")
            val intent = viewModel.prepareVpn()
            if (intent != null) {
                pendingNode = node
                permissionLauncher.launch(intent)
            } else {
                viewModel.connect(node)
            }
            afterLaunch()
        }

        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(message) {
            val text = message
            if (!text.isNullOrBlank()) {
                snackbarHostState.showSnackbar(text)
                viewModel.consumeMessage()
            }
        }

        val hero = heroPresentationFor(vpnState, searchingFastest, activeConnection)
        val onHeroClick: () -> Unit = {
            when {
                vpnState == VpnState.CONNECTED -> viewModel.stop()
                searchingFastest -> viewModel.cancelTests()
                vpnState == VpnState.CONNECTING -> viewModel.stop()
                else -> {
                    android.util.Log.i(TAG, "Hero connect clicked")
                    val intent = viewModel.prepareVpn()
                    if (intent != null) {
                        pendingFastest = true
                        permissionLauncher.launch(intent)
                    } else {
                        viewModel.connectFastest()
                    }
                }
            }
        }
        val onWarpClick: () -> Unit = {
            android.util.Log.i(TAG, "Get WARP (pill) clicked")
            val intent = viewModel.prepareVpn()
            if (intent != null) {
                pendingWarp = true
                pendingWarpForceNew = false
                permissionLauncher.launch(intent)
            } else {
                viewModel.startWarp()
            }
        }

        val clipboardManager = LocalClipboardManager.current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("OmniVPN") },
                    actions = {
                        TextButton(onClick = { sheetVisible = true }) { Text("Settings") }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))

                HeroConnectButton(
                    accent = hero.accent,
                    busy = hero.busy,
                    title = hero.title,
                    subtitle = hero.subtitle,
                    onClick = onHeroClick
                )

                Spacer(Modifier.height(20.dp))

                WarpPillButton(
                    enabled = !busy && canStartVpn,
                    onClick = onWarpClick,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(18.dp))

                AddSubscriptionRow(
                    value = subscriptionUrlInput,
                    onValueChange = { subscriptionUrlInput = it },
                    enabled = !busy,
                    onAdd = {
                        viewModel.import(subscriptionUrlInput)
                        subscriptionUrlInput = ""
                    },
                    onPasteFromClipboard = {
                        clipboardManager.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            subscriptionUrlInput = it
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(18.dp))

                Text(
                    "Servers (${subscriptionNodes.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                )

                if (subscriptionNodes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No subscription servers yet.\nAdd a subscription above to see it here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 20.dp)
                    ) {
                        items(subscriptionNodes, key = { it.id }) { node ->
                            ServerRow(
                                node = node,
                                health = nodeHealth[node.id] ?: NodeHealth.Unknown,
                                connectable = !busy && canStartVpn,
                                onConnect = { connectToNode(node) },
                                onTest = { viewModel.checkNode(node) },
                                onDelete = { viewModel.deleteNode(node) }
                            )
                        }
                    }
                }
            }
        }

        if (sheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { sheetVisible = false },
                sheetState = rememberModalBottomSheetState()
            ) {
                SecondarySheetContent(
                    viewModel = viewModel,
                    input = input,
                    onInputChange = { input = it },
                    onImport = { viewModel.import(input) },
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
                        android.util.Log.i(TAG, "Get WARP (override) clicked, endpointOverride=${warpEndpointInput.isNotBlank()}")
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
                    onConnectNode = { node -> connectToNode(node) { sheetVisible = false } }
                )
            }
        }

        if (confirmResetWarp) {
            AlertDialog(
                onDismissRequest = { confirmResetWarp = false },
                title = { Text("Reset WARP account?") },
                text = {
                    Text(
                        "This discards the saved WARP account and registers a brand " +
                            "new one with Cloudflare. Normal \"Get WARP\" clicks always " +
                            "reuse the same saved account, so only do this if WARP stops " +
                            "connecting and a fresh registration might help."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmResetWarp = false
                        android.util.Log.i(TAG, "Reset WARP confirmed, endpointOverride=${warpEndpointInput.isNotBlank()}")
                        val intent = viewModel.prepareVpn()
                        if (intent != null) {
                            pendingWarp = true
                            pendingWarpForceNew = true
                            permissionLauncher.launch(intent)
                        } else {
                            viewModel.startWarp(warpEndpointInput, forceNew = true)
                        }
                    }) { Text("Reset") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmResetWarp = false }) { Text("Cancel") }
                }
            )
        }
    }
}

/**
 * The single dominant control on the main screen: tap to connect to the
 * fastest reachable subscription server (idle/error/revoked), tap to cancel
 * (while searching) or tap to disconnect (while connecting/connected) - see
 * heroPresentationFor for exactly which of those a given state maps to.
 * Colour and the pulsing glow are the only things that change between
 * states; the label underneath carries the rest so the button itself never
 * needs more than one glyph.
 */
@androidx.compose.runtime.Composable
private fun HeroConnectButton(
    accent: Color,
    busy: Boolean,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "heroPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heroPulseScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.22f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "heroGlowAlpha"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            // Soft outer glow: a larger, low-alpha radial gradient behind the
            // real button, pulsing gently while busy. A layered gradient
            // rather than Modifier.shadow()'s colour params - it reads as a
            // bloom, not a drop shadow, and needs no colour-shadow API.
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .scale(if (busy) pulse else 1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = if (busy) glowAlpha else 0.28f),
                                accent.copy(alpha = 0f)
                            )
                        )
                    )
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(3.dp, accent, CircleShape)
                    .clickable(onClick = onClick)
            ) {
                if (busy) {
                    CircularProgressIndicator(color = accent, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
                } else {
                    PowerGlyph(color = accent, modifier = Modifier.size(56.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/**
 * Drawn rather than a Unicode glyph (compare the ▾/▸ triangles below, which
 * are plain text): this is the single most prominent element on the main
 * screen, and minSdk 24 plus emoji-presentation quirks in later Unicode
 * symbol blocks make a text character a real risk of rendering as the wrong
 * (or a missing) glyph on some device/font combination. A drawn circle-with-
 * gap-and-stroke is the standard "power" icon shape and renders identically
 * everywhere.
 */
@androidx.compose.runtime.Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.14f
        val radius = size.minDimension / 2f * 0.58f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawArc(
            color = color,
            startAngle = -55f,
            sweepAngle = 290f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(center.x, center.y - radius * 1.3f),
            end = Offset(center.x, center.y - radius * 0.1f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

private data class HeroPresentation(
    val accent: Color,
    val title: String,
    val subtitle: String?,
    val busy: Boolean
)

/** Maps live VPN/search state to what the hero button should show. */
@androidx.compose.runtime.Composable
private fun heroPresentationFor(
    vpnState: VpnState,
    searching: Boolean,
    activeConnection: ActiveConnection
): HeroPresentation {
    val activeLabel = when (activeConnection) {
        is ActiveConnection.Server -> activeConnection.node.name
        ActiveConnection.Warp -> "Cloudflare WARP"
        ActiveConnection.None -> null
    }
    val primary = MaterialTheme.colorScheme.primary
    val connected = OmniVpnStatusColors.connected
    val warning = OmniVpnStatusColors.warning
    val error = OmniVpnStatusColors.error
    return when {
        searching -> HeroPresentation(
            accent = primary,
            title = "Searching…",
            subtitle = "Testing subscription servers",
            busy = true
        )
        vpnState == VpnState.CONNECTING -> HeroPresentation(
            accent = primary,
            title = "Connecting…",
            subtitle = activeLabel,
            busy = true
        )
        vpnState == VpnState.CONNECTED -> HeroPresentation(
            accent = connected,
            title = "Connected",
            subtitle = activeLabel ?: "Secure connection active",
            busy = false
        )
        vpnState == VpnState.ERROR -> HeroPresentation(
            accent = error,
            title = "Connection error",
            subtitle = "Tap to try again",
            busy = false
        )
        vpnState == VpnState.REVOKED -> HeroPresentation(
            accent = warning,
            title = "Permission revoked",
            subtitle = "Tap to reconnect",
            busy = false
        )
        else -> HeroPresentation(
            accent = primary,
            title = "Connect",
            subtitle = "Fastest available server",
            busy = false
        )
    }
}

/**
 * Second most prominent control, deliberately its own colour (Cloudflare-
 * adjacent orange) rather than a second copy of the hero button's accent -
 * "Get WARP" is a distinct one-tap path, not a variant of "connect to the
 * fastest server", and needs to read as its own thing at a glance.
 */
@androidx.compose.runtime.Composable
private fun WarpPillButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val warp = OmniVpnStatusColors.warp
    val contentAlpha = if (enabled) 1f else 0.4f
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        color = warp.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, warp.copy(alpha = contentAlpha)),
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
            Text("Get WARP", style = MaterialTheme.typography.titleMedium, color = warp.copy(alpha = contentAlpha))
        }
    }
}

/**
 * Compact "add a subscription" control, kept to the one thing the main
 * screen wants: a subscription URL. Pasting a raw config or picking a file
 * both moved to the Settings sheet (see SecondarySheetContent) - those are
 * one-off/power-user actions, not the everyday "I got a new subscription
 * link" case this is for.
 */
@androidx.compose.runtime.Composable
private fun AddSubscriptionRow(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onAdd: () -> Unit,
    onPasteFromClipboard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            label = { Text("Subscription URL") },
            trailingIcon = {
                Text(
                    "Paste",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable(enabled = enabled, onClick = onPasteFromClipboard)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onAdd,
            enabled = enabled && value.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add subscription") }
    }
}

/**
 * One distinct hue per protocol so the server list stays scannable at a
 * glance - the badge is the point, not any deeper meaning behind the colour
 * choice. WARP is included for completeness even though a subscription-
 * sourced node can never actually carry it (see ConfigParser/
 * SubscriptionParser - only WarpAccount.toNode() ever produces Protocol.WARP).
 */
@androidx.compose.runtime.Composable
private fun protocolColor(protocol: Protocol): Color {
    val dark = isSystemInDarkTheme()
    return when (protocol) {
        Protocol.VLESS -> if (dark) ProtocolVlessDark else ProtocolVlessLight
        Protocol.TROJAN -> if (dark) ProtocolTrojanDark else ProtocolTrojanLight
        Protocol.HYSTERIA2 -> if (dark) ProtocolHysteria2Dark else ProtocolHysteria2Light
        Protocol.AMNEZIAWG -> if (dark) ProtocolAmneziaWgDark else ProtocolAmneziaWgLight
        Protocol.WARP -> OmniVpnStatusColors.warp
    }
}

@androidx.compose.runtime.Composable
private fun ProtocolBadge(protocol: Protocol, modifier: Modifier = Modifier) {
    val color = protocolColor(protocol)
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.16f), modifier = modifier) {
        Text(
            protocol.name,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/** Compact, tappable-to-retest health indicator used inside a ServerRow. */
@androidx.compose.runtime.Composable
private fun NodeHealthChip(health: NodeHealth, onTest: () -> Unit, modifier: Modifier = Modifier) {
    when (health) {
        is NodeHealth.Unknown -> Text(
            "Test",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier.clickable(onClick = onTest).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        is NodeHealth.Checking -> Box(modifier = modifier.padding(4.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        }
        is NodeHealth.Reachable -> Text(
            "${health.latencyMs} ms",
            style = MaterialTheme.typography.labelSmall,
            color = OmniVpnStatusColors.connected,
            modifier = modifier.clickable(onClick = onTest).padding(horizontal = 8.dp, vertical = 4.dp)
        )
        is NodeHealth.Unreachable -> Text(
            "Offline",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.clickable(onClick = onTest).padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * Wraps [content] with a red "Delete" backdrop revealed by dragging it left,
 * confirmed with the same AlertDialog pattern the app already used for
 * destructive actions. A hand-rolled drag (Modifier.draggable +
 * rememberDraggableState + the standalone animate()) rather than Material3's
 * SwipeToDismissBox: that API's exact shape has shifted across Compose
 * versions and this sticks to lower-level primitives that have been stable
 * for a long time, which matters with no compiler available to check against.
 */
@androidx.compose.runtime.Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @androidx.compose.runtime.Composable () -> Unit
) {
    val density = LocalDensity.current
    val maxSwipePx = remember(density) { with(density) { 120.dp.toPx() } }
    val deleteThresholdPx = remember(density) { with(density) { 88.dp.toPx() } }
    var offsetX by remember { mutableStateOf(0f) }
    var confirmDelete by remember { mutableStateOf(false) }

    val draggableState = rememberDraggableState { delta ->
        offsetX = (offsetX + delta).coerceIn(-maxSwipePx, 0f)
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.errorContainer),
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                "Delete",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 28.dp)
            )
        }
        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .draggable(
                    state = draggableState,
                    orientation = Orientation.Horizontal,
                    onDragStopped = {
                        if (offsetX < -deleteThresholdPx) {
                            confirmDelete = true
                        }
                        animate(offsetX, 0f, animationSpec = tween(200)) { value, _ -> offsetX = value }
                    }
                )
        ) {
            content()
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove server?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * One server: tap anywhere to connect (no separate Connect button), swipe
 * left to delete (no separate Delete button) - see the September 2026
 * redesign. Used for both the main screen's subscription list and the
 * Settings sheet's imported-configs list, so both look and behave the same.
 */
@androidx.compose.runtime.Composable
private fun ServerRow(
    node: Node,
    health: NodeHealth,
    connectable: Boolean,
    onConnect: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    SwipeToDeleteRow(onDelete = onDelete, modifier = modifier.fillMaxWidth()) {
        Surface(
            onClick = onConnect,
            enabled = connectable,
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                ProtocolBadge(node.protocol)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(node.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${node.server}:${node.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.width(8.dp))
                NodeHealthChip(health = health, onTest = onTest)
            }
        }
    }
}

/**
 * Everything demoted off the main screen by the redesign: importing a raw
 * config/file, WARP troubleshooting tools, subscription management and
 * configs imported outside a subscription. Reads straight from [viewModel]
 * like SubscriptionRow already did rather than having a dozen values
 * threaded in from the caller; only the bits that need the Activity's
 * permission-launcher/file-picker/clipboard are passed in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun SecondarySheetContent(
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
    onConnectNode: (Node) -> Unit
) {
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val nodeHealth by viewModel.nodeHealth.collectAsStateWithLifecycle()
    val testMode by viewModel.testMode.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val canStartVpn = vpnState == VpnState.DISCONNECTED ||
        vpnState == VpnState.ERROR ||
        vpnState == VpnState.REVOKED
    // Configs added by pasting text or picking a file rather than through a
    // subscription - the main screen's list only shows subscription servers
    // (sourceId != null), so these need a home too.
    val importedNodes = nodes.filter { it.sourceId == null }

    var importExpanded by remember { mutableStateOf(false) }
    var warpToolsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        if (busy) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Working…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    // Also reachable from here for a search/test started before
                    // the sheet was opened - not just the hero button itself.
                    TextButton(onClick = { viewModel.cancelTests() }) { Text("Cancel") }
                }
            }
        }

        item {
            CollapsibleSection(
                title = "Import config or file",
                expanded = importExpanded,
                onToggle = { importExpanded = !importExpanded }
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Paste a config") },
                    placeholder = { Text("vless://… / trojan://… / JSON / YAML") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onImport, enabled = !busy && input.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Import") }
                    OutlinedButton(onClick = onPickFile, enabled = !busy, modifier = Modifier.weight(1f)) { Text("File") }
                }
            }
        }

        item {
            CollapsibleSection(
                title = "Cloudflare WARP tools",
                expanded = warpToolsExpanded,
                onToggle = { warpToolsExpanded = !warpToolsExpanded }
            ) {
                OutlinedTextField(
                    value = warpEndpointInput,
                    onValueChange = onWarpEndpointChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Endpoint override (optional)") },
                    placeholder = { Text("162.159.192.1:2408") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        enabled = !busy && canStartVpn,
                        onClick = onWarpWithOverride,
                        modifier = Modifier.weight(1f)
                    ) { Text("Connect with override") }
                    OutlinedButton(
                        enabled = !busy && canStartVpn,
                        onClick = onFindWarpEndpoint,
                        modifier = Modifier.weight(1f)
                    ) { Text("Find endpoint") }
                }
                TextButton(enabled = !busy && canStartVpn, onClick = onResetWarpRequested) { Text("Reset WARP account") }
            }
        }

        if (subscriptions.isNotEmpty()) {
            item {
                Text("Subscriptions (${subscriptions.size})", style = MaterialTheme.typography.titleMedium)
            }
            items(subscriptions, key = { it.id }) { subscription ->
                SubscriptionRow(
                    subscription,
                    viewModel,
                    enabled = !busy,
                    testEnabled = !busy && canStartVpn,
                    onTestAll = { viewModel.checkSubscriptionNodes(subscription) }
                )
            }
        }

        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (testMode == TestMode.QUICK) {
                        "Manual test: port only - a pass doesn't prove the proxy works"
                    } else {
                        "Manual test: real request through the server - slow but honest"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(
                    enabled = !busy,
                    onClick = { viewModel.setTestMode(if (testMode == TestMode.QUICK) TestMode.FULL else TestMode.QUICK) }
                ) { Text(if (testMode == TestMode.QUICK) "Quick" else "Full") }
            }
        }

        item {
            Text("Imported configs (${importedNodes.size})", style = MaterialTheme.typography.titleMedium)
        }
        if (importedNodes.isEmpty()) {
            item {
                Text(
                    "Configs pasted or imported from a file (not part of a subscription) show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(importedNodes, key = { it.id }) { node ->
                ServerRow(
                    node = node,
                    health = nodeHealth[node.id] ?: NodeHealth.Unknown,
                    connectable = !busy && canStartVpn,
                    onConnect = { onConnectNode(node) },
                    onTest = { viewModel.checkNode(node) },
                    onDelete = { viewModel.deleteNode(node) }
                )
            }
        }
    }
}

// Plain text disclosure triangle rather than Icons.Default.ExpandMore/Less: pulling
// in the material-icons artifact for one glyph isn't worth a new dependency.
@androidx.compose.runtime.Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @androidx.compose.runtime.Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        if (expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        }
    }
}

@androidx.compose.runtime.Composable
private fun SubscriptionRow(
    subscription: Subscription,
    viewModel: MainViewModel,
    enabled: Boolean,
    testEnabled: Boolean,
    onTestAll: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(subscription.name, style = MaterialTheme.typography.titleMedium)
            Text(
                subscription.url,
                maxLines = 1,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.refresh(subscription) },
                    enabled = enabled
                ) { Text("Refresh") }
                OutlinedButton(
                    onClick = onTestAll,
                    enabled = testEnabled
                ) { Text("Test all") }
                OutlinedButton(
                    onClick = { viewModel.delete(subscription) },
                    enabled = enabled
                ) { Text("Delete") }
            }
        }
    }
}

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
