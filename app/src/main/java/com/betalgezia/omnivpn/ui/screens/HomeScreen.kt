package com.betalgezia.omnivpn.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.ui.VpnActions
import com.betalgezia.omnivpn.ui.organic.ButtonKind
import com.betalgezia.omnivpn.ui.organic.IconPlate
import com.betalgezia.omnivpn.ui.organic.LatencyBars
import com.betalgezia.omnivpn.ui.organic.OrbitMark
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicCard
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.ProtocolTag
import com.betalgezia.omnivpn.ui.organic.Tag
import com.betalgezia.omnivpn.ui.organic.TagTone
import com.betalgezia.omnivpn.ui.organic.latencyLook
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.NodeHealth
import com.betalgezia.omnivpn.vpn.TrafficStatus
import com.betalgezia.omnivpn.vpn.TunnelStats
import com.betalgezia.omnivpn.vpn.VpnState
import kotlinx.coroutines.delay

// Organic home screen (handoff README §1): header, status, the hero power
// button with live counters on its blob, the no-traffic card, the WARP button
// and the server selector. The switch-event card (§1.4) comes with the
// switch journal in a later stage.

/** Fill, ring, icon, title and blob colours of one hero state. */
private data class HeroLook(val fill: Color, val ring: Color, val icon: Color, val title: Color, val blob: Color)

