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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
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
        setContent { OmniVpnScreen() }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun OmniVpnScreen(viewModel: MainViewModel = hiltViewModel()) {
        val nodes by viewModel.nodes.collectAsStateWithLifecycle()
        val nodeHealth by viewModel.nodeHealth.collectAsStateWithLifecycle()
        val testMode by viewModel.testMode.collectAsStateWithLifecycle()
        val subscriptions by viewModel.subscriptions.collectAsState()
        val vpnState by viewModel.vpnState.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val message by viewModel.message.collectAsState()
        val canStartVpn = vpnState == VpnState.DISCONNECTED ||
            vpnState == VpnState.ERROR ||
            vpnState == VpnState.REVOKED
        var input by remember { mutableStateOf("") }
        // Collapsed by default: the add-server and WARP controls are used
        // occasionally, but were previously always-expanded and pushed the
        // server list (the thing actually looked at most often) off the
        // bottom of the screen with only a sliver of the first card visible.
        var addServerExpanded by remember { mutableStateOf(false) }
        var warpExpanded by remember { mutableStateOf(false) }
        var subscriptionsExpanded by remember { mutableStateOf(false) }
        var pendingNode by remember { mutableStateOf<Node?>(null) }
        var pendingWarp by remember { mutableStateOf(false) }
        // Carries the "Reset WARP account" intent through the VPN-permission
        // detour below: forceNew=true discards the cached account and
        // registers a brand new one with Cloudflare (VpnController.startWarp)
        // instead of the normal "Get WARP" behaviour of reusing whatever is
        // cached. Kept separate from pendingWarp so a plain "Get WARP" click
        // never accidentally forces a new registration.
        var pendingWarpForceNew by remember { mutableStateOf(false) }
        var confirmResetWarp by remember { mutableStateOf(false) }
        // Cloudflare WARP's usual anycast IP can itself end up blocked
        // independently of protocol-level DPI (reported by other
        // AmneziaWG+WARP users under the same kind of blocking); read
        // directly by the permission-launcher callback below, which fires
        // later, so whatever is typed here at "Get WARP" time is what's
        // used even if permission had to be requested first.
        var warpEndpointInput by remember { mutableStateOf("") }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val node = pendingNode
            val warp = pendingWarp
            val warpForceNew = pendingWarpForceNew
            pendingNode = null
            pendingWarp = false
            pendingWarpForceNew = false
            android.util.Log.i(
                TAG,
                "VPN permission result: code=${result.resultCode}, dataPresent=${result.data != null}, " +
                    "pendingNode=${node?.name}, pendingWarp=${warp}, pendingWarpForceNew=${warpForceNew}"
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
        Scaffold(topBar = { TopAppBar(title = { Text("OmniVPN") }) }) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("VPN: ${vpnState.name}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        // Testing a long server list can run for minutes now that
                        // timeouts get a second, slower attempt - there has to be
                        // a way to stop without force-quitting the app.
                        TextButton(onClick = { viewModel.cancelTests() }) { Text("Cancel") }
                    }
                    if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
                        OutlinedButton(onClick = viewModel::stop) { Text("Disconnect") }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }

                CollapsibleSection(
                    title = "Add server",
                    expanded = addServerExpanded,
                    onToggle = { addServerExpanded = !addServerExpanded }
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        label = { Text("Subscription URL or config") },
                        placeholder = { Text("https://… / vless://… / JSON / YAML") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.import(input) }, enabled = !busy && input.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Import") }
                        OutlinedButton(
                            onClick = {
                                fileLauncher.launch(
                                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                )
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) { Text("File") }
                    }
                }

                CollapsibleSection(
                    title = "Cloudflare WARP",
                    expanded = warpExpanded,
                    onToggle = { warpExpanded = !warpExpanded }
                ) {
                    OutlinedTextField(
                        value = warpEndpointInput,
                        onValueChange = { warpEndpointInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("WARP endpoint override (optional)") },
                        placeholder = { Text("162.159.192.1:2408") }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            enabled = !busy && canStartVpn,
                            onClick = {
                                android.util.Log.i(TAG, "Get WARP clicked, endpointOverride=${warpEndpointInput.isNotBlank()}")
                                val intent = viewModel.prepareVpn()
                                if (intent != null) {
                                    android.util.Log.i(TAG, "Get WARP: launching VPN permission activity")
                                    pendingWarp = true
                                    pendingWarpForceNew = false
                                    permissionLauncher.launch(intent)
                                } else {
                                    android.util.Log.i(TAG, "Get WARP: VPN permission already granted")
                                    viewModel.startWarp(warpEndpointInput)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Get WARP") }
                        OutlinedButton(
                            enabled = !busy && canStartVpn,
                            onClick = { confirmResetWarp = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reset WARP") }
                    }
                    OutlinedButton(
                        enabled = !busy && canStartVpn,
                        onClick = { viewModel.findWarpEndpoint() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Find working endpoint") }
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
                                    android.util.Log.i(TAG, "Reset WARP: launching VPN permission activity")
                                    pendingWarp = true
                                    pendingWarpForceNew = true
                                    permissionLauncher.launch(intent)
                                } else {
                                    android.util.Log.i(TAG, "Reset WARP: VPN permission already granted")
                                    viewModel.startWarp(warpEndpointInput, forceNew = true)
                                }
                            }) { Text("Reset") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmResetWarp = false }) { Text("Cancel") }
                        }
                    )
                }

                if (subscriptions.isNotEmpty()) {
                    CollapsibleSection(
                        title = "Subscriptions (${subscriptions.size})",
                        expanded = subscriptionsExpanded,
                        onToggle = { subscriptionsExpanded = !subscriptionsExpanded }
                    ) {
                        subscriptions.forEach { subscription ->
                            SubscriptionRow(
                                subscription,
                                viewModel,
                                enabled = !busy,
                                testEnabled = !busy && canStartVpn,
                                onTestAll = { viewModel.checkSubscriptionNodes(subscription) }
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Servers (${nodes.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (!canStartVpn) {
                        Text(
                            "Disconnect to test",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        enabled = !busy && canStartVpn && nodes.isNotEmpty(),
                        onClick = { viewModel.checkAllNodes() }
                    ) { Text("Check all") }
                }
                Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
                        onClick = {
                            viewModel.setTestMode(
                                if (testMode == TestMode.QUICK) TestMode.FULL else TestMode.QUICK
                            )
                        }
                    ) { Text(if (testMode == TestMode.QUICK) "Quick" else "Full") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(nodes, key = { it.id }) { node ->
                        NodeCard(
                            node = node,
                            health = nodeHealth[node.id] ?: NodeHealth.Unknown,
                            enabled = !busy && canStartVpn,
                            deleteEnabled = !busy,
                            onTest = { viewModel.checkNode(node) },
                            onConnect = {
                                android.util.Log.i(TAG, "Connect clicked: node=${node.name}")
                                val intent = viewModel.prepareVpn()
                                if (intent != null) {
                                    android.util.Log.i(TAG, "Connect: launching VPN permission activity")
                                    pendingNode = node
                                    permissionLauncher.launch(intent)
                                } else {
                                    android.util.Log.i(TAG, "Connect: VPN permission already granted")
                                    viewModel.connect(node)
                                }
                            },
                            onDelete = {
                                android.util.Log.i(TAG, "Delete clicked: node=${node.name}")
                                viewModel.deleteNode(node)
                            }
                        )
                    }
                }
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
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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
        is NodeHealth.Reachable -> "● ${health.latencyMs} ms" to Color(0xFF2E7D32)
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
