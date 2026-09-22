package com.betalgezia.omnivpn.ui.theme

import androidx.compose.ui.graphics.Color

// Brand accent - an electric indigo/blue used for the hero connect button,
// primary actions and links. Chosen to read as "network/tech" without
// tipping into any one platform's stock Material purple.
val AccentBlueLight = Color(0xFF3D5EF2)
val AccentBlueDark = Color(0xFF6C8CFF)

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
val StatusWarningDark = Color(0xFFFFB84D)
val StatusErrorLight = Color(0xFFD03A2E)
val StatusErrorDark = Color(0xFFFF6B5E)

// Dark theme surfaces - a deep, slightly blue-tinted near-black rather than
// pure black, so elevated cards/sheets still read as "lifted" against it.
val BackgroundDark = Color(0xFF0A0E17)
val SurfaceDark = Color(0xFF121826)
val SurfaceVariantDark = Color(0xFF1E2739)
val OnSurfaceDark = Color(0xFFF2F5FA)
val OnSurfaceVariantDark = Color(0xFF9AA5B8)
val OutlineDark = Color(0xFF2C374B)

// Light theme surfaces - kept close to Material defaults for familiarity,
// tinted just enough to match the dark theme's hue family.
val BackgroundLight = Color(0xFFF7F9FC)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFE7ECF5)
val OnSurfaceLight = Color(0xFF12151C)
val OnSurfaceVariantLight = Color(0xFF4B5568)
val OutlineLight = Color(0xFFD3DAE6)