@Composable
private fun heroLook(kind: HeroKind, warp: Boolean): HeroLook {
    val c = Organic.colors
    return when (kind) {
        HeroKind.OFF -> HeroLook(c.surface, c.accent, c.accent700, c.text, c.sage200)
        HeroKind.SEARCH, HeroKind.CONNECTING -> HeroLook(c.accent100, c.accent300, c.accent700, c.accent700, c.accent200)
        HeroKind.ON -> if (warp) {
            HeroLook(c.accent, c.accent300, c.bg, c.accent700, c.accent200)
        } else {
            HeroLook(c.sage, c.sage300, c.bg, c.sage700, c.sage300)
        }
        HeroKind.NO_TRAFFIC, HeroKind.NO_DNS -> HeroLook(c.accent200, c.accent400, c.accent800, c.accent800, c.accent300)
        HeroKind.ERROR -> HeroLook(c.accent700, c.accent400, c.bg, c.accent800, c.accent300)
        HeroKind.REVOKED -> HeroLook(c.neutral300, c.neutral500, c.neutral800, c.neutral800, c.neutral300)
    }
}

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    actions: VpnActions,
    onOpenServers: () -> Unit,
    onOpenJournal: () -> Unit
) {
    val c = Organic.colors
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val searching by viewModel.searchingFastest.collectAsStateWithLifecycle()
    val warpStarting by viewModel.warpStarting.collectAsStateWithLifecycle()
    val active by viewModel.activeConnection.collectAsStateWithLifecycle()
    val traffic by viewModel.trafficStatus.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val stats by viewModel.tunnelStats.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val health by viewModel.nodeHealth.collectAsStateWithLifecycle()
    val autoSwitch by viewModel.autoSwitchEnabled.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val connectedSince by viewModel.connectedSince.collectAsStateWithLifecycle()
    val switchNotice by viewModel.switchNotice.collectAsStateWithLifecycle()
    val measuring by viewModel.speedMeasuring.collectAsStateWithLifecycle()
    val journal by viewModel.switchJournal.collectAsStateWithLifecycle()

    // What «Подключиться» searches: subscription servers only (see connectFastest).
    val candidates = remember(nodes) {
        nodes.filter { it.id != 0L && it.sourceId != null && it.protocol != Protocol.WARP }
    }
    val kind = heroKind(vpnState, searching, warpStarting, active, traffic, lastError)
    val server = (active as? ActiveConnection.Server)?.node
    val activeName = when {
        server != null -> server.name
        active == ActiveConnection.Warp || warpStarting -> "Cloudflare WARP"
        else -> null
    }
    val warpActive = active == ActiveConnection.Warp
    val warpOn = warpActive && vpnState == VpnState.CONNECTED
    val warpConnecting = !warpOn && (warpStarting || (warpActive && vpnState != VpnState.CONNECTED))

    val title = when (kind) {
        HeroKind.OFF -> "Подключиться"
        HeroKind.SEARCH -> "Поиск…"
        HeroKind.CONNECTING -> "Подключение…"
        HeroKind.ON -> "Подключено"
        HeroKind.NO_TRAFFIC -> "Нет трафика"
        HeroKind.NO_DNS -> "Нет DNS"
        HeroKind.ERROR -> "Ошибка подключения"
        HeroKind.REVOKED -> "Разрешение отозвано"
    }
    val subtitle = when (kind) {
        HeroKind.OFF -> if (candidates.isNotEmpty()) {
            "Самый быстрый из ${candidates.size} ${serversGenitive(candidates.size)}"
        } else {
            "Сначала добавьте подписку на вкладке «Серверы»"
        }
        HeroKind.SEARCH -> {
            val checked = candidates.count { node -> health[node.id].let { it != null && it != NodeHealth.Checking } }
            "Проверено $checked из ${candidates.size}"
        }
        HeroKind.CONNECTING -> activeName ?: "Устанавливаем соединение"
        HeroKind.ON -> activeName ?: "Защищённое соединение"
        HeroKind.NO_TRAFFIC -> "«${activeName ?: "Сервер"}» не отвечает"
        HeroKind.NO_DNS -> "Сайты не открываются: не работает DNS"
        HeroKind.ERROR -> "${errorReason(lastError)}. Нажмите, чтобы повторить"
        HeroKind.REVOKED -> "Другое VPN-приложение заняло туннель. Нажмите, чтобы вернуть"
    }

    val onHeroTap: () -> Unit = {
        when (kind) {
            HeroKind.OFF, HeroKind.ERROR, HeroKind.REVOKED ->
                // No servers yet: let the view model say so, no consent dialog first.
                if (candidates.isEmpty()) viewModel.connectFastest() else actions.connectFastest()
            HeroKind.SEARCH -> viewModel.cancelTests()
            HeroKind.CONNECTING ->
                // WARP still registering: there is no tunnel to stop yet.
                if (vpnState != VpnState.DISCONNECTED) actions.stop()
            HeroKind.ON, HeroKind.NO_TRAFFIC, HeroKind.NO_DNS -> actions.stop()
        }
    }

    // Session clock for the WARP button, ticking only while WARP is up.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(warpOn) {
        while (warpOn) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val clock = sessionClock((nowMs - (connectedSince ?: nowMs)) / 1000)

    Column(
        Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrbitMark(Modifier.size(32.dp))
            Text("OmniVPN", style = OrganicType.appName, color = c.text)
        }

        val look = heroLook(kind, warp = warpActive)
        val titleColor by animateColorAsState(look.title, tween(500), label = "heroTitle")
        Column(Modifier.heightIn(min = 92.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = OrganicType.heroTitle, color = titleColor)
            Text(
                subtitle,
                style = OrganicType.bodyLarge.copy(lineHeight = 21.75.sp),
                color = c.neutral700,
                modifier = Modifier.widthIn(max = 300.dp)
            )
        }

        HeroBlock(
            look = look,
            spinning = kind == HeroKind.SEARCH || kind == HeroKind.CONNECTING,
            pulsing = kind == HeroKind.ON && traffic == TrafficStatus.OK,
            onTap = onHeroTap
        ) {
            if (vpnState == VpnState.CONNECTED) {
                Counters(
                    protocol = server?.protocol,
                    stats = stats,
                    measuring = measuring != null,
                    modifier = Modifier.offset(x = 200.dp, y = 52.dp).width(100.dp)
                )
            }
        }

        val slide = with(LocalDensity.current) { 16.dp.roundToPx() }
        val tunnelUp = vpnState == VpnState.CONNECTED || vpnState == VpnState.CONNECTING
        AnimatedVisibility(
            visible = tunnelUp && (switchNotice != null || measuring != null || journal.isNotEmpty()),
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { slide },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { slide }
        ) {
            SwitchCard(
                notice = switchNotice,
                measuring = measuring,
                journalSize = journal.size,
                onDismiss = viewModel::dismissSwitchNotice,
                onOpenJournal = onOpenJournal
            )
        }
        AnimatedVisibility(
            visible = kind == HeroKind.NO_TRAFFIC || kind == HeroKind.NO_DNS,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { slide },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { slide }
        ) {
            NoTrafficCard(
                note = if (autoSwitch && server?.sourceId != null) {
                    "Автопереключение включено — перейдём на соседний сервер подписки"
                } else {
                    "Выберите другой сервер или WARP"
                },
                onOpenServers = onOpenServers
            )
        }

        WarpButton(
            on = warpOn,
            connecting = warpConnecting,
            clock = clock,
            // Turning WARP off always works; starting it waits for a running search/connect.
            enabled = warpOn || warpConnecting || !busy,
            onClick = {
                when {
                    warpOn || (warpActive && warpConnecting) -> actions.stop()
                    warpConnecting -> Unit // still registering
                    else -> actions.startWarp()
                }
            }
        )

        if (kind != HeroKind.ON && kind != HeroKind.NO_TRAFFIC && kind != HeroKind.NO_DNS) {
            ServerPicker(
                text = if (candidates.isNotEmpty()) {
                    "Самый быстрый из ${candidates.size} · подписок: ${subscriptions.size}"
                } else {
                    "Серверов пока нет — добавить"
                },
                onClick = onOpenServers
            )
        }
    }
}

