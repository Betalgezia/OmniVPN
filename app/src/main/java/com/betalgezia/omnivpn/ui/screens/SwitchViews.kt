package com.betalgezia.omnivpn.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.betalgezia.omnivpn.ui.organic.IconPlate
import com.betalgezia.omnivpn.ui.organic.OrganicBottomSheet
import com.betalgezia.omnivpn.ui.organic.OrganicCard
import com.betalgezia.omnivpn.ui.organic.OrganicIconButton
import com.betalgezia.omnivpn.ui.organic.OrganicIcons
import com.betalgezia.omnivpn.ui.organic.RowDivider
import com.betalgezia.omnivpn.ui.organic.SheetHeader
import com.betalgezia.omnivpn.ui.organic.Tag
import com.betalgezia.omnivpn.ui.organic.TagTone
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.vpn.SpeedMeasuring
import com.betalgezia.omnivpn.vpn.SwitchEvent
import com.betalgezia.omnivpn.vpn.SwitchKind
import kotlinx.coroutines.delay

// The automatic-switch card on the home screen (handoff §1.4) and the
// «Переключения» journal sheet (§4a).

private data class EventLook(val icon: ImageVector, val plate: Color, val ink: Color)

/** Switched → sage plate; a measurement or «stayed» → accent. Gauge for slow, unplug for dead. */
@Composable
private fun eventLook(kind: SwitchKind?, measuring: Boolean): EventLook {
    val c = Organic.colors
    val switched = !measuring && (kind == SwitchKind.SLOW || kind == SwitchKind.DEAD)
    return EventLook(
        icon = if (!measuring && kind == SwitchKind.DEAD) OrganicIcons.Unplug else OrganicIcons.Gauge,
        plate = if (switched) c.sage200 else c.accent200,
        ink = if (switched) c.sage800 else c.accent800
    )
}

@Composable
fun SwitchCard(
    notice: SwitchEvent?,
    measuring: SpeedMeasuring?,
    journalSize: Int,
    onDismiss: () -> Unit,
    onOpenJournal: () -> Unit
) {
    val c = Organic.colors
    // «только что» → «N мин назад» moves on by itself.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(notice) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val showEvent = measuring != null || notice != null

    OrganicCard(spacing = 14.dp) {
        if (showEvent) {
            val look = eventLook(notice?.kind, measuring != null)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    IconPlate(look.icon, plate = look.plate, tint = look.ink, size = 40.dp, iconSize = 19.dp)
                    if (measuring != null) PlateSpinner(look.ink)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        if (measuring != null) measuringTitle(measuring) else noticeTitle(notice!!),
                        style = TextStyle(fontSize = 15.sp, lineHeight = 19.5.sp, fontWeight = FontWeight.Bold),
                        color = c.text
                    )
                    Text(
                        if (measuring != null) measuringReason(measuring) else noticeReason(notice!!),
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.85.sp),
                        color = c.neutral700
                    )
                    Text(
                        if (measuring != null) "сейчас" else relativeTime(notice!!.atMs, now),
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
                        color = c.neutral600,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                // Nothing to hide while a measurement runs.
                if (measuring == null) {
                    OrganicIconButton(
                        OrganicIcons.X,
                        onClick = onDismiss,
                        modifier = Modifier.offset(x = 8.dp, y = (-8).dp),
                        contentDescription = "Скрыть",
                        tint = c.neutral600
                    )
                }
            }
        }
        if (journalSize > 0) {
            Column {
                if (showEvent) {
                    RowDivider()
                    Spacer(Modifier.height(12.dp))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(OrganicShapes.sm)
                        .clickable(role = Role.Button, onClick = onOpenJournal),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(OrganicIcons.History, contentDescription = null, modifier = Modifier.size(17.dp), tint = c.accent700)
                    Text(
                        "Журнал переключений",
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        color = c.text,
                        modifier = Modifier.weight(1f)
                    )
                    Tag("$journalSize", TagTone.Neutral)
                    Icon(OrganicIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp), tint = c.neutral600)
                }
            }
        }
    }
}

/** A measurement runs: an arc turns around the plate (5 outside it, 3 thick, once a second). */
@Composable
private fun PlateSpinner(color: Color) {
    val rotation by rememberInfiniteTransition(label = "plateSpin").animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1_000, easing = LinearEasing), RepeatMode.Restart),
        label = "plateSpinAngle"
    )
    Canvas(Modifier.requiredSize(50.dp)) {
        val stroke = 3.dp.toPx()
        val radius = size.minDimension / 2 - stroke / 2
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

/** «Переключения»: the automatic switches, newest first. */
@Composable
fun SwitchJournalSheet(visible: Boolean, events: List<SwitchEvent>, onDismiss: () -> Unit) {
    val c = Organic.colors
    OrganicBottomSheet(visible = visible, onDismiss = onDismiss, maxHeightFraction = 0.75f, spacing = 12.dp) {
        SheetHeader("Переключения", onClose = onDismiss)
        Text(
            "Автоматические переходы между серверами подписки. Серверы, выбранные вручную, сюда не попадают.",
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.85.sp),
            color = c.neutral700
        )
        Column {
            events.forEach { event ->
                val look = eventLook(event.kind, measuring = false)
                RowDivider()
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconPlate(look.icon, plate = look.plate, tint = look.ink, size = 36.dp, iconSize = 17.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                journalRoute(event),
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                                color = c.text,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                clockLabel(event.atMs),
                                style = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum"),
                                color = c.neutral600
                            )
                        }
                        Text(journalReason(event), style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp), color = c.neutral700)
                    }
                }
            }
        }
    }
}
