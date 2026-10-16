package com.betalgezia.omnivpn.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.data.model.Subscription
import com.betalgezia.omnivpn.ui.VpnActions
import com.betalgezia.omnivpn.ui.organic.ButtonKind
import com.betalgezia.omnivpn.ui.organic.IconPlate
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicCard
import com.betalgezia.omnivpn.ui.organic.OrganicDialog
import com.betalgezia.omnivpn.ui.organic.OrganicIconButton
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.OrganicSwitch
import com.betalgezia.omnivpn.ui.organic.OrganicTextField
import com.betalgezia.omnivpn.ui.organic.RowDivider
import com.betalgezia.omnivpn.ui.organic.SettingsLinkRow
import com.betalgezia.omnivpn.ui.organic.SettingsSwitchRow
import com.betalgezia.omnivpn.ui.organic.Tag
import com.betalgezia.omnivpn.ui.organic.TagTone
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType
import com.betalgezia.omnivpn.vpn.ActiveConnection
import com.betalgezia.omnivpn.vpn.TuneState
import com.betalgezia.omnivpn.vpn.VpnState
import io.nekohasekai.libbox.Libbox

// Settings pages (handoff README §3a): «Подписки», «Cloudflare WARP»,
// «Обфускация AmneziaWG», «О приложении». Each is a back button + title over
// its own column; the shell keeps the page in the Settings tab.

private fun SettingsPage.title(): String = when (this) {
    SettingsPage.SUBSCRIPTIONS -> "Подписки"
    SettingsPage.WARP -> "Cloudflare WARP"
    SettingsPage.OBFUSCATION -> "Обфускация AmneziaWG"
    SettingsPage.ABOUT -> "О приложении"
}