/** CSS `border-radius: 58% 42% 55% 45% / 50% 60% 40% 50%` - the soft blob behind the button. */
private val BlobShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        return Outline.Rounded(
            RoundRect(
                left = 0f, top = 0f, right = w, bottom = h,
                topLeftCornerRadius = CornerRadius(0.58f * w, 0.50f * h),
                topRightCornerRadius = CornerRadius(0.42f * w, 0.60f * h),
                bottomRightCornerRadius = CornerRadius(0.55f * w, 0.40f * h),
                bottomLeftCornerRadius = CornerRadius(0.45f * w, 0.50f * h)
            )
        )
    }
}

private val EaseOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)

/**
 * 210-high hero: blob (left 120, top 18, 200×180), the 186 power button
 * (left 0, top 10, 4dp ring) with its spinner or pulse, and [overlay] for the
 * counters drawn on the blob.
 */
@Composable
private fun HeroBlock(
    look: HeroLook,
    spinning: Boolean,
    pulsing: Boolean,
    onTap: () -> Unit,
    overlay: @Composable () -> Unit
) {
    val c = Organic.colors
    val fill by animateColorAsState(look.fill, tween(500), label = "heroFill")
    val ring by animateColorAsState(look.ring, tween(500), label = "heroRing")
    val icon by animateColorAsState(look.icon, tween(500), label = "heroIcon")
    val blob by animateColorAsState(look.blob, tween(600), label = "heroBlob")

    Box(Modifier.fillMaxWidth().height(210.dp)) {
        Box(
            Modifier
                .offset(x = 120.dp, y = 18.dp)
                .size(200.dp, 180.dp)
                .background(blob, BlobShape)
        )
        Box(Modifier.offset(y = 10.dp).size(186.dp), contentAlignment = Alignment.Center) {
            if (pulsing) PulseHalo(fill)
            val interaction = remember { MutableInteractionSource() }
            val pressed by interaction.collectIsPressedAsState()
            val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120), label = "heroPress")
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .shadow(5.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(CircleShape)
                    .background(fill)
                    .border(4.dp, ring, CircleShape)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Подключение",
                        onClick = onTap
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(OrganicIcons.Power, contentDescription = "Подключение", modifier = Modifier.size(64.dp), tint = icon)
            }
            if (spinning) SpinnerArc(c.accent)
        }
        overlay()
    }
}

/** Searching / connecting: top and right quarters of a ring 9 outside the button, 1 turn a second. */
@Composable
private fun SpinnerArc(color: Color) {
    val rotation by rememberInfiniteTransition(label = "spin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
        label = "spinAngle"
    )
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 4.dp.toPx()
        // Button inner edge (186/2 − 4) + 9 inset, stroke centred on the ring.
        val radius = (89.dp + 9.dp).toPx() - stroke / 2
        drawArc(
            color = color,
            startAngle = -135f + rotation,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke)
        )
    }
}

/** Connected and traffic flows: a halo in the button colour, 0.94→1.22, fading out, every 2.6 s. */
@Composable
private fun PulseHalo(color: Color) {
    val t by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulseT"
    )
    Canvas(Modifier.fillMaxSize()) {
        if (t >= 0.75f) return@Canvas
        val p = EaseOut.transform(t / 0.75f)
        val radius = 89.dp.toPx() * (0.94f + 0.28f * p)
        drawCircle(color.copy(alpha = color.alpha * 0.8f * (1f - p)), radius = radius)
    }
}

