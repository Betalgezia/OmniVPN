package com.betalgezia.omnivpn.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betalgezia.omnivpn.ui.organic.ButtonKind
import com.betalgezia.omnivpn.ui.organic.IconPlate
import com.betalgezia.omnivpn.ui.organic.OrganicBottomSheet
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicIconButton
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.SectionLabel
import com.betalgezia.omnivpn.ui.organic.SheetHeader
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType
import com.betalgezia.omnivpn.update.GITHUB_REPO
import com.betalgezia.omnivpn.update.NoteBlock
import com.betalgezia.omnivpn.update.ReleaseInfo
import com.betalgezia.omnivpn.update.UpdateState
import com.betalgezia.omnivpn.update.formatMegabytes
import com.betalgezia.omnivpn.update.release
import com.betalgezia.omnivpn.update.releaseNoteBlocks

// The in-app update from GitHub releases (update/AppUpdater): the banner at
// the top of Главная, the «Обновление» sheet, and the row on «О приложении».

/** «12 из 26 МБ», or «12 МБ» while the size is unknown. */
internal fun downloadProgressText(done: Long, total: Long): String =
    if (total > 0) "${formatMegabytes(done).removeSuffix(" МБ")} из ${formatMegabytes(total)}" else formatMegabytes(done)

private fun UpdateState.headline(): String {
    val version = release?.version.orEmpty()
    return when (this) {
        UpdateState.Idle -> "Обновления"
        is UpdateState.Available -> "Доступна версия $version"
        is UpdateState.Downloading -> "Скачиваем OmniVPN $version"
        is UpdateState.NeedsPermission -> "Обновление $version скачано"
        is UpdateState.Installing -> "Устанавливаем $version"
        is UpdateState.Failed -> "Обновление $version не установлено"
    }
}

private fun UpdateState.detail(): String = when (this) {
    UpdateState.Idle -> ""
    is UpdateState.Available -> "Нажмите, чтобы посмотреть, что нового"
    is UpdateState.Downloading -> downloadProgressText(doneBytes, totalBytes)
    is UpdateState.NeedsPermission -> "Разрешите установку — это нужно один раз"
    is UpdateState.Installing -> "Подтвердите в окне Android"
    is UpdateState.Failed -> message
}

/** Top of Главная while an update is known: what it is and where it stands. Tap opens the sheet. */
@Composable
fun UpdateBanner(state: UpdateState, onOpen: () -> Unit, onSkip: () -> Unit) {
    val c = Organic.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OrganicShapes.lg)
            .background(c.accent100)
            .clickable(role = Role.Button, onClickLabel = "Открыть обновление", onClick = onOpen)
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconPlate(OrganicIcons.Download, plate = c.accent, tint = c.bg, size = 40.dp, iconSize = 19.dp)
            Column(Modifier.weight(1f)) {
                Text(state.headline(), style = OrganicType.bodyStrong, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.detail(),
                    style = OrganicType.secondary,
                    color = if (state is UpdateState.Failed) c.accent800 else c.neutral700,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state is UpdateState.Available) {
                OrganicIconButton(OrganicIcons.X, onClick = onSkip, contentDescription = "Пропустить эту версию", tint = c.neutral600)
            } else {
                Icon(
                    OrganicIcons.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    tint = c.neutral600
                )
            }
        }
        if (state is UpdateState.Downloading) {
            DownloadBar(state, Modifier.padding(end = 6.dp))
        }
    }
}

@Composable
private fun DownloadBar(state: UpdateState.Downloading, modifier: Modifier = Modifier) {
    val c = Organic.colors
    val fraction = if (state.totalBytes > 0) (state.doneBytes.toFloat() / state.totalBytes).coerceIn(0f, 1f) else 0f
    val progress by animateFloatAsState(fraction, tween(200), label = "updateProgress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(OrganicShapes.pill).background(c.neutral300)) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().clip(OrganicShapes.pill).background(c.accent))
    }
}

/**
 * «Обновление»: the new version, what's new in it (the release description)
 * and the one next step - update, cancel, allow installing, retry.
 */
@Composable
fun UpdateSheet(
    visible: Boolean,
    state: UpdateState,
    currentVersion: String,
    vpnOn: Boolean,
    skipped: Boolean,
    onDismiss: () -> Unit,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onAllowInstall: () -> Unit
) {
    val c = Organic.colors
    OrganicBottomSheet(visible = visible, onDismiss = onDismiss, maxHeightFraction = 0.85f, spacing = 14.dp) {
        SheetHeader("Обновление", onClose = onDismiss)
        val release = state.release
        if (release == null) {
            Text("У вас последняя версия — $currentVersion.", style = OrganicType.bodyLarge, color = c.neutral700)
        } else {
            ReleaseDetails(state, release, currentVersion, vpnOn, skipped, onUpdate, onCancel, onSkip, onAllowInstall)
        }
    }
}