@Composable
fun SettingsPageScreen(
    page: SettingsPage,
    viewModel: MainViewModel,
    actions: VpnActions,
    onBack: () -> Unit,
    onAddSubscription: () -> Unit
) {
    Column(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OrganicIconButton(
                OrganicIcons.ChevronLeft,
                onClick = onBack,
                modifier = Modifier.offset(x = (-6).dp),
                contentDescription = "Назад",
                size = 44.dp,
                iconSize = 22.dp
            )
            Text(page.title(), style = OrganicType.pageTitle, color = Organic.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (page) {
            SettingsPage.SUBSCRIPTIONS -> SubscriptionsPage(viewModel, actions, onAddSubscription)
            SettingsPage.WARP -> WarpPage(viewModel, actions)
            SettingsPage.OBFUSCATION -> ObfuscationPage(viewModel)
            SettingsPage.ABOUT -> AboutPage()
        }
    }
}

// ============================ Подписки ===================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionsPage(viewModel: MainViewModel, actions: VpnActions, onAddSubscription: () -> Unit) {
    val c = Organic.colors
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val nodes by viewModel.nodes.collectAsStateWithLifecycle()
    val autoUpdate by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val minutes by viewModel.autoUpdateIntervalMinutes.collectAsStateWithLifecycle()
    val active by viewModel.activeConnection.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<Subscription?>(null) }
    var deleting by remember { mutableStateOf<Subscription?>(null) }
    val now = remember(subscriptions) { System.currentTimeMillis() }

    OrganicCard(spacing = 12.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch) { viewModel.setAutoUpdateEnabled(!autoUpdate) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text("Автообновление", style = OrganicType.bodyStrong, color = c.text)
                Text(autoUpdateSummary(autoUpdate, minutes), style = OrganicType.secondary, color = c.neutral700)
            }
            OrganicSwitch(checked = autoUpdate, onCheckedChange = null)
        }
        if (autoUpdate) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AUTO_UPDATE_FREQUENCIES.forEach { value ->
                    val selected = value == minutes
                    Text(
                        frequencyLabel(value),
                        style = OrganicType.secondary,
                        color = if (selected) c.bg else c.text,
                        modifier = Modifier
                            .clip(OrganicShapes.pill)
                            .background(if (selected) c.accent else c.bg)
                            .border(1.dp, if (selected) c.accent else c.divider, OrganicShapes.pill)
                            .clickable(role = Role.RadioButton) { viewModel.setAutoUpdateInterval(value) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            Text("Минимальный интервал — 15 минут.", style = OrganicType.meta, color = c.neutral600)
        }
    }

    subscriptions.forEachIndexed { index, subscription ->
        val count = nodes.count { it.sourceId == subscription.id }
        // Start padding 10 instead of 18: the action row reaches 8 further
        // left than the texts, like the handoff's −8 margin.
        OrganicCard(contentPadding = PaddingValues(start = 10.dp, top = 14.dp, end = 12.dp, bottom = 10.dp), spacing = 6.dp) {
            Text(
                subscription.name,
                style = OrganicType.groupTitle,
                color = c.text,
                modifier = Modifier.padding(start = 8.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$count серв. · ${updatedAgo(subscription.lastUpdatedAt, now)}",
                style = OrganicType.secondary,
                color = c.neutral700,
                modifier = Modifier.padding(start = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                OrganicButton("Переименовать", onClick = { renaming = subscription }, kind = ButtonKind.Ghost, icon = OrganicIcons.Pencil)
                Spacer(Modifier.weight(1f))
                OrganicIconButton(
                    OrganicIcons.ChevronUp,
                    onClick = { viewModel.moveSubscription(subscription, -1) },
                    contentDescription = "Выше",
                    enabled = index > 0
                )
                OrganicIconButton(
                    OrganicIcons.ChevronDown,
                    onClick = { viewModel.moveSubscription(subscription, 1) },
                    contentDescription = "Ниже",
                    enabled = index < subscriptions.lastIndex
                )
                OrganicIconButton(
                    OrganicIcons.Trash2,
                    onClick = { deleting = subscription },
                    contentDescription = "Удалить подписку",
                    tint = c.accent800
                )
            }
        }
    }

    OrganicButton("Добавить подписку", onClick = onAddSubscription, kind = ButtonKind.Secondary, icon = OrganicIcons.Plus)

    renaming?.let { subscription ->
        var name by rememberSaveable(subscription.id) { mutableStateOf(subscription.name) }
        OrganicDialog(
            title = "Переименовать подписку",
            body = "Название видно в списке серверов.",
            confirmLabel = "Сохранить",
            confirmEnabled = name.isNotBlank(),
            onConfirm = {
                viewModel.renameSubscription(subscription, name.trim())
                renaming = null
            },
            onDismiss = { renaming = null }
        ) {
            OrganicTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
            )
        }
    }

    deleting?.let { subscription ->
        val count = nodes.count { it.sourceId == subscription.id }
        OrganicDialog(
            title = "Удалить подписку?",
            body = "«${subscription.name}» и её серверы ($count) будут удалены с устройства.",
            confirmLabel = "Удалить",
            onConfirm = {
                deleting = null
                // Deleting the subscription we're connected through disconnects first.
                val activeSource = (active as? ActiveConnection.Server)?.node?.sourceId
                if (activeSource == subscription.id && vpnState != VpnState.DISCONNECTED) actions.stop()
                viewModel.delete(subscription)
            },
            onDismiss = { deleting = null }
        )
    }
}

// ============================ Cloudflare WARP ============================

@Composable
private fun WarpPage(viewModel: MainViewModel, actions: VpnActions) {
    val c = Organic.colors
    val info by viewModel.warpInfo.collectAsStateWithLifecycle()
    val ipv6 by viewModel.ipv6Enabled.collectAsStateWithLifecycle()
    val scanning by viewModel.endpointScanning.collectAsStateWithLifecycle()
    val active by viewModel.activeConnection.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val warpStarting by viewModel.warpStarting.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var endpoint by rememberSaveable { mutableStateOf("") }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshWarpState() }

    val warpActive = active == ActiveConnection.Warp
    val warpOn = warpActive && vpnState == VpnState.CONNECTED
    val warpConnecting = !warpOn && (warpStarting || (warpActive && vpnState != VpnState.CONNECTED))
    val idle = vpnState == VpnState.DISCONNECTED || vpnState == VpnState.ERROR || vpnState == VpnState.REVOKED

    OrganicCard(color = c.accent100, contentPadding = PaddingValues(18.dp), spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconPlate(OrganicIcons.Cloud, plate = c.accent, tint = c.bg, size = 46.dp, iconSize = 22.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        warpOn -> "WARP подключён"
                        warpConnecting -> "Подключаем WARP…"
                        else -> "Cloudflare WARP"
                    },
                    style = OrganicType.groupTitle,
                    color = c.text
                )
                Text(
                    (if (info.accountSaved) "Аккаунт сохранён" else "Аккаунт создастся при подключении") + " · ${info.endpoint}",
                    style = OrganicType.secondary,
                    color = c.accent800
                )
            }
        }
        OrganicButton(
            text = if (warpOn || warpConnecting) "Отключить WARP" else "Подключить WARP",
            onClick = { if (warpOn || warpActive) actions.stop() else actions.startWarp() },
            modifier = Modifier.fillMaxWidth(),
            // Still registering: nothing to stop yet; otherwise wait for a running search.
            enabled = if (warpOn || warpActive) true else !busy
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Свой endpoint",
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
            color = c.neutral700
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OrganicTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.weight(1f),
                placeholder = WARP_DEFAULT_ENDPOINT_HINT,
                mono = true,
                minHeight = 44.dp,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
            )
            OrganicButton(
                "Подключить",
                onClick = {
                    if (isWarpEndpoint(endpoint)) actions.startWarp(endpoint = endpoint.trim())
                    else viewModel.showMessage("Формат адреса: $WARP_DEFAULT_ENDPOINT_HINT")
                },
                kind = ButtonKind.Secondary,
                enabled = !busy
            )
        }
        Text("Если обычный адрес WARP заблокирован в вашей сети.", style = OrganicType.meta, color = c.neutral600)
    }

    OrganicCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        SettingsLinkRow(
            icon = OrganicIcons.Crosshair,
            plate = c.accent200,
            iconTint = c.accent800,
            title = "Найти рабочий endpoint",
            summary = when {
                scanning -> "Перебираем адреса Cloudflare…"
                !idle -> "Доступно при выключенном VPN"
                else -> "Перебрать адреса Cloudflare и сохранить рабочий"
            },
            onClick = viewModel::findWarpEndpoint,
            enabled = !scanning && !busy,
            chevron = false
        )
        RowDivider()
        SettingsSwitchRow(
            title = "IPv6 для WARP",
            summary = if (ipv6) {
                "Для сетей с родным IPv6. Выключите, если сломан именно IPv6 сети."
            } else {
                "Выключено — только IPv4"
            },
            checked = ipv6,
            onCheckedChange = viewModel::setIpv6Enabled,
            icon = OrganicIcons.Globe,
            plate = c.sage200,
            iconTint = c.sage800
        )
    }

    Row(
        Modifier
            .clip(OrganicShapes.pill)
            .clickable(enabled = !busy, role = Role.Button) { confirmReset = true }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(OrganicIcons.TriangleAlert, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.accent800)
        Text("Сбросить аккаунт WARP", style = OrganicType.button, color = c.accent800)
    }

    if (confirmReset) {
        OrganicDialog(
            title = "Сбросить аккаунт WARP?",
            body = "Сохранённый аккаунт будет удалён, и в Cloudflare зарегистрируется новый. " +
                "Сбрасывайте, только если WARP перестал подключаться.",
            confirmLabel = "Сбросить",
            onConfirm = {
                confirmReset = false
                actions.startWarp(forceNew = true)
            },
            onDismiss = { confirmReset = false }
        )
    }
}

