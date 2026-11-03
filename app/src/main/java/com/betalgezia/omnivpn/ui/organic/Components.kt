package com.betalgezia.omnivpn.ui.organic

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.betalgezia.omnivpn.data.model.Protocol
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType
import kotlinx.coroutines.delay

// Building blocks of the Organic design system (handoff README → «Компоненты»).
// Screens are assembled only from these, so a token change lands everywhere.

// ============================ Buttons ==================================

enum class ButtonKind { Primary, Secondary, Ghost }

/**
 * Pill button set in the heading font, 14sp (16sp and taller when [large],
 * as on the onboarding screens). Primary: accent fill; Secondary: 1dp
 * divider outline; Ghost: accent text only.
 */
@Composable
fun OrganicButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    large: Boolean = false
) {
    val c = Organic.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val container = when (kind) {
        ButtonKind.Primary -> if (pressed) c.accent700 else c.accent
        ButtonKind.Secondary -> if (pressed) c.text.copy(alpha = 0.14f) else Color.Transparent
        ButtonKind.Ghost -> if (pressed) c.accent.copy(alpha = 0.18f) else Color.Transparent
    }
    val content = when (kind) {
        ButtonKind.Primary -> c.bg
        ButtonKind.Secondary -> c.text
        ButtonKind.Ghost -> c.accent
    }
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.45f)
            .clip(OrganicShapes.pill)
            .background(container)
            .then(if (kind == ButtonKind.Secondary) Modifier.border(1.dp, c.divider, OrganicShapes.pill) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .defaultMinSize(minHeight = if (large) 52.dp else 38.dp)
            .padding(
                horizontal = if (kind == ButtonKind.Ghost) 8.dp else 15.8.dp,
                vertical = if (large) 15.dp else 8.8.dp
            ),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(if (large) 18.dp else 15.dp), tint = content)
        Text(
            text,
            style = if (large) OrganicType.button.copy(fontSize = 16.sp, lineHeight = 20.sp) else OrganicType.button,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Round icon button: 36 by default, 44 in navigation and main actions.
 * [background] fills it (e.g. accent for the "+" in the servers header).
 */
@Composable
fun OrganicIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    size: Dp = 36.dp,
    iconSize: Dp = 18.dp,
    tint: Color = Organic.colors.text,
    background: Color = Color.Transparent,
    enabled: Boolean = true
) {
    val c = Organic.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        background != Color.Transparent && pressed -> c.accent700
        background != Color.Transparent -> background
        pressed -> c.text.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(fill)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(iconSize), tint = tint)
    }
}

// ============================ Segmented ================================

/** Pill segmented control; the selected option is filled with accent. */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = Organic.colors
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(OrganicShapes.pill)
            .border(1.dp, c.divider, OrganicShapes.pill)
    ) {
        options.forEachIndexed { index, label ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
            val selected = index == selectedIndex
            val fill by animateColorAsState(if (selected) c.accent else Color.Transparent, tween(200), label = "seg")
            Box(
                modifier = Modifier
                    .background(fill)
                    .clickable(enabled = enabled && !selected, role = Role.RadioButton) { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = OrganicType.secondary.copy(lineHeight = 17.sp),
                    color = if (selected) c.bg else c.text,
                    maxLines = 1
                )
            }
        }
    }
}

// ============================ Tags =====================================

enum class TagTone { Accent, Sage, Neutral, Outline, Solid }

