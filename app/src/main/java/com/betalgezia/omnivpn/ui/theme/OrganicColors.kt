package com.betalgezia.omnivpn.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The Organic design system (October 2026 handoff,
 * design_handoff_omnivpn_organic/README.md): warm paper grounds, terracotta
 * accent, sage second accent. Every screen reads colours by role from here,
 * and the light and dark palettes carry the same roles, so a screen switches
 * theme without a single `if (dark)`.
 *
 * Ramps go 100 (lightest in the light theme) → 900. The dark theme mirrors
 * them: step N in dark = step (1000 − N) in light, 500 stays.
 */
@Immutable
data class OrganicColors(
    val isDark: Boolean,
    val bg: Color,
    val surface: Color,
    val text: Color,
    /** Terracotta - main accent. */
    val accent: Color,
    /** Sage - second accent: "connected to a server", switches. */
    val sage: Color,
    val divider: Color,
    val shadow: Color,

    val neutral100: Color, val neutral200: Color, val neutral300: Color,
    val neutral400: Color, val neutral500: Color, val neutral600: Color,
    val neutral700: Color, val neutral800: Color, val neutral900: Color,

    val accent100: Color, val accent200: Color, val accent300: Color,
    val accent400: Color, val accent500: Color, val accent600: Color,
    val accent700: Color, val accent800: Color, val accent900: Color,

    val sage100: Color, val sage200: Color, val sage300: Color,
    val sage400: Color, val sage500: Color, val sage600: Color,
    val sage700: Color, val sage800: Color, val sage900: Color
)

private val n = listOf(0xFFF9F4ED, 0xFFEEE7DB, 0xFFDCD3C4, 0xFFC0B6A5, 0xFFA19786, 0xFF82796A, 0xFF645C50, 0xFF474238, 0xFF2E2B25).map { Color(it) }
private val a = listOf(0xFFFFF2EB, 0xFFFFE1D0, 0xFFFFC6A5, 0xFFF6A06B, 0xFFD67F48, 0xFFB2622D, 0xFF8C491A, 0xFF643312, 0xFF402310).map { Color(it) }
private val s = listOf(0xFFF0FAE1, 0xFFE1EECC, 0xFFCCDBB2, 0xFFAEBF92, 0xFF8FA073, 0xFF728157, 0xFF56633F, 0xFF3D472B, 0xFF272E1B).map { Color(it) }

/** Ramp step for 100…900, light theme. */
private fun List<Color>.at(step: Int) = this[step / 100 - 1]

/** Ramp step for 100…900, dark theme (mirrored). */
private fun List<Color>.darkAt(step: Int) = at(1000 - step)

val OrganicLight = OrganicColors(
    isDark = false,
    bg = Color(0xFFF5EAD8),
    surface = Color(0xFFEBDDC5),
    text = Color(0xFF201E1D),
    accent = Color(0xFFC67139),
    sage = Color(0xFF7A8A5E),
    divider = Color(0xFF201E1D).copy(alpha = 0.16f),
    shadow = Color(0xFF2E2B25),
    neutral100 = n.at(100), neutral200 = n.at(200), neutral300 = n.at(300),
    neutral400 = n.at(400), neutral500 = n.at(500), neutral600 = n.at(600),
    neutral700 = n.at(700), neutral800 = n.at(800), neutral900 = n.at(900),
    accent100 = a.at(100), accent200 = a.at(200), accent300 = a.at(300),
    accent400 = a.at(400), accent500 = a.at(500), accent600 = a.at(600),
    accent700 = a.at(700), accent800 = a.at(800), accent900 = a.at(900),
    sage100 = s.at(100), sage200 = s.at(200), sage300 = s.at(300),
    sage400 = s.at(400), sage500 = s.at(500), sage600 = s.at(600),
    sage700 = s.at(700), sage800 = s.at(800), sage900 = s.at(900)
)

val OrganicDark = OrganicColors(
    isDark = true,
    bg = Color(0xFF201E1D),
    surface = Color(0xFF2E2B25),
    text = Color(0xFFF9F4ED),
    accent = Color(0xFFD67F48),
    sage = Color(0xFF8FA073),
    divider = Color(0xFFF9F4ED).copy(alpha = 0.16f),
    shadow = Color.Black,
    neutral100 = n.darkAt(100), neutral200 = n.darkAt(200), neutral300 = n.darkAt(300),
    neutral400 = n.darkAt(400), neutral500 = n.darkAt(500), neutral600 = n.darkAt(600),
    neutral700 = n.darkAt(700), neutral800 = n.darkAt(800), neutral900 = n.darkAt(900),
    accent100 = a.darkAt(100), accent200 = a.darkAt(200), accent300 = a.darkAt(300),
    accent400 = a.darkAt(400), accent500 = a.darkAt(500), accent600 = a.darkAt(600),
    accent700 = a.darkAt(700), accent800 = a.darkAt(800), accent900 = a.darkAt(900),
    sage100 = s.darkAt(100), sage200 = s.darkAt(200), sage300 = s.darkAt(300),
    sage400 = s.darkAt(400), sage500 = s.darkAt(500), sage600 = s.darkAt(600),
    sage700 = s.darkAt(700), sage800 = s.darkAt(800), sage900 = s.darkAt(900)
)
