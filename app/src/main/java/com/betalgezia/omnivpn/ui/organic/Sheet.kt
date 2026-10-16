package com.betalgezia.omnivpn.ui.organic

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.betalgezia.omnivpn.ui.theme.Organic
import com.betalgezia.omnivpn.ui.theme.OrganicShapes
import com.betalgezia.omnivpn.ui.theme.OrganicType

/**
 * The handoff's bottom sheet: neutral-900 α45 scrim (tap closes), bg sheet
 * with 28 top corners, handle 40×5, padding 10/22/26, up from the bottom in
 * 250 ms. Scrolls when taller than [maxHeightFraction] of the window. Drawn
 * over the whole window, so it goes last in the shell; system back closes it.
 */
@Composable
fun OrganicBottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    maxHeightFraction: Float = 0.92f,
    spacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = Organic.colors
    BackHandler(enabled = visible, onBack = onDismiss)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxSheetHeight = maxHeight * maxHeightFraction
        AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(200))) {
            Box(
                Modifier
                    .fillMaxSize()
                    // The dark theme mirrors the ramps, so its neutral-900 is light: use black there.
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
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .shadow(16.dp, shape, ambientColor = c.shadow, spotColor = c.shadow)
                    .clip(shape)
                    .background(c.bg)
                    // Taps on the sheet itself must not reach the scrim below.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                    .verticalScroll(rememberScrollState())
                    .padding(start = 22.dp, end = 22.dp, top = 10.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(spacing)
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(40.dp, 5.dp)
                        .clip(OrganicShapes.pill)
                        .background(c.neutral300)
                )
                content()
            }
        }
    }
}

/** Sheet title (24, heading font) with the close cross on the right. */
@Composable
fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = OrganicType.pageTitle, color = Organic.colors.text, modifier = Modifier.weight(1f))
        OrganicIconButton(OrganicIcons.X, onClick = onClose, contentDescription = "Закрыть", iconSize = 20.dp)
    }
}