/** 11sp pill label. [small] is the compact 10sp one used inside server rows. */
@Composable
fun Tag(text: String, tone: TagTone, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = Organic.colors
    val (fill, ink) = when (tone) {
        TagTone.Accent -> c.accent100 to c.accent800
        TagTone.Sage -> c.sage100 to c.sage800
        TagTone.Neutral -> c.neutral100 to c.neutral800
        TagTone.Outline -> Color.Transparent to c.accent
        TagTone.Solid -> c.accent to c.bg
    }
    Box(
        modifier = modifier
            .clip(OrganicShapes.pill)
            .background(fill)
            .then(if (tone == TagTone.Outline) Modifier.border(1.dp, c.accent, OrganicShapes.pill) else Modifier)
            .padding(horizontal = if (small) 7.dp else 10.dp, vertical = if (small) 1.dp else 3.dp)
    ) {
        Text(
            text,
            style = OrganicType.tag.copy(
                fontSize = if (small) 10.sp else 11.sp,
                fontWeight = if (tone == TagTone.Solid) FontWeight.Bold else FontWeight.SemiBold,
                letterSpacing = if (tone == TagTone.Solid) 0.08.em else 0.02.em
            ),
            color = ink,
            maxLines = 1
        )
    }
}

fun protocolLabel(protocol: Protocol): String = when (protocol) {
    Protocol.SHADOWSOCKS -> "SS"
    Protocol.HYSTERIA2 -> "HYSTERIA2"
    else -> protocol.name
}

/** Protocol tag colours from the handoff: VLESS accent, Trojan sage, AmneziaWG outline, … */
@Composable
fun ProtocolTag(protocol: Protocol, modifier: Modifier = Modifier, small: Boolean = false) {
    val tone = when (protocol) {
        Protocol.VLESS, Protocol.WARP -> TagTone.Accent
        Protocol.TROJAN -> TagTone.Sage
        Protocol.AMNEZIAWG -> TagTone.Outline
        Protocol.HYSTERIA2, Protocol.TUIC, Protocol.SHADOWSOCKS, Protocol.VMESS -> TagTone.Neutral
    }
    Tag(protocolLabel(protocol), tone, modifier, small)
}

// ============================ Switch ===================================

/**
 * 52×32 pill track, 24 knob in the background colour. On: sage track, knob
 * moved by 20. Off: neutral-300 track. 200 ms. Pass [onCheckedChange] = null
 * when the whole row is the touch target.
 */
@Composable
fun OrganicSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val c = Organic.colors
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, tween(200), label = "knob")
    val track by animateColorAsState(if (checked) c.sage else c.neutral300, tween(200), label = "track")
    Box(
        modifier = modifier
            .size(52.dp, 32.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(OrganicShapes.pill)
            .background(track)
            .then(
                if (onCheckedChange != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
                } else {
                    Modifier
                }
            )
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .offset(x = knob)
                .size(24.dp)
                .shadow(1.dp, CircleShape, ambientColor = c.shadow, spotColor = c.shadow)
                .background(c.bg, CircleShape)
        )
    }
}

// ============================ Input ====================================

/**
 * Surface-filled input with a 1dp divider outline (accent when focused).
 * Single-line inputs are pills; multi-line ones get the 16 radius.
 */
@Composable
fun OrganicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    mono: Boolean = false,
    minHeight: Dp = 48.dp,
    textStyle: TextStyle = if (mono) OrganicType.mono.copy(fontSize = 13.sp, lineHeight = 18.sp) else OrganicType.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    enabled: Boolean = true
) {
    val c = Organic.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = if (singleLine) OrganicShapes.pill else OrganicShapes.md
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle.copy(color = c.text),
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = keyboardOptions,
        interactionSource = interaction,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .heightIn(min = minHeight)
                    .clip(shape)
                    .background(c.surface)
                    .border(1.dp, if (focused) c.accent else c.divider, shape)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart
            ) {
                if (value.isEmpty()) Text(placeholder, style = textStyle, color = c.neutral600, maxLines = if (singleLine) 1 else 4)
                inner()
            }
        }
    )
}

// ============================ Cards & rows =============================

/** Surface card, radius ≈32. */
@Composable
fun OrganicCard(
    modifier: Modifier = Modifier,
    color: Color = Organic.colors.surface,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    spacing: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(OrganicShapes.card)
            .background(color)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** UPPERCASE 12sp section label, neutral-600. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = OrganicType.sectionLabel,
        color = Organic.colors.neutral600,
        modifier = modifier.padding(start = 4.dp)
    )
}

