package com.betalgezia.omnivpn.ui.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.betalgezia.omnivpn.MainViewModel
import com.betalgezia.omnivpn.addErrorText
import com.betalgezia.omnivpn.ui.organic.ButtonKind
import com.betalgezia.omnivpn.ui.organic.OrganicButton
import com.betalgezia.omnivpn.ui.organic.OrganicIconButton
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.OrganicTextField
import com.betalgezia.omnivpn.ui.organic.Segmented
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AddMode(val label: String) { SUBSCRIPTION("Подписка"), CONFIG("Конфигурация"), FILE("Файл") }

/**
 * «Добавить сервер» bottom sheet (handoff README §4): a subscription link, a
 * pasted config or a file. Errors stay in the sheet; on success it closes
 * ([onAdded]) and the snackbar says what was added. Drawn over the whole
 * window (scrim included), so it is placed last in the shell.
 */
@Composable
fun AddServerSheet(
    visible: Boolean,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onAdded: () -> Unit,
    /** Open on this tab (e.g. «Добавить подписку» on the Подписки page); null keeps the last one. */
    startMode: AddMode? = null
) {
    val c = Organic.colors
    val adding by viewModel.adding.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(AddMode.SUBSCRIPTION) }
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var readingFile by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(visible) {
        if (visible) {
            text = ""
            error = null
            if (startMode != null) mode = startMode
        }
    }

    val done: (String?) -> Unit = { problem ->
        if (problem == null) {
            text = ""
            onAdded()
        } else {
            error = problem
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            readingFile = true
            error = null
            runCatching { readPickedFile(context, uri) }
                .onSuccess { (content, name) -> viewModel.importConfig(content, name ?: "Файл", done) }
                .onFailure { error = addErrorText(it) }
            readingFile = false
        }
    }
    val paste: () -> Unit = {
        clipboard.getText()?.text?.trim()?.takeIf { it.isNotEmpty() }?.let {
            text = it
            error = null
        }
    }
    val submit: () -> Unit = {
        when (mode) {
            AddMode.SUBSCRIPTION -> {
                val problem = subscriptionUrlError(text)
                if (problem != null) error = problem else viewModel.addSubscription(text, done)
            }
            AddMode.CONFIG -> {
                val problem = configTextError(text)
                if (problem != null) error = problem else viewModel.importConfig(text, onResult = done)
            }
            AddMode.FILE -> Unit
        }
    }

    BackHandler(enabled = visible, onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (c.isDark) Color.Black.copy(alpha = 0.55f) else c.neutral900.copy(alpha = 0.45f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Закрыть",
                        onClick = onDismiss
                    )
            )
        }
        AnimatedVisibility(
            visible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(250)) { it },
            exit = slideOutVertically(tween(200)) { it }
        ) {
            val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .shadow(16.dp, sheetShape, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(sheetShape)
                    .background(c.bg)
                    // Taps on the sheet itself must not reach the scrim below.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(40.dp, 5.dp)
                        .clip(OrganicShapes.pill)
                        .background(c.neutral300)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Добавить сервер", style = OrganicType.pageTitle, color = c.text, modifier = Modifier.weight(1f))
                    OrganicIconButton(OrganicIcons.X, onClick = onDismiss, contentDescription = "Закрыть", iconSize = 20.dp)
                }
                Segmented(
                    options = AddMode.entries.map { it.label },
                    selectedIndex = mode.ordinal,
                    onSelect = {
                        mode = AddMode.entries[it]
                        text = ""
                        error = null
                    },
                    enabled = !adding && !readingFile
                )

                when (mode) {
                    AddMode.SUBSCRIPTION -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Ссылка на подписку")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OrganicTextField(
                                value = text,
                                onValueChange = {
                                    text = it
                                    error = null
                                },
                                modifier = Modifier.weight(1f),
                                placeholder = "https://…",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                                enabled = !adding
                            )
                            OrganicButton("Вставить", onClick = paste, kind = ButtonKind.Secondary, enabled = !adding)
                        }
                        Text(
                            "Только HTTPS. Серверы подписки участвуют в поиске быстрейшего и автопереключении.",
                            style = OrganicType.meta,
                            color = c.neutral600
                        )
                    }
                    AddMode.CONFIG -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Ссылка или текст конфигурации")
                        OrganicTextField(
                            value = text,
                            onValueChange = {
                                text = it
                                error = null
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            placeholder = "vless://… · trojan://… · hy2://… · JSON · YAML",
                            singleLine = false,
                            mono = true,
                            minHeight = 110.dp,
                            enabled = !adding
                        )
                        // Ghost buttons carry 8dp side padding; line the label up with the field.
                        OrganicButton(
                            "Вставить",
                            onClick = paste,
                            kind = ButtonKind.Ghost,
                            enabled = !adding,
                            modifier = Modifier.offset(x = (-8).dp)
                        )
                    }
                    AddMode.FILE -> FileZone(
                        busy = adding || readingFile,
                        onPick = { picker.launch(arrayOf("*/*")) }
                    )
                }

                error?.let {
                    Text(
                        it,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        color = c.accent800,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(OrganicShapes.md)
                            .background(c.accent100)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }

                if (mode != AddMode.FILE) {
                    OrganicButton(
                        text = when {
                            mode == AddMode.SUBSCRIPTION && adding -> "Загружаем подписку…"
                            mode == AddMode.SUBSCRIPTION -> "Добавить подписку"
                            adding -> "Импортируем…"
                            else -> "Импортировать"
                        },
                        onClick = submit,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !adding,
                        large = true
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold), color = Organic.colors.neutral700)
}

/** Dashed drop-zone look: file-up, «Выбрать файл», accepted types. */
@Composable
private fun FileZone(busy: Boolean, onPick: () -> Unit) {
    val c = Organic.colors
    val dash = c.neutral400
    Column(
        Modifier
            .fillMaxWidth()
            .clip(OrganicShapes.lg)
            .background(c.surface)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawRoundRect(
                    color = dash,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(28.dp.toPx() - stroke / 2),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())))
                )
            }
            .clickable(enabled = !busy, role = Role.Button, onClick = onPick)
            .padding(horizontal = 16.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(OrganicIcons.FileUp, contentDescription = null, modifier = Modifier.size(28.dp), tint = c.accent700)
        Text(if (busy) "Импортируем…" else "Выбрать файл", style = OrganicType.bodyStrong, color = c.text)
        Text(".conf, .json, .yaml · до 5 МБ", style = OrganicType.meta, color = c.neutral600)
    }
}

private const val MAX_IMPORT_BYTES = 5 * 1024 * 1024

/** The picked file's text (≤ 5 MB) and its display name, if the provider gives one. */
private suspend fun readPickedFile(context: Context, uri: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
    val name = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
    val text = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_IMPORT_BYTES) { "Import file is too large" }
            out.write(buffer, 0, count)
        }
        out.toString(Charsets.UTF_8.name())
    } ?: error("Unable to open selected file")
    text to name
}
