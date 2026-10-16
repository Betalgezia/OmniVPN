package com.betalgezia.omnivpn.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.ui.ThemeChoice
import com.betalgezia.omnivpn.ui.organic.OrganicCard
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.RowDivider
import com.betalgezia.omnivpn.ui.organic.SectionLabel
import com.betalgezia.omnivpn.ui.organic.SettingsLinkRow
import com.betalgezia.omnivpn.ui.organic.SettingsSegmentRow
import com.betalgezia.omnivpn.ui.organic.SettingsSwitchRow
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicType
import com.betalgezia.omnivpn.vpn.TestMode
import io.nekohasekai.libbox.Libbox

/** Pages reachable from the settings root (handoff §3a). */
enum class SettingsPage { SUBSCRIPTIONS, WARP, OBFUSCATION, ABOUT }

/**
 * Settings root (handoff §3): sections of cards, every row shows its current
 * value. Connection switches and segments act in place; the rest open pages.
 */
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    theme: ThemeChoice,
    onThemeChange: (ThemeChoice) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
    onAddServer: () -> Unit
) {
    val c = Organic.colors
    val autoSwitch by viewModel.autoSwitchEnabled.collectAsStateWithLifecycle()
    val speedSwitch by viewModel.speedSwitchEnabled.collectAsStateWithLifecycle()
    val testMode by viewModel.testMode.collectAsStateWithLifecycle()
    val tileWarp by viewModel.quickTileStartsWarp.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val autoUpdate by viewModel.autoUpdateEnabled.collectAsStateWithLifecycle()
    val autoUpdateMinutes by viewModel.autoUpdateIntervalMinutes.collectAsStateWithLifecycle()
    val ipv6 by viewModel.ipv6Enabled.collectAsStateWithLifecycle()
    val obfCustom by viewModel.obfuscationIsCustom.collectAsStateWithLifecycle()
    val coreVersion = remember { runCatching { Libbox.version() }.getOrNull() }

    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text("Настройки", style = OrganicType.tabTitle)

        Section("Подключение") {
            SettingsSwitchRow(
                title = "Автопереключение",
                summary = if (autoSwitch) {
                    "Если сервер перестал пропускать трафик, переходим на соседний из той же подписки. Про WARP только сообщаем."
                } else {
                    "Выключено — только сообщаем, что сервер не отвечает"
                },
                checked = autoSwitch,
                onCheckedChange = viewModel::setAutoSwitchEnabled
            )
            RowDivider()
            SettingsSwitchRow(
                title = "Уходить с медленного сервера",
                summary = if (speedSwitch) {
                    "Если загрузка идёт медленнее 3 Мбит/с, замерим другие серверы подписки (≈1 МБ на каждый) и перейдём на более быстрый."
                } else {
                    "Выключено — остаёмся на выбранном сервере, даже если он медленный."
                },
                checked = speedSwitch,
                onCheckedChange = viewModel::setSpeedSwitchEnabled
            )
            RowDivider()
            SettingsSegmentRow(
                title = "Проверка серверов",
                options = listOf("Быстрая", "Полная"),
                selectedIndex = if (testMode == TestMode.QUICK) 0 else 1,
                onSelect = { viewModel.setTestMode(if (it == 0) TestMode.QUICK else TestMode.FULL) },
                description = if (testMode == TestMode.QUICK) {
                    "TCP-рукопожатие с сервером. Быстро, но протокол не проверяется."
                } else {
                    "Настоящий запрос через sing-box. Дольше, зато точно."
                }
            )
            RowDivider()
            SettingsSegmentRow(
                title = "Плитка в шторке",
                options = listOf("WARP", "Быстрейший"),
                selectedIndex = if (tileWarp) 0 else 1,
                onSelect = { viewModel.setQuickTileStartsWarp(it == 0) },
                description = if (tileWarp) {
                    "Нажатие на плитку включает Cloudflare WARP."
                } else {
                    "Плитка проверяет серверы подписок и подключается к самому быстрому."
                }
            )
        }

        Section("Серверы") {
            SettingsLinkRow(
                icon = OrganicIcons.Link,
                plate = c.sage200,
                iconTint = c.sage800,
                title = "Подписки",
                summary = "${subscriptions.size} · " +
                    if (autoUpdate) "обновление каждые ${frequencyLabel(autoUpdateMinutes)}" else "только вручную",
                onClick = { onOpenPage(SettingsPage.SUBSCRIPTIONS) }
            )
            RowDivider()
            SettingsLinkRow(
                icon = OrganicIcons.Plus,
                plate = c.accent200,
                iconTint = c.accent800,
                title = "Добавить сервер",
                summary = "Подписка, ссылка, JSON/YAML или файл",
                onClick = onAddServer
            )
        }

        Section("Обход блокировок") {
            SettingsLinkRow(
                icon = OrganicIcons.Cloud,
                plate = c.accent200,
                iconTint = c.accent800,
                title = "Cloudflare WARP",
                summary = if (ipv6) "IPv6 вкл" else "IPv6 выкл",
                onClick = { onOpenPage(SettingsPage.WARP) }
            )
            RowDivider()
            SettingsLinkRow(
                icon = OrganicIcons.WandSparkles,
                plate = c.neutral300,
                iconTint = c.neutral800,
                title = "Обфускация AmneziaWG",
                summary = if (obfCustom) "Подобранные" else "Стандартные",
                onClick = { onOpenPage(SettingsPage.OBFUSCATION) }
            )
        }

        Section("Приложение") {
            SettingsSegmentRow(
                title = "Тема",
                options = listOf("Светлая", "Тёмная"),
                selectedIndex = if (theme == ThemeChoice.LIGHT) 0 else 1,
                onSelect = { onThemeChange(if (it == 0) ThemeChoice.LIGHT else ThemeChoice.DARK) },
                description = ""
            )
            RowDivider()
            SettingsLinkRow(
                icon = OrganicIcons.Info,
                plate = c.neutral300,
                iconTint = c.neutral800,
                title = "О приложении",
                summary = coreVersion?.let { "Ядро sing-box-lx ${it.removePrefix("v")}" } ?: "Ядро sing-box-lx",
                onClick = { onOpenPage(SettingsPage.ABOUT) }
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        SectionLabel(title)
        Spacer(Modifier.height(8.dp))
        OrganicCard(contentPadding = PaddingValues(vertical = 6.dp)) { content() }
    }
}