/** 1dp divider between rows inside a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Organic.colors.divider))
}

/** Round 40 (or [size]) plate with an icon - the leading mark of settings rows. */
@Composable
fun IconPlate(icon: ImageVector, plate: Color, tint: Color, size: Dp = 40.dp, iconSize: Dp = 18.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(plate), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize), tint = tint)
    }
}

/** Settings link row: plate, title, current value, chevron. */
@Composable
fun SettingsLinkRow(
    icon: ImageVector,
    plate: Color,
    iconTint: Color,
    title: String,
    summary: String,
    onClick: () -> Unit,
    titleColor: Color = Organic.colors.text,
    enabled: Boolean = true,
    /** false for an action row (it does something here rather than open a page). */
    chevron: Boolean = true
) {
    val c = Organic.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.55f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconPlate(icon, plate, iconTint)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = OrganicType.bodyStrong, color = titleColor)
            if (summary.isNotEmpty()) Text(summary, style = OrganicType.secondary, color = c.neutral700)
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            Icon(OrganicIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(18.dp), tint = c.neutral600)
        }
    }
}

/** Settings row with a switch; the whole row toggles. */
@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    plate: Color = Color.Transparent,
    iconTint: Color = Organic.colors.text
) {
    val c = Organic.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            IconPlate(icon, plate, iconTint)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = OrganicType.bodyStrong)
            if (summary.isNotEmpty()) Text(summary, style = OrganicType.secondary, color = c.neutral700)
        }
        Spacer(Modifier.width(12.dp))
        OrganicSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Settings row with a segmented control and a line describing the current choice. */
@Composable
fun SettingsSegmentRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    description: String,
    enabled: Boolean = true
) {
    val c = Organic.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = OrganicType.bodyStrong, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Segmented(options, selectedIndex, onSelect, enabled = enabled)
        }
        if (description.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(description, style = OrganicType.secondary, color = c.neutral700)
        }
    }
}

// ============================ Latency ==================================

/** Three bars 4 wide, 5/9/13 high, radius 2, gap 2; [active] of them lit. */
@Composable
fun LatencyBars(active: Int, activeColor: Color, inactiveColor: Color, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(5.dp, 9.dp, 13.dp).forEachIndexed { index, h ->
            Box(
                Modifier
                    .size(4.dp, h)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (index < active) activeColor else inactiveColor)
            )
        }
    }
}

/** How a latency reads: label colour, bar colour and how many bars are lit. */
data class LatencyLook(val label: String, val labelColor: Color, val barColor: Color, val bars: Int)

/**
 * The handoff's one latency scale for server rows and the home screen:
 * <100 fast, 100–299 fine, 300–999 slow, ≥1000 very slow.
 */
@Composable
fun latencyLook(latencyMs: Long): LatencyLook {
    val c = Organic.colors
    val label = "$latencyMs мс"
    return when {
        latencyMs < 100 -> LatencyLook(label, c.sage800, c.sage600, 3)
        latencyMs < 300 -> LatencyLook(label, c.sage700, c.sage400, 3)
        latencyMs < 1000 -> LatencyLook(label, c.accent700, c.accent500, 2)
        else -> LatencyLook(label, c.accent800, c.accent700, 1)
    }
}

// ============================ Dialog ===================================

/**
 * Surface dialog, radius ≈32: heading-font title, body, actions on the right
 * (secondary «Отмена», primary action). [content] goes between body and actions.
 */
@Composable
fun OrganicDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    body: String? = null,
    dismissLabel: String = "Отмена",
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val c = Organic.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 22.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .shadow(16.dp, OrganicShapes.card, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(OrganicShapes.card)
                .background(c.surface)
                .padding(17.6.dp),
            verticalArrangement = Arrangement.spacedBy(13.2.dp)
        ) {
            Text(title, style = OrganicType.dialogTitle)
            if (body != null) Text(body, style = OrganicType.bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp), color = c.text.copy(alpha = 0.85f))
            content?.invoke(this)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.8.dp, Alignment.End)
            ) {
                OrganicButton(dismissLabel, onDismiss, kind = ButtonKind.Secondary)
                OrganicButton(confirmLabel, onConfirm, enabled = confirmEnabled)
            }
        }
    }
}

