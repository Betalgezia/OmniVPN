package com.betalgezia.omnivpn.ui.theme

import androidx.compose.ui.graphics.Color

// Brand accent - the Nocturne "blurple" used for the hero connect button,
// primary actions and links (retuned 2026-09 to the Nocturne v2 design
// system's --color-accent-400). Light theme keeps the older electric blue.
val AccentBlueLight = Color(0xFF3D5EF2)
val AccentBlueDark = Color(0xFFB5ABFC)

// Cloudflare-adjacent orange, reserved for the WARP action so it reads as
// its own distinct path rather than a second "primary" button competing
// with the hero connect button.
val WarpOrangeLight = Color(0xFFE8720C)
val WarpOrangeDark = Color(0xFFFF9B4A)

// Semantic status colors, used outside MaterialTheme.colorScheme (which has
// no dedicated "success"/"warning" slot) for connection/health states.
val StatusConnectedLight = Color(0xFF1E9E5A)
val StatusConnectedDark = Color(0xFF3DDC84)
val StatusWarningLight = Color(0xFFB4720A)
val StatusWarningDark = Color(0xFFFFC658)
val StatusErrorLight = Color(0xFFD03A2E)
val StatusErrorDark = Color(0xFFFF6B5E)

// One distinct hue per protocol, for the server list's protocol badge - the
// point is to be able to tell VLESS/TROJAN/HYSTERIA2/AMNEZIAWG apart at a
// glance in a scrolling list, not to carry any other meaning.
val ProtocolVlessLight = Color(0xFF3355CC)
val ProtocolVlessDark = Color(0xFF7C9EFF)
val ProtocolTrojanLight = Color(0xFFA6318F)
val ProtocolTrojanDark = Color(0xFFE88AE0)
val ProtocolHysteria2Light = Color(0xFF9A6400)
val ProtocolHysteria2Dark = Color(0xFFFFC658)
val ProtocolAmneziaWgLight = Color(0xFF157A5C)
val ProtocolAmneziaWgDark = Color(0xFF6EE7B7)

// Dark theme surfaces - retuned 2026-09 to the Nocturne v2 palette
// (--color-bg / --color-surface / --color-neutral-900 / …).
val BackgroundDark = Color(0xFF161826)
val SurfaceDark = Color(0xFF232532)
val SurfaceVariantDark = Color(0xFF292B31)
val OnSurfaceDark = Color(0xFFE9E9ED)
val OnSurfaceVariantDark = Color(0xFFB2B6CA)
val OutlineDark = Color(0xFF3F424D)

// Light theme surfaces - kept close to Material defaults for familiarity,
// tinted just enough to match the dark theme's hue family. The Nocturne v2
// redesign is dark-only, so these are left as-is.
val BackgroundLight = Color(0xFFF7F9FC)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFE7ECF5)
val OnSurfaceLight = Color(0xFF12151C)
val OnSurfaceVariantLight = Color(0xFF4B5568)
val OutlineLight = Color(0xFFD3DAE6)

/**
 * The Nocturne v2 design-system palette (from the September 2026 handoff
 * `prototype/_ds/…/styles.css`). These are the exact tokens the redesign's
 * gradients, chips, auroras and per-state colours reference - kept as one
 * object rather than scattered `Color(0xFF…)` literals so the whole UI reads
 * from a single source and any retune happens here.
 *
 * Dark-only by design intent (the whole redesign is dark), so these are flat
 * values, not light/dark pairs like the semantic colours above.
 */
object Nocturne {
    // Grounds
    val bg = Color(0xFF161826)
    val surface = Color(0xFF232532)
    val text = Color(0xFFE9E9ED)

    // Neutral ramp (100 lightest … 900 darkest)
    val neutral100 = Color(0xFFF3F5FE)
    val neutral200 = Color(0xFFE4E7F5)
    val neutral300 = Color(0xFFCFD3E5)
    val neutral400 = Color(0xFFB2B6CA)
    val neutral500 = Color(0xFF9397AB)
    val neutral600 = Color(0xFF75798C)
    val neutral700 = Color(0xFF595D6C)
    val neutral800 = Color(0xFF3F424D)
    val neutral900 = Color(0xFF292B31)

    // Accent ramp (Nocturne blurple)
    val accent300 = Color(0xFFD2CEFD)
    val accent400 = Color(0xFFB5ABFC)
    val accent500 = Color(0xFF968AE0)
    val accent700 = Color(0xFF5D5294)
    val accent800 = Color(0xFF423A6A)
    val accent900 = Color(0xFF2B2741)

    // Indigo used by the app-icon ground
    val indigoMid = Color(0xFF353B80)
    val indigoDeep = Color(0xFF262A60)

    // Protocol / status hues (match Color.kt's *Dark values)
    val vless = Color(0xFF7C9EFF)
    val trojan = Color(0xFFE88AE0)
    val hysteria2 = Color(0xFFFFC658)
    val amneziaWg = Color(0xFF6EE7B7)
    val warp = Color(0xFFFF9B4A)
    val warpAmber = Color(0xFFFFC658)
    val warpPink = Color(0xFFE88AE0)
    val connected = Color(0xFF3DDC84)
    val connectedSoft = Color(0xFF6EE7B7)
    val warning = Color(0xFFFFC658)
    val error = Color(0xFFFF6B5E)

    // Sheet / snackbar grounds
    val sheetTop = Color(0xFF262838)
    val sheetBottom = Color(0xFF1C1E2B)
    val snackbar = Color(0xFF2D3040)
    val warpInk = Color(0xFF2B1A0C)
}
