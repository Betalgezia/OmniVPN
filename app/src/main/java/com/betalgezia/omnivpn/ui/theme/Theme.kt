package com.betalgezia.omnivpn.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun omniVpnDarkColorScheme() = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color(0xFF00174D),
    primaryContainer = Color(0xFF1F3B8C),
    onPrimaryContainer = Color(0xFFDCE2FF),
    secondary = WarpOrangeDark,
    onSecondary = Color(0xFF3D1F00),
    background = BackgroundDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    error = StatusErrorDark,
    onError = Color(0xFF3D0300)
)

private fun omniVpnLightColorScheme() = lightColorScheme(
    primary = AccentBlueLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE2FF),
    onPrimaryContainer = Color(0xFF00174D),
    secondary = WarpOrangeLight,
    onSecondary = Color.White,
    background = BackgroundLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    error = StatusErrorLight,
    onError = Color.White
)

/**
 * App-wide Material3 theme. Neither color scheme uses dynamic/Material-You
 * colors on purpose - a VPN client's "connected" state leans on a specific
 * green/red vocabulary (see [OmniVpnStatusColors]) that needs to stay
 * legible and consistent regardless of the user's wallpaper.
 */
@Composable
fun OmniVpnTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) omniVpnDarkColorScheme() else omniVpnLightColorScheme()

    // targetSdk 36 means the OS enforces edge-to-edge regardless of what this
    // app asks for, so status-bar icon contrast has to be set explicitly -
    // left alone it can default to dark-on-dark in the new dark theme. Skipped
    // in edit-mode/preview, where there is no real Activity/window behind the view.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

/**
 * Semantic status colors outside Material3's colorScheme slots (which has no
 * dedicated "success"/"warning"). Each call reads the current system theme
 * directly rather than threading a Boolean through every call site.
 */
object OmniVpnStatusColors {
    val connected: Color
        @Composable get() = if (isSystemInDarkTheme()) StatusConnectedDark else StatusConnectedLight

    val warning: Color
        @Composable get() = if (isSystemInDarkTheme()) StatusWarningDark else StatusWarningLight

    val error: Color
        @Composable get() = if (isSystemInDarkTheme()) StatusErrorDark else StatusErrorLight

    val warp: Color
        @Composable get() = if (isSystemInDarkTheme()) WarpOrangeDark else WarpOrangeLight
}