// ============================ Snackbar =================================

/**
 * What the snackbar shows. Shared with the window so that any tap on the
 * screen can [dismiss] it at once (see MainActivity's shell).
 */
@Stable
class OrganicSnackbarState {
    var text by mutableStateOf("")
        private set
    var visible by mutableStateOf(false)
        private set
    // Bumped on every new message: restarts the hide timer.
    var shown by mutableIntStateOf(0)
        private set

    fun show(message: String) {
        text = message
        visible = true
        shown++
    }

    fun dismiss() {
        visible = false
    }
}

@Composable
fun rememberOrganicSnackbarState(): OrganicSnackbarState = remember { OrganicSnackbarState() }

/**
 * Text-coloured pill-ish bar with background-coloured text; slides up 16 and
 * fades in over 200 ms, leaves by itself after ~2.5 s (longer texts 4 s) or
 * at the first tap anywhere. Place it above the bottom bar.
 */
@Composable
fun OrganicSnackbarHost(
    message: String?,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
    state: OrganicSnackbarState = rememberOrganicSnackbarState()
) {
    val c = Organic.colors
    // Taking the message clears it in the view model (onShown), which changes
    // this key again - so the timer can't live here: it used to be cancelled
    // right away and the bar never went away.
    LaunchedEffect(message) {
        if (!message.isNullOrBlank()) {
            state.show(message)
            onShown()
        }
    }
    LaunchedEffect(state.shown) {
        if (state.visible) {
            delay(if (state.text.length > 90) 4_000 else 2_500)
            state.dismiss()
        }
    }
    AnimatedVisibility(
        visible = state.visible,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 3 },
        exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 3 }
    ) {
        Text(
            state.text,
            style = OrganicType.snackbar,
            color = c.bg,
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .shadow(6.dp, OrganicShapes.md, ambientColor = c.shadow, spotColor = c.shadow)
                .clip(OrganicShapes.md)
                .background(c.text)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

// ============================ Navigation ===============================

enum class AppTab(val label: String, val icon: ImageVector) {
    HOME("Главная", OrganicIcons.Power),
    SERVERS("Серверы", OrganicIcons.Server),
    SETTINGS("Настройки", OrganicIcons.SlidersHorizontal)
}

/** Bottom bar: 3 columns, 64×32 pill indicator behind the active icon. */
@Composable
fun OrganicBottomNav(selected: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    val c = Organic.colors
    Column(modifier.fillMaxWidth().background(c.bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp)
        ) {
            AppTab.entries.forEach { tab ->
                val active = tab == selected
                val pill by animateColorAsState(if (active) c.accent200 else Color.Transparent, tween(200), label = "nav")
                val ink = if (active) c.accent800 else c.neutral700
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier.size(64.dp, 32.dp).clip(OrganicShapes.pill).background(pill),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(tab.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = ink)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tab.label, style = OrganicType.navLabel, color = ink)
                }
            }
        }
    }
}

// ============================ Brand mark ===============================

/**
 * The «Орбита» mark: a terracotta ring open in its upper-right quarter with a
 * sage dot in the gap (same geometry as the launcher icon, cropped to the mark).
 */
@Composable
fun OrbitMark(modifier: Modifier = Modifier.size(32.dp)) {
    Canvas(modifier) {
        // Launcher geometry on a 108 grid; the mark spans 27…81 (ring + stroke).
        val unit = size.minDimension / 54f
        fun p(v: Float) = (v - 27f) * unit
        val stroke = 10f * unit
        drawArc(
            color = Color(0xFFC67139),
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(p(32f), p(32f)),
            size = Size(44f * unit, 44f * unit),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawCircle(color = Color(0xFF7A8A5E), radius = 8.5f * unit, center = Offset(p(69.56f), p(38.44f)))
    }
}
