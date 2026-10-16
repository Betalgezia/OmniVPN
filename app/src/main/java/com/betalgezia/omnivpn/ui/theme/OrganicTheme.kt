package com.betalgezia.omnivpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.betalgezia.omnivpn.R

// ---- Type ----
// Headings: the handoff's Caprasimo has no Cyrillic, so - as the handoff says
// - Alegreya ExtraBold, bundled (res/font) so it never depends on a font
// provider. Body text: the system sans (Roboto), Figtree has no Cyrillic either.

val AlegreyaExtraBold = FontFamily(Font(R.font.alegreya_extrabold, FontWeight.ExtraBold))

private val heading = TextStyle(fontFamily = AlegreyaExtraBold, fontWeight = FontWeight.ExtraBold)
private val body = TextStyle(fontFamily = FontFamily.Default)
private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** Text roles from the handoff (sizes in sp, line heights as multiples). */
object OrganicType {
    val heroTitle = heading.copy(fontSize = 34.sp, lineHeight = 36.7.sp, letterSpacing = (-0.015).em, lineHeightStyle = trim)
    val onboardingTitle = heading.copy(fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.015).em)
    val tabTitle = heading.copy(fontSize = 30.sp, lineHeight = 34.sp)
    val pageTitle = heading.copy(fontSize = 24.sp, lineHeight = 28.sp)
    val dialogTitle = heading.copy(fontSize = 20.sp, lineHeight = 24.sp)
    val appName = heading.copy(fontSize = 20.sp, lineHeight = 24.sp)
    val groupTitle = heading.copy(fontSize = 18.sp, lineHeight = 22.sp)
    val warpTitle = heading.copy(fontSize = 17.sp, lineHeight = 21.sp)
    val button = heading.copy(fontSize = 14.sp, lineHeight = 18.sp)

    val bodyLarge = body.copy(fontSize = 15.sp, lineHeight = 22.sp)
    val bodyStrong = body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val secondary = body.copy(fontSize = 13.sp, lineHeight = 19.sp)
    val meta = body.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val navLabel = body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val sectionLabel = body.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.08.em)
    val tag = body.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold)
    val snackbar = body.copy(fontSize = 14.sp, lineHeight = 20.sp)
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
}

// ---- Shapes & spacing ----

object OrganicShapes {
    val sm = RoundedCornerShape(8.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(28.dp)
    /** Cards and dialogs: lg × 1.15. */
    val card = RoundedCornerShape(32.dp)
    /** Buttons, tags, segments, inputs. */
    val pill = RoundedCornerShape(percent = 50)
}

/** Spacing scale 4.4 / 8.8 / 13.2 / 17.6 / 26.4 / 35.2. */
object OrganicSpace {
    val xs = 4.4.dp
    val sm = 8.8.dp
    val md = 13.2.dp
    val lg = 17.6.dp
    val xl = 26.4.dp
    val xxl = 35.2.dp
}

// ---- Theme ----

val LocalOrganicColors = staticCompositionLocalOf { OrganicLight }

/** Read the current Organic palette: `Organic.colors.accent700` etc. */
object Organic {
    val colors: OrganicColors
        @Composable @ReadOnlyComposable get() = LocalOrganicColors.current
}

@Composable
fun OrganicTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) OrganicDark else OrganicLight

    // targetSdk 36 forces edge-to-edge: system-bar icons have to follow the
    // theme explicitly, or they end up dark-on-dark / light-on-light.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insets = WindowCompat.getInsetsController(window, view)
            insets.isAppearanceLightStatusBars = !dark
            insets.isAppearanceLightNavigationBars = !dark
            @Suppress("DEPRECATION")
            window.navigationBarColor = colors.bg.toArgb()
        }
    }

    // Material components that are still used (sheets, text fields' cursor,
    // ripples) pick their colours from this mapping.
    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent, onPrimary = colors.bg,
            secondary = colors.sage, onSecondary = colors.bg,
            background = colors.bg, onBackground = colors.text,
            surface = colors.bg, onSurface = colors.text,
            surfaceVariant = colors.surface, onSurfaceVariant = colors.neutral700,
            surfaceContainerLow = colors.bg, surfaceContainer = colors.surface,
            outline = colors.divider, error = colors.accent800
        )
    } else {
        lightColorScheme(
            primary = colors.accent, onPrimary = colors.bg,
            secondary = colors.sage, onSecondary = colors.bg,
            background = colors.bg, onBackground = colors.text,
            surface = colors.bg, onSurface = colors.text,
            surfaceVariant = colors.surface, onSurfaceVariant = colors.neutral700,
            surfaceContainerLow = colors.bg, surfaceContainer = colors.surface,
            outline = colors.divider, error = colors.accent800
        )
    }

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalOrganicColors provides colors,
            LocalContentColor provides colors.text,
            content = content
        )
    }
}