private const val WARP_DEFAULT_ENDPOINT_HINT = "162.159.192.1:2408"

// ============================ Обфускация =================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ObfuscationPage(viewModel: MainViewModel) {
    val c = Organic.colors
    val current by viewModel.currentObfuscation.collectAsStateWithLifecycle()
    val custom by viewModel.obfuscationIsCustom.collectAsStateWithLifecycle()
    val tune by viewModel.tuneState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshWarpState() }

    Text(
        "Параметры «мусорных» пакетов для AmneziaWG и WARP. Если соединение режется провайдером, " +
            "подбор переберёт около 15 вариантов на трёх лучших адресах WARP.",
        style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
        color = c.neutral700
    )

    OrganicCard(spacing = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Сейчас", style = OrganicType.bodyStrong, color = c.text, modifier = Modifier.weight(1f))
            Tag(obfuscationSummary(custom, current.tunedLatencyMs), if (custom) TagTone.Sage else TagTone.Neutral)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            obfuscationChips(current).forEach { MonoChip(it) }
        }
    }

    when (val state = tune) {
        TuneState.Idle -> {
            OrganicButton(
                "Подобрать параметры",
                onClick = viewModel::startTune,
                modifier = Modifier.fillMaxWidth(),
                icon = OrganicIcons.WandSparkles
            )
            if (custom) {
                OrganicButton(
                    "Вернуть стандартные",
                    onClick = viewModel::resetObfuscation,
                    kind = ButtonKind.Ghost,
                    modifier = Modifier.offset(x = (-8).dp)
                )
            }
            Text(
                "Подбор запускается только вручную и только при выключенном VPN.",
                style = OrganicType.meta.copy(lineHeight = 18.sp),
                color = c.neutral600
            )
        }
        is TuneState.Running -> OrganicCard(spacing = 10.dp) {
            Row {
                Text("Вариант ${state.step} из ${state.total}", style = OrganicType.bodyStrong.copy(fontSize = 14.sp), color = c.text, modifier = Modifier.weight(1f))
                Text("рабочих: ${state.okCount}", style = OrganicType.bodyStrong.copy(fontSize = 14.sp), color = c.sage700)
            }
            val progress by animateFloatAsState(
                if (state.total > 0) state.step.toFloat() / state.total else 0f,
                tween(250),
                label = "tuneProgress"
            )
            Box(Modifier.fillMaxWidth().height(8.dp).clip(OrganicShapes.pill).background(c.neutral300)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(OrganicShapes.pill).background(c.accent))
            }
            state.log.forEach { line ->
                val ok = line.latencyMs != null
                val ink = if (ok) c.sage600 else c.neutral500
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(ink))
                    Text(
                        tuneCandidateLabel(line.candidate),
                        style = OrganicType.mono,
                        color = c.neutral700,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(if (ok) "${line.latencyMs} мс" else "нет рукопожатия", style = OrganicType.mono, color = ink)
                }
            }
            OrganicButton("Остановить", onClick = viewModel::cancelTune, kind = ButtonKind.Secondary)
        }
        is TuneState.Found -> OrganicCard(color = c.sage100, spacing = 8.dp) {
            Text("Найден рабочий вариант · ${state.latencyMs} мс", style = OrganicType.groupTitle, color = c.sage800)
            Text(tuneCandidateLabel(state.best), style = OrganicType.mono, color = c.sage800)
            Text(
                "Рабочих вариантов: ${state.okCount} из ${state.total}. Выбран самый быстрый.",
                style = OrganicType.secondary,
                color = c.neutral700
            )
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrganicButton("Применить", onClick = viewModel::applyTune, modifier = Modifier.weight(1f))
                OrganicButton("Оставить текущие", onClick = viewModel::dismissTune, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
            }
        }
        is TuneState.NotFound -> OrganicCard(color = c.accent100, spacing = 8.dp) {
            Text("Рабочих вариантов не найдено", style = OrganicType.groupTitle, color = c.accent800)
            Text("Текущие параметры не изменены.", style = OrganicType.secondary, color = c.neutral700)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrganicButton("Повторить", onClick = viewModel::startTune, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
                OrganicButton("Закрыть", onClick = viewModel::dismissTune, kind = ButtonKind.Secondary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MonoChip(text: String) {
    val c = Organic.colors
    Text(
        text,
        style = OrganicType.mono,
        color = c.neutral800,
        maxLines = 1,
        modifier = Modifier
            .clip(OrganicShapes.pill)
            .background(c.neutral100)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

// ============================ О приложении ===============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutPage() {
    val c = Organic.colors
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "—"
    }
    val core = remember { runCatching { Libbox.version().removePrefix("v") }.getOrNull() ?: "—" }

    OrganicCard(contentPadding = PaddingValues(vertical = 6.dp)) {
        InfoRow("Версия", version)
        RowDivider()
        InfoRow("Ядро", "sing-box-lx $core")
        RowDivider()
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Протоколы", style = TextStyle(fontSize = 14.sp), color = c.neutral700)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag("VLESS", TagTone.Accent)
                Tag("Trojan", TagTone.Sage)
                Tag("Hysteria2", TagTone.Neutral)
                Tag("AmneziaWG 2.0", TagTone.Outline)
                Tag("TUIC", TagTone.Neutral)
                Tag("VMess", TagTone.Neutral)
                Tag("Shadowsocks", TagTone.Neutral)
                Tag("WARP", TagTone.Accent)
            }
        }
    }
    Text(
        "Исходный код на GitHub",
        style = TextStyle(fontSize = 14.sp, textDecoration = TextDecoration.Underline),
        color = c.accent700,
        modifier = Modifier
            .clip(OrganicShapes.sm)
            .clickable(role = Role.Button) { uriHandler.openUri("https://github.com/Betalgezia/OmniVPN") }
            .padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = Organic.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = TextStyle(fontSize = 14.sp), color = c.neutral700, modifier = Modifier.weight(1f))
        Text(value, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = c.text)
    }
}
