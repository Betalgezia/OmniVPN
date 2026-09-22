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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.ui.theme.OmniVpnStatusColors
import com.betalgezia.omnivpn.ui.theme.OmniVpnTheme
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.TestMode
import com.betalgezia.omnivpn.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

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
        val canStartVpn = vpnState == VpnState.DISCONNECTED ||
            vpnState == VpnState.ERROR ||
            vpnState == VpnState.REVOKED

        var input by remember { mutableStateOf("") }
        // Cloudflare WARP's usual anycast IP can itself end up blocked
        // independently of protocol-level DPI (reported by other
        // AmneziaWG+WARP users under the same kind of blocking); read
        // directly by the permission-launcher callback below, which fires
        // later, so whatever is typed here at "Get WARP" time is what's
        // used even if permission had to be requested first.
        var warpEndpointInput by remember { mutableStateOf("") }
        var confirmResetWarp by remember { mutableStateOf(false) }
        // Everything besides the hero button and the WARP pill - add server,
        // WARP troubleshooting tools, subscriptions and the full server list -
        // lives in this sheet instead of being permanently on screen. See the
        // September 2026 redesign: the flat always-expanded layout pushed the
        // one thing worth looking at most (the server list) off the bottom of
        // the screen.
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("OmniVPN") },
                    actions = {
                        TextButton(onClick = { sheetVisible = true }) { Text("Servers") }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                HeroConnectButton(
                    accent = hero.accent,
                    busy = hero.busy,
                    title = hero.title,
                    subtitle = hero.subtitle,
                    onClick = onHeroClick
                )

                Spacer(Modifier.height(36.dp))

                WarpPillButton(
                    enabled = !busy && canStartVpn,
                    onClick = onWarpClick,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))

                ServersSummaryRow(
                    serverCount = nodes.size,
                    subscriptionCount = subscriptions.size,
                    onClick = { sheetVisible = true },
                    modifier = Modifier.fillMaxWidth()
                )
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
                    onConnectNode = { node ->
                        android.util.Log.i(TAG, "Connect clicked: node=${node.name}")
                        val intent = viewModel.prepareVpn()
                        if (intent != null) {
                            pendingNode = node
                            permissionLauncher.launch(intent)
                        } else {
                            viewModel.connect(node)
                        }
                        sheetVisible = false
                    }
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
 * fastest reachable server (idle/error/revoked), tap to cancel (while
 * searching) or tap to disconnect (while connecting/connected) - see
 * heroPresentationFor for exactly which of those a given state maps to.
 * Colour and the pulsing glow are the only things that change between
 * states; the label underneath carries the rest so the button itself
 * never needs more than one glyph.
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
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(224.dp)) {
            // Soft outer glow: a larger, low-alpha radial gradient behind the
            // real button, pulsing gently while busy. A layered gradient
            // rather than Modifier.shadow()'s colour params - it reads as a
            // bloom, not a drop shadow, and needs no colour-shadow API.
            Box(
                modifier = Modifier
                    .size(224.dp)
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
                    .size(184.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(3.dp, accent, CircleShape)
                    .clickable(onClick = onClick)
            ) {
                if (busy) {
                    CircularProgressIndicator(color = accent, strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                } else {
                    PowerGlyph(color = accent, modifier = Modifier.size(64.dp))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
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
            subtitle = "Testing servers for the best connection",
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

/** Tappable at-a-glance summary; also opens the "everything else" sheet. */
@androidx.compose.runtime.Composable
private fun ServersSummaryRow(
    serverCount: Int,
    subscriptionCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Servers & subscriptions", style = MaterialTheme.typography.titleSmall)
                Text(
                    "$serverCount servers · $subscriptionCount subscriptions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Everything demoted off the main screen by the redesign: add server, WARP
 * troubleshooting tools, subscriptions and the full server list. Reads
 * straight from [viewModel] like SubscriptionRow/NodeCard already did rather
 * than having ~15 values threaded in from the caller; only the bits that
 * need the Activity's permission-launcher/file-picker are passed in.
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

    var addServerExpanded by remember { mutableStateOf(false) }
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
            Text("Servers & subscriptions", style = MaterialTheme.typography.titleLarge)
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
                title = "Add server",
                expanded = addServerExpanded,
                onToggle = { addServerExpanded = !addServerExpanded }
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Subscription URL or config") },
                    placeholder = { Text("https://… / vless://… / JSON / YAML") }
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
                    "Servers (${nodes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    enabled = !busy && canStartVpn && nodes.isNotEmpty(),
                    onClick = { viewModel.checkAllNodes() }
                ) { Text("Check all") }
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
                        "Quick test: port only - a pass doesn't prove the proxy works"
                    } else {
                        "Full test: real request through the server - slow but honest"
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

        if (nodes.isEmpty()) {
            item {
                Text(
                    "No servers yet. Add a subscription or import a config above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(nodes, key = { it.id }) { node ->
                NodeCard(
                    node = node,
                    health = nodeHealth[node.id] ?: NodeHealth.Unknown,
                    enabled = !busy && canStartVpn,
                    deleteEnabled = !busy,
                    onTest = { viewModel.checkNode(node) },
                    onConnect = { onConnectNode(node) },
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(subscription.name, style = MaterialTheme.typography.titleMedium)
            Text(subscription.url, maxLines = 1)
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

@androidx.compose.runtime.Composable
private fun NodeCard(
    node: Node,
    health: NodeHealth,
    enabled: Boolean,
    deleteEnabled: Boolean,
    onTest: () -> Unit,
    onConnect: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(node.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                "${node.protocol} • ${node.server}:${node.port}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            NodeHealthLabel(health)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (node.protocol != Protocol.WARP) {
                    OutlinedButton(
                        onClick = onTest,
                        enabled = enabled && health != NodeHealth.Checking,
                        modifier = Modifier.weight(1f)
                    ) { Text("Test") }
                }
                OutlinedButton(onClick = { confirmDelete = true }, enabled = deleteEnabled, modifier = Modifier.weight(1f)) { Text("Delete") }
                Button(onClick = onConnect, enabled = enabled, modifier = Modifier.weight(1f)) { Text("Connect") }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove server?") },
            text = { Text("\"${node.name}\" will be removed from your server list.") },
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

@androidx.compose.runtime.Composable
private fun NodeHealthLabel(health: NodeHealth) {
    val (text, color) = when (health) {
        is NodeHealth.Unknown -> return
        is NodeHealth.Checking -> "Testing…" to MaterialTheme.colorScheme.onSurfaceVariant
        is NodeHealth.Reachable -> "● ${health.latencyMs} ms" to OmniVpnStatusColors.connected
        is NodeHealth.Unreachable -> "● Unreachable: ${health.reason}" to MaterialTheme.colorScheme.error
    }
    Text(text, color = color, style = MaterialTheme.typography.bodySmall)
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
