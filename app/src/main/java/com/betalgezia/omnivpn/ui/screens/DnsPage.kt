package com.betalgezia.omnivpn.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicCard
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.OrganicTextField
import com.betalgezia.omnivpn.ui.organic.RowDivider
import com.betalgezia.omnivpn.ui.organic.SectionLabel
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicType
import com.betalgezia.omnivpn.vpn.DnsCatalog
import com.betalgezia.omnivpn.vpn.parseDnsEndpoint

// Settings → DNS-сервер: the resolver AmneziaWG/WARP use. Named DoH presets
// (including the «для ИИ» group) or a custom DoH/IP address; the choice is
// stored in DnsSettings and baked into the next endpointMode config by
// SingBoxConfigBuilder.

@Composable
internal fun DnsPage(viewModel: MainViewModel) {
    val c = Organic.colors
    val selection by viewModel.dnsSelection.collectAsStateWithLifecycle()
    val custom by viewModel.dnsCustom.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "DNS для WARP и AmneziaWG. Запросы идут через туннель, а DoH дополнительно их шифрует. " +
                "У прокси-протоколов (VLESS, Trojan, Hysteria2 и других) домены разрешает сам сервер — на них выбор не влияет.",
            style = OrganicType.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = c.neutral700
        )

        SectionLabel("По умолчанию")
        OrganicCard(contentPadding = PaddingValues(vertical = 4.dp)) {
            DnsRow(
                title = DnsCatalog.DEFAULT_SERVER,
                summary = "UDP через туннель",
                selected = selection == DnsCatalog.DEFAULT_ID,
                onClick = { viewModel.selectDns(DnsCatalog.DEFAULT_ID) }
            )
        }

        DnsCatalog.groups.forEach { group ->
            SectionLabel(group.title)
            OrganicCard(contentPadding = PaddingValues(vertical = 4.dp)) {
                group.presets.forEachIndexed { index, preset ->
                    if (index > 0) RowDivider()
                    DnsRow(
                        title = preset.label,
                        summary = "${preset.summary} · ${preset.endpoint.host}",
                        selected = selection == preset.id,
                        onClick = { viewModel.selectDns(preset.id) }
                    )
                }
            }
        }

        CustomDnsCard(viewModel, selection, custom)

        Text(
            "Изменение применится при следующем подключении.",
            style = OrganicType.meta,
            color = c.neutral600
        )
    }
}

@Composable
private fun DnsRow(title: String, summary: String, selected: Boolean, onClick: () -> Unit) {
    val c = Organic.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = OrganicType.bodyStrong, color = c.text)
            Text(summary, style = OrganicType.secondary, color = c.neutral700)
        }
        if (selected) {
            Icon(
                OrganicIcons.Check,
                contentDescription = "Выбрано",
                modifier = Modifier.size(20.dp),
                tint = c.accent
            )
        }
    }
}

@Composable
private fun CustomDnsCard(viewModel: MainViewModel, selection: String, saved: String) {
    val c = Organic.colors
    val selectedCustom = selection == DnsCatalog.CUSTOM_ID
    // Edits are local until «Сохранить»; reset when the stored value changes
    // (a successful save normalises it, e.g. adds https:// and /dns-query).
    var input by remember(saved) { mutableStateOf(saved) }
    val parsed = remember(input) { parseDnsEndpoint(input) }
    val invalid = input.isNotBlank() && parsed == null
    val canSave = parsed != null && (input.trim() != saved || !selectedCustom)

    SectionLabel("Свой DNS")
    OrganicCard(spacing = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DoH-адрес или IP-адрес сервера",
                style = OrganicType.secondary,
                color = c.neutral700,
                modifier = Modifier.weight(1f)
            )
            if (selectedCustom) {
                Icon(
                    OrganicIcons.Check,
                    contentDescription = "Выбрано",
                    modifier = Modifier.size(20.dp),
                    tint = c.accent
                )
            }
        }
        if (selectedCustom && saved.isNotBlank()) {
            Text(
                "Выбран · ${parseDnsEndpoint(saved)?.host ?: saved}",
                style = OrganicType.meta,
                color = c.accent800
            )
        }
        OrganicTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "https://dns.example/dns-query или 1.1.1.1",
            mono = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)
        )
        Text(
            when {
                invalid -> "Не понял адрес. Примеры: https://dns.google/dns-query, dns.google/dns-query, 9.9.9.9."
                else -> "Без схемы считаем DoH: dns.google → https://dns.google/dns-query. IP-адрес — обычный UDP DNS."
            },
            style = OrganicType.meta,
            color = if (invalid) c.accent800 else c.neutral600
        )
        OrganicButton(
            text = if (selectedCustom) "Сохранить" else "Сохранить и выбрать",
            onClick = {
                val normalized = parseDnsEndpoint(input)?.text
                if (viewModel.saveCustomDns(input)) {
                    normalized?.let { input = it }
                } else {
                    viewModel.showMessage("Проверьте адрес DNS")
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = canSave
        )
    }
}
