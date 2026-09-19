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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Subscription
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
        val subscriptions by viewModel.subscriptions.collectAsState()
        val vpnState by viewModel.vpnState.collectAsState()
        val busy by viewModel.busy.collectAsState()
        val message by viewModel.message.collectAsState()
        val canStartVpn = vpnState == VpnState.DISCONNECTED ||
            vpnState == VpnState.ERROR ||
            vpnState == VpnState.REVOKED
        var input by remember { mutableStateOf("") }
        var pendingNode by remember { mutableStateOf<Node?>(null) }
        var pendingWarp by remember { mutableStateOf(false) }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val node = pendingNode
            val warp = pendingWarp
            pendingNode = null
            pendingWarp = false
            android.util.Log.i(
                TAG,
                "VPN permission result: code=${result.resultCode}, dataPresent=${result.data != null}, " +
                    "pendingNode=${node?.name}, pendingWarp=${warp}"
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
                        android.util.Log.i(TAG, "VPN permission granted -> starting WARP flow")
                        viewModel.startWarp()
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
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("VPN: ${vpnState.name}", style = MaterialTheme.typography.titleMedium)

                if (vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING) {
                    OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) { Text("Disconnect") }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
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
                    OutlinedButton(
                        enabled = !busy && canStartVpn,
                        onClick = {
                            android.util.Log.i(TAG, "Get WARP clicked")
                            val intent = viewModel.prepareVpn()
                            if (intent != null) {
                                android.util.Log.i(TAG, "Get WARP: launching VPN permission activity")
                                pendingWarp = true
                                permissionLauncher.launch(intent)
                            } else {
                                android.util.Log.i(TAG, "Get WARP: VPN permission already granted")
                                viewModel.startWarp()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Get WARP") }
                }

                if (busy) CircularProgressIndicator()
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

                if (subscriptions.isNotEmpty()) {
                    Text("Subscriptions", style = MaterialTheme.typography.titleLarge)
                    subscriptions.forEach { subscription ->
                        SubscriptionRow(subscription, viewModel, enabled = !busy)
                    }
                }

                Text("Servers", style = MaterialTheme.typography.titleLarge)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(nodes, key = { it.id }) { node ->
                        NodeCard(
                            node = node,
                            enabled = !busy && canStartVpn
                        ) {
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
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun SubscriptionRow(
    subscription: Subscription,
    viewModel: MainViewModel,
    enabled: Boolean
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
                    onClick = { viewModel.delete(subscription) },
                    enabled = enabled
                ) { Text("Delete") }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun NodeCard(node: Node, enabled: Boolean, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(node.name, style = MaterialTheme.typography.titleMedium)
                Text("${node.protocol} • ${node.server}:${node.port}")
            }
            Button(onClick = onConnect, enabled = enabled) { Text("Connect") }
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