private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Protocol, latency with bars, ↓ and ↑ - straight on the blob, no frames. */
@Composable
private fun Counters(protocol: Protocol?, stats: TunnelStats, measuring: Boolean, modifier: Modifier) {
    val c = Organic.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (protocol != null) ProtocolTag(protocol) else Tag("WARP", TagTone.Accent)
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val latency = stats.latencyMs
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val dim = c.text.copy(alpha = 0.2f)
                if (latency != null) {
                    val l = latencyLook(latency)
                    Text(l.label, style = Tabular.merge(TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold)), color = c.text, maxLines = 1, softWrap = false)
                    LatencyBars(active = l.bars, activeColor = l.barColor, inactiveColor = dim)
                } else {
                    Text("—", style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold), color = c.neutral600, maxLines = 1)
                    LatencyBars(active = 0, activeColor = dim, inactiveColor = dim)
                }
            }
            // While a slow-server measurement runs, the download figure is what's in question.
            RateRow(OrganicIcons.ArrowDown, formatMbps(stats.downBps), highlight = measuring && stats.downBps != null)
            RateRow(OrganicIcons.ArrowUp, formatMbps(stats.upBps))
        }
    }
}

@Composable
private fun RateRow(icon: ImageVector, value: String, highlight: Boolean = false) {
    val c = Organic.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = c.neutral700)
        Text(
            value,
            style = Tabular.merge(TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)),
            color = if (highlight) c.accent700 else c.text,
            maxLines = 1,
            softWrap = false
        )
        Text("Мбит/с", style = TextStyle(fontSize = 11.sp), color = c.neutral700, maxLines = 1, softWrap = false)
    }
}

/** Connected but nothing gets through: what happens next and a way to the list. */
@Composable
private fun NoTrafficCard(note: String, onOpenServers: () -> Unit) {
    val c = Organic.colors
    OrganicCard(color = c.accent100) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(
                OrganicIcons.TriangleAlert,
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp).size(20.dp),
                tint = c.accent700
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(note, style = TextStyle(fontSize = 14.sp, lineHeight = 20.3.sp), color = c.accent800)
                // Ghost buttons carry 8dp side padding; pull the label back to the text edge.
                OrganicButton(
                    text = "Открыть серверы",
                    onClick = onOpenServers,
                    kind = ButtonKind.Ghost,
                    modifier = Modifier.offset(x = (-8).dp)
                )
            }
        }
    }
}

/** Full-width WARP pill: idle, connecting, connected (with the session clock and «ВКЛ»). */
@Composable
private fun WarpButton(on: Boolean, connecting: Boolean, clock: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Organic.colors
    val lit = on || connecting
    val background by animateColorAsState(if (lit) c.accent100 else c.surface, tween(400), label = "warpBg")
    val border by animateColorAsState(if (lit) c.accent else c.accent.copy(alpha = 0f), tween(400), label = "warpBorder")
    val plate by animateColorAsState(if (lit) c.accent else c.accent200, tween(400), label = "warpPlate")
    val title = when {
        on -> "WARP подключён"
        connecting -> "Подключаем WARP…"
        else -> "Cloudflare WARP"
    }
    val subtitle = when {
        on -> "Нажмите, чтобы отключить · $clock"
        connecting -> "Регистрация и рукопожатие"
        else -> "Подключение в одно касание"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clip(OrganicShapes.pill)
            .background(background)
            .border(2.dp, border, OrganicShapes.pill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconPlate(OrganicIcons.Cloud, plate = plate, tint = if (lit) c.bg else c.accent700, size = 46.dp, iconSize = 22.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = OrganicType.warpTitle, color = c.text, maxLines = 1)
            Text(
                subtitle,
                style = OrganicType.secondary.merge(Tabular),
                color = c.neutral700,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (on) Tag("ВКЛ", TagTone.Solid)
        if (!lit) Icon(OrganicIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.accent700)
    }
}

/** «Сервер · Самый быстрый из N» - opens the Servers tab. */
@Composable
private fun ServerPicker(text: String, onClick: () -> Unit) {
    val c = Organic.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(OrganicShapes.md)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconPlate(OrganicIcons.Zap, plate = c.sage200, tint = c.sage800)
        Column(Modifier.weight(1f)) {
            Text("Сервер", style = OrganicType.meta, color = c.neutral600)
            Text(text, style = OrganicType.bodyStrong, color = c.text)
        }
        Icon(OrganicIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.neutral600)
    }
}
