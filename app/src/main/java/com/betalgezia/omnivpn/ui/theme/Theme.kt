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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.core.view.WindowCompat
import com.betalgezia.omnivpn.R

private fun omniVpnDarkColorScheme() = darkColorScheme(
    primary = AccentBlueDark,
    onPrimary = Color(0xFF161826),
    primaryContainer = AccentBlueDark,
    onPrimaryContainer = Color(0xFF161826),
    secondary = WarpOrangeDark,
    onSecondary = Color(0xFF2B1A0C),
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

// Inter via the Google Fonts downloadable-font provider (see the Nocturne v2
// handoff: "подключить через res/font или downloadable fonts; допустим
// fallback на системный sans"). Bundling the TTFs would be ideal, but the
// downloadable provider needs no binary asset and falls back cleanly: every
// Font() below lists FontFamily.SansSerif as its fallback, so if the provider
// is missing/slow (e.g. a device without the Play font provider) text still
// renders in the system sans immediately rather than blocking.
private val googleFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val interGoogleFont = GoogleFont("Inter")

val InterFontFamily = FontFamily(
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Normal),
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Medium),
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.SemiBold),
    Font(googleFont = interGoogleFont, fontProvider = googleFontProvider, weight = FontWeight.Bold)
)

// Every Material3 type role gets Inter as its family; sizes stay at the M3
// defaults since the redesign sets explicit sizes at each call site. The one
// house rule from the handoff ("заголовки не жирнее 500") is applied by using
// FontWeight.Medium at those call sites rather than baking it in here.
private fun omniVpnTypography(): Typography {
    val base = Typography()
    fun androidx.compose.ui.text.TextStyle.inter() = copy(fontFamily = InterFontFamily)
    return Typography(
        displayLarge = base.displayLarge.inter(),
        displayMedium = base.displayMedium.inter(),
        displaySmall = base.displaySmall.inter(),
        headlineLarge = base.headlineLarge.inter(),
        headlineMedium = base.headlineMedium.inter(),
        headlineSmall = base.headlineSmall.inter(),
        titleLarge = base.titleLarge.inter(),
        titleMedium = base.titleMedium.inter(),
        titleSmall = base.titleSmall.inter(),
        bodyLarge = base.bodyLarge.inter(),
        bodyMedium = base.bodyMedium.inter(),
        bodySmall = base.bodySmall.inter(),
        labelLarge = base.labelLarge.inter(),
        labelMedium = base.labelMedium.inter(),
        labelSmall = base.labelSmall.inter()
    )
}

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
        typography = omniVpnTypography(),
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