@Composable
private fun ColumnScope.ReleaseDetails(
    state: UpdateState,
    release: ReleaseInfo,
    currentVersion: String,
    vpnOn: Boolean,
    skipped: Boolean,
    onUpdate: () -> Unit,
    onCancel: () -> Unit,
    onSkip: () -> Unit,
    onAllowInstall: () -> Unit
) {
    val c = Organic.colors
    val uriHandler = LocalUriHandler.current
    val asset = when (state) {
        is UpdateState.Available -> state.asset
        is UpdateState.Downloading -> state.asset
        is UpdateState.NeedsPermission -> state.asset
        is UpdateState.Installing -> state.asset
        is UpdateState.Failed -> state.asset
        UpdateState.Idle -> null
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("OmniVPN ${release.version}", style = OrganicType.groupTitle, color = c.text)
        val size = asset?.sizeBytes?.takeIf { it > 0 }?.let { " · ${formatMegabytes(it)}" }.orEmpty()
        Text("Сейчас установлена $currentVersion$size", style = OrganicType.meta, color = c.neutral600)
    }

    val blocks = remember(release.notes) { releaseNoteBlocks(release.notes) }
    if (blocks.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Что нового", Modifier.offset(x = (-4).dp))
            blocks.forEach { NoteView(it) }
        }
    } else {
        Text(
            "Описание этой версии — на GitHub",
            style = TextStyle(fontSize = 14.sp, textDecoration = TextDecoration.Underline),
            color = c.accent700,
            modifier = Modifier
                .clip(OrganicShapes.sm)
                .clickable(role = Role.Button) {
                    uriHandler.openUri("https://github.com/$GITHUB_REPO/releases/tag/${release.tag}")
                }
                .padding(vertical = 4.dp)
        )
    }

    when (state) {
        is UpdateState.Available -> {
            OrganicButton(
                asset?.sizeBytes?.takeIf { it > 0 }?.let { "Обновить · ${formatMegabytes(it)}" } ?: "Обновить",
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth(),
                icon = OrganicIcons.Download,
                large = true
            )
            if (vpnOn) Note("На время установки VPN отключится и потом подключится снова сам.")
            if (!skipped) {
                OrganicButton(
                    "Пропустить эту версию",
                    onClick = onSkip,
                    kind = ButtonKind.Ghost,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
        is UpdateState.Downloading -> {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Скачиваем · ${downloadProgressText(state.doneBytes, state.totalBytes)}",
                    style = OrganicType.bodyStrong.copy(fontSize = 14.sp, fontFeatureSettings = "tnum"),
                    color = c.text
                )
                DownloadBar(state)
            }
            OrganicButton("Отменить", onClick = onCancel, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.NeedsPermission -> {
            Note("Android должен разрешить OmniVPN устанавливать приложения. Это нужно один раз — включите переключатель и вернитесь сюда.")
            OrganicButton("Разрешить установку", onClick = onAllowInstall, modifier = Modifier.fillMaxWidth(), large = true)
        }
        is UpdateState.Installing -> {
            Note("Подтвердите установку в окне Android. После неё OmniVPN перезапустится.")
        }
        is UpdateState.Failed -> {
            Text(state.message, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = c.accent800)
            OrganicButton(
                if (state.canInstall) "Установить" else "Повторить",
                onClick = onUpdate,
                modifier = Modifier.fillMaxWidth(),
                large = true
            )
        }
        UpdateState.Idle -> Unit
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = OrganicType.secondary, color = Organic.colors.neutral700)
}

@Composable
private fun NoteView(block: NoteBlock) {
    val c = Organic.colors
    when (block) {
        is NoteBlock.Heading -> Text(
            block.text,
            style = OrganicType.bodyStrong,
            color = c.text,
            modifier = Modifier.padding(top = 4.dp)
        )
        is NoteBlock.Paragraph -> Text(block.text, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), color = c.neutral700)
        is NoteBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(top = 8.dp).size(5.dp).clip(CircleShape).background(c.accent))
            Text(
                buildAnnotatedString {
                    block.lead?.let { lead ->
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.text)) { append(lead) }
                        if (block.text.isNotEmpty()) append(' ')
                    }
                    append(block.text)
                },
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                color = c.neutral700,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/** «О приложении»: check for updates, or open the one that's known. */
@Composable
fun UpdateAboutRow(state: UpdateState, checking: Boolean, testBuild: Boolean, onCheck: () -> Unit, onOpen: () -> Unit) {
    val c = Organic.colors
    val known = state !is UpdateState.Idle
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = known || !checking, role = Role.Button, onClick = if (known) onOpen else onCheck)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (known) state.headline() else "Обновления",
                style = TextStyle(fontSize = 14.sp, fontWeight = if (known) FontWeight.SemiBold else FontWeight.Normal),
                color = if (known) c.text else c.neutral700
            )
            Text(
                when {
                    known -> state.detail()
                    testBuild -> "Тестовая сборка: обновляется через компьютер"
                    else -> "С GitHub, проверяются при запуске"
                },
                style = OrganicType.meta,
                color = if (state is UpdateState.Failed) c.accent800 else c.neutral600,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            when {
                known -> "Открыть"
                checking -> "Проверяем…"
                else -> "Проверить"
            },
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = if (checking && !known) c.neutral600 else c.accent700
        )
    }
}
