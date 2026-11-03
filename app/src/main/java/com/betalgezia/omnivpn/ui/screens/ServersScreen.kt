package com.betalgezia.omnivpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.ALL_SERVERS
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.data.model.Node
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.ui.VpnActions
import com.betalgezia.omnivpn.ui.organic.ButtonKind
import com.betalgezia.omnivpn.ui.organic.LatencyBars
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicCard
import com.betalgezia.omnivpn.ui.organic.OrganicDialog
import com.betalgezia.omnivpn.ui.organic.OrganicIconButton
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.ProtocolTag
import com.betalgezia.omnivpn.ui.organic.latencyLook
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.TestMode
import com.betalgezia.omnivpn.vpn.VpnState
import kotlinx.coroutines.delay

// Organic «Серверы» tab (handoff README §2): header with «Изменить» and «+»,
// «Проверить все» / «Обновить», the VPN-on note, subscription groups that
// collapse, server rows with the latency chip, and «Импортированные».

@Composable
fun ServersScreen(
    viewModel: MainViewModel,
    actions: VpnActions,
    onAddServer: () -> Unit,
    onOpenHome: () -> Unit
) {
    val c = Organic.colors
    val allNodes by viewModel.nodes.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val health by viewModel.nodeHealth.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val active by viewModel.activeConnection.collectAsStateWithLifecycle()
    val searching by viewModel.searchingFastest.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshingSubscriptions.collectAsStateWithLifecycle()
    val autoUpdate by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateMinutes by viewModel.autoUpdateIntervalMinutes.collectAsStateWithLifecycle()
    val collapsed by viewModel.collapsedSubscriptions.collectAsStateWithLifecycle()
    val groupTest by viewModel.subscriptionTest.collectAsStateWithLifecycle()

    var editing by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Node?>(null) }

    // «обновлено N мин назад» moves on by itself.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }

    val nodes = remember(allNodes) { allNodes.filter { it.protocol != Protocol.WARP } }
    val imported = remember(nodes) { nodes.filter { it.sourceId == null } }
    val canTest = vpnState == VpnState.DISCONNECTED || vpnState == VpnState.ERROR || vpnState == VpnState.REVOKED
    val live = vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING
    val activeId = (active as? ActiveConnection.Server)?.node?.id
    val testingAll = groupTest?.first == ALL_SERVERS
    LaunchedEffect(nodes.isEmpty()) { if (nodes.isEmpty()) editing = false }

    val onRowTap: (Node) -> Unit = { node ->
        when {
            // A search or a start is under way; a second start would race it.
            searching || vpnState == VpnState.CONNECTING || busy -> Unit
            node.id == activeId && vpnState == VpnState.CONNECTED -> onOpenHome()
            else -> actions.connectNode(node)
        }
    }
    val row: @Composable (Node) -> Unit = { node ->
        ServerRow(
            node = node,
            health = health[node.id] ?: NodeHealth.Unknown,
            active = live && node.id == activeId,
            editing = editing,
            onTap = { onRowTap(node) },
            onTest = { viewModel.checkNode(node) },
            onDelete = { pendingDelete = node }
        )
    }

    Column(
        Modifier.padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Серверы", style = OrganicType.tabTitle, color = c.text, modifier = Modifier.weight(1f))
            if (nodes.isNotEmpty()) {
                OrganicButton(
                    text = if (editing) "Готово" else "Изменить",
                    onClick = { editing = !editing },
                    kind = ButtonKind.Secondary
                )
            }
            OrganicIconButton(
                icon = OrganicIcons.Plus,
                onClick = onAddServer,
                contentDescription = "Добавить сервер",
                size = 44.dp,
                iconSize = 20.dp,
                tint = c.bg,
                background = c.accent
            )
        }

        if (nodes.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrganicButton(
                    text = if (testingAll) "Проверка…" else "Проверить все",
                    // A second tap stops a running check.
                    onClick = { if (testingAll) viewModel.cancelTests() else viewModel.testAll() },
                    kind = ButtonKind.Secondary,
                    icon = OrganicIcons.CircleCheck
                )
                if (subscriptions.isNotEmpty()) {
                    OrganicButton(
                        text = "Обновить",
                        onClick = { viewModel.refreshAll() },
                        kind = ButtonKind.Secondary,
                        icon = OrganicIcons.RefreshCw,
                        enabled = refreshing.isEmpty()
                    )
                }
            }
        }

        if (!canTest && nodes.isNotEmpty()) {
            Text(
                "Пока VPN включён, проверка недоступна. Нажмите на сервер, чтобы переключиться на него.",
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.2.sp),
                color = c.sage800,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .fillMaxWidth()
                    .clip(OrganicShapes.md)
                    .background(c.sage100)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }

        if (nodes.isEmpty() && subscriptions.isEmpty()) {
            OrganicCard(
                modifier = Modifier.padding(top = 6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 22.dp),
                spacing = 10.dp
            ) {
                Text("Серверов пока нет", style = OrganicType.dialogTitle, color = c.text)
                Text(
                    "Добавьте ссылку на подписку, вставьте конфигурацию или выберите файл. " +
                        "Пока можно пользоваться WARP с главной.",
                    style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                    color = c.neutral700
                )
                OrganicButton("Добавить сервер", onClick = onAddServer)
            }
        }

        subscriptions.forEach { subscription ->
            val groupNodes = nodes.filter { it.sourceId == subscription.id }
            val isCollapsed = subscription.id in collapsed
            SubscriptionGroup(
                subscription = subscription,
                meta = subscriptionMeta(
                    servers = groupNodes.size,
                    lastUpdatedAt = subscription.lastUpdatedAt,
                    nowMs = now,
                    refreshing = subscription.id in refreshing,
                    autoUpdateMinutes = if (autoUpdate) autoUpdateMinutes else null
                ),
                collapsed = isCollapsed,
                refreshing = subscription.id in refreshing,
                testing = groupTest?.takeIf { it.first == subscription.id }?.second,
                onToggle = { viewModel.toggleSubscriptionCollapsed(subscription) },
                onRefresh = { viewModel.refresh(subscription) },
                onTest = { mode -> viewModel.testSubscription(subscription, mode) }
            ) {
                groupNodes.forEach { row(it) }
            }
        }

        if (imported.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp)) {
                    Text("Импортированные", style = OrganicType.groupTitle, color = c.text)
                    Text(
                        "Вставлены текстом или из файла · без автопереключения",
                        style = OrganicType.meta,
                        color = c.neutral600
                    )
                }
                imported.forEach { row(it) }
            }
        }
    }

    pendingDelete?.let { node ->
        val from = subscriptions.firstOrNull { it.id == node.sourceId }?.name
        OrganicDialog(
            title = "Удалить сервер?",
            body = if (from != null) {
                "«${node.name}» будет удалён из подписки «$from». При следующем обновлении подписки он может появиться снова."
            } else {
                "«${node.name}» будет удалён из списка."
            },
            confirmLabel = "Удалить",
            onConfirm = {
                pendingDelete = null
                // Removing the server we're on disconnects first.
                if (node.id == activeId && live) actions.stop()
                viewModel.deleteNode(node)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** A subscription's header (tap to collapse, refresh / quick / full) and its rows. */
@Composable
private fun SubscriptionGroup(
    subscription: Subscription,
    meta: String,
    collapsed: Boolean,
    refreshing: Boolean,
    testing: TestMode?,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onTest: (TestMode) -> Unit,
    rows: @Composable () -> Unit
) {
    val c = Organic.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val chevron by animateFloatAsState(if (collapsed) -90f else 0f, tween(200), label = "groupChevron")
            Row(
                Modifier
                    .weight(1f)
                    .clip(OrganicShapes.sm)
                    .clickable(role = Role.Button, onClickLabel = if (collapsed) "Развернуть" else "Свернуть", onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    OrganicIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp).rotate(chevron),
                    tint = c.neutral600
                )
                Column(Modifier.weight(1f)) {
                    Text(subscription.name, style = OrganicType.groupTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (testing != null) "Проверка… $meta" else meta,
                        style = OrganicType.meta,
                        color = c.neutral600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            GroupIconButton(OrganicIcons.RefreshCw, c.neutral700, "Обновить подписку", busy = refreshing, spin = true, onClick = onRefresh)
            GroupIconButton(OrganicIcons.Zap, c.accent700, "Быстрая проверка", busy = testing == TestMode.QUICK, spin = false) { onTest(TestMode.QUICK) }
            GroupIconButton(OrganicIcons.CircleCheck, c.sage700, "Полная проверка", busy = testing == TestMode.FULL, spin = false) { onTest(TestMode.FULL) }
        }
        AnimatedVisibility(
            visible = !collapsed,
            enter = expandVertically(tween(200)) + fadeIn(tween(200)),
            exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { rows() }
        }
    }
}

/**
 * 36 icon button of a group header. While [busy]: the refresh arrows turn
 * ([spin]); a test icon gives way to a small spinner instead - a spinning
 * bolt or check mark reads as broken, not busy.
 */
@Composable
private fun GroupIconButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    busy: Boolean,
    spin: Boolean,
    onClick: () -> Unit
) {
    val angle = if (busy) {
        val turning by rememberInfiniteTransition(label = "groupBusy").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
            label = "groupBusyAngle"
        )
        turning
    } else {
        0f
    }
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (busy && !spin) {
            Canvas(Modifier.size(15.dp)) {
                drawArc(
                    color = tint,
                    startAngle = angle - 90f,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        } else {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(16.dp).rotate(angle), tint = tint)
        }
    }
}

/** Full-width server row: name, protocol tag + host:port, latency chip; trash in edit mode. */
@Composable
private fun ServerRow(
    node: Node,
    health: NodeHealth,
    active: Boolean,
    editing: Boolean,
    onTap: () -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit
) {
    val c = Organic.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .clip(OrganicShapes.lg)
                .background(if (active) c.sage100 else c.surface)
                .border(2.dp, if (active) c.sage else Color.Transparent, OrganicShapes.lg)
                .clickable(role = Role.Button, onClick = onTap)
                .padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.name, style = OrganicType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProtocolTag(node.protocol, small = true)
                    Text(
                        hostPort(node),
                        style = OrganicType.mono,
                        color = c.neutral600,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LatencyChip(health, onTest)
        }
        if (editing) {
            OrganicIconButton(
                icon = OrganicIcons.Trash2,
                onClick = onDelete,
                contentDescription = "Удалить «${node.name}»",
                size = 44.dp,
                iconSize = 18.dp,
                tint = c.accent800,
                background = c.accent100
            )
        }
    }
}

private fun hostPort(node: Node): String =
    if (node.server.contains(':')) "[${node.server}]:${node.port}" else "${node.server}:${node.port}"

/** «Тест» / «···» / «48 мс» + bars / «Нет связи»; a tap re-tests the server. */
@Composable
private fun LatencyChip(health: NodeHealth, onTest: () -> Unit) {
    val c = Organic.colors
    var label = "Тест"
    var ink = c.accent700
    var bars = 0
    var barColor = c.neutral300
    when (health) {
        is NodeHealth.Checking -> {
            label = "···"
            ink = c.neutral600
        }
        is NodeHealth.Reachable -> {
            val look = latencyLook(health.latencyMs.toLong())
            label = look.label
            ink = look.labelColor
            bars = look.bars
            barColor = look.barColor
        }
        is NodeHealth.Unreachable -> {
            label = "Нет связи"
            ink = c.accent800
        }
        else -> Unit
    }
    Row(
        Modifier
            .clip(OrganicShapes.pill)
            .clickable(role = Role.Button, onClickLabel = "Проверить", onClick = onTest)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = ink,
            maxLines = 1
        )
        LatencyBars(active = bars, activeColor = barColor, inactiveColor = c.neutral300)
    }
}
