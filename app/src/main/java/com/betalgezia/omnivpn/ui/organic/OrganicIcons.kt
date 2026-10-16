package com.betalgezia.omnivpn.ui.organic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Lucide icons (lucide-static 1.58.0, ISC license) as Compose vectors, drawn
 * the way the Organic handoff specifies: stroke width 2.75 on the 24-unit
 * grid, round caps and joins. Tint them with Icon(tint = …) - the stroke is a
 * placeholder colour that the tint replaces.
 *
 * Generated from the SVG files; circles, rects and lines are converted to
 * path data so every icon is plain stroked paths.
 */
object OrganicIcons {
    /** lucide «activity» */
    val Activity: ImageVector by lazy {
        lucide(
            "activity",
            "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2"
        )
    }

    /** lucide «arrow-down» */
    val ArrowDown: ImageVector by lazy {
        lucide(
            "arrow-down",
            "M12 5v14",
            "m19 12-7 7-7-7"
        )
    }

    /** lucide «arrow-up» */
    val ArrowUp: ImageVector by lazy {
        lucide(
            "arrow-up",
            "m5 12 7-7 7 7",
            "M12 19V5"
        )
    }

    /** lucide «check» */
    val Check: ImageVector by lazy {
        lucide(
            "check",
            "M20 6 9 17l-5-5"
        )
    }

    /** lucide «chevron-down» */
    val ChevronDown: ImageVector by lazy {
        lucide(
            "chevron-down",
            "m6 9 6 6 6-6"
        )
    }

    /** lucide «chevron-left» */
    val ChevronLeft: ImageVector by lazy {
        lucide(
            "chevron-left",
            "m15 18-6-6 6-6"
        )
    }

    /** lucide «chevron-right» */
    val ChevronRight: ImageVector by lazy {
        lucide(
            "chevron-right",
            "m9 18 6-6-6-6"
        )
    }

    /** lucide «chevron-up» */
    val ChevronUp: ImageVector by lazy {
        lucide(
            "chevron-up",
            "m18 15-6-6-6 6"
        )
    }

    /** lucide «circle-check» */
    val CircleCheck: ImageVector by lazy {
        lucide(
            "circle-check",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "m16 9-5.5 5.5L8 12"
        )
    }

    /** lucide «clipboard-paste» */
    val ClipboardPaste: ImageVector by lazy {
        lucide(
            "clipboard-paste",
            "M11 14h10",
            "M16 4h2a2 2 0 0 1 2 2v1.344",
            "m17 18 4-4-4-4",
            "M8 4H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 1.793-1.113",
            "M9 2h6a1 1 0 0 1 1 1v2a1 1 0 0 1 -1 1h-6a1 1 0 0 1 -1 -1v-2a1 1 0 0 1 1 -1Z"
        )
    }

    /** lucide «cloud» */
    val Cloud: ImageVector by lazy {
        lucide(
            "cloud",
            "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z"
        )
    }

    /** lucide «crosshair» */
    val Crosshair: ImageVector by lazy {
        lucide(
            "crosshair",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "M22 12L18 12",
            "M6 12L2 12",
            "M12 6L12 2",
            "M12 22L12 18"
        )
    }

    /** lucide «file-up» */
    val FileUp: ImageVector by lazy {
        lucide(
            "file-up",
            "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z",
            "M14 2v5a1 1 0 0 0 1 1h5",
            "M12 12v6",
            "m15 15-3-3-3 3"
        )
    }

    /** lucide «gauge» */
    val Gauge: ImageVector by lazy {
        lucide(
            "gauge",
            "m12 14 4-4",
            "M3.34 19a10 10 0 1 1 17.32 0"
        )
    }

    /** lucide «globe» */
    val Globe: ImageVector by lazy {
        lucide(
            "globe",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20",
            "M2 12h20"
        )
    }

    /** lucide «history» */
    val History: ImageVector by lazy {
        lucide(
            "history",
            "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8",
            "M3 3v5h5",
            "M12 7v5l4 2"
        )
    }

    /** lucide «info» */
    val Info: ImageVector by lazy {
        lucide(
            "info",
            "M2 12a10 10 0 1 0 20 0a10 10 0 1 0 -20 0",
            "M12 16v-4",
            "M12 8h.01"
        )
    }

    /** lucide «link» */
    val Link: ImageVector by lazy {
        lucide(
            "link",
            "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71",
            "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"
        )
    }

    /** lucide «pencil» */
    val Pencil: ImageVector by lazy {
        lucide(
            "pencil",
            "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z",
            "m15 5 4 4"
        )
    }

    /** lucide «plus» */
    val Plus: ImageVector by lazy {
        lucide(
            "plus",
            "M5 12h14",
            "M12 5v14"
        )
    }

    /** lucide «power» */
    val Power: ImageVector by lazy {
        lucide(
            "power",
            "M12 2v10",
            "M18.4 6.6a9 9 0 1 1-12.77.04"
        )
    }

    /** lucide «refresh-cw» */
    val RefreshCw: ImageVector by lazy {
        lucide(
            "refresh-cw",
            "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8",
            "M21 3v5h-5",
            "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16",
            "M8 16H3v5"
        )
    }

    /** lucide «server» */
    val Server: ImageVector by lazy {
        lucide(
            "server",
            "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z",
            "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-4a2 2 0 0 1 2 -2Z",
            "M6 6L6.01 6",
            "M6 18L6.01 18"
        )
    }

    /** lucide «shield-check» */
    val ShieldCheck: ImageVector by lazy {
        lucide(
            "shield-check",
            "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z",
            "m9 12 2 2 4-4"
        )
    }

    /** lucide «sliders-horizontal» */
    val SlidersHorizontal: ImageVector by lazy {
        lucide(
            "sliders-horizontal",
            "M10 5H3",
            "M12 19H3",
            "M14 3v4",
            "M16 17v4",
            "M21 12h-9",
            "M21 19h-5",
            "M21 5h-7",
            "M8 10v4",
            "M8 12H3"
        )
    }

    /** lucide «trash-2» */
    val Trash2: ImageVector by lazy {
        lucide(
            "trash-2",
            "M10 11v6",
            "M14 11v6",
            "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6",
            "M3 6h18",
            "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"
        )
    }

    /** lucide «triangle-alert» */
    val TriangleAlert: ImageVector by lazy {
        lucide(
            "triangle-alert",
            "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3",
            "M12 9v4",
            "M12 17h.01"
        )
    }

    /** lucide «unplug» */
    val Unplug: ImageVector by lazy {
        lucide(
            "unplug",
            "m19 5 3-3",
            "m2 22 3-3",
            "M6.3 20.3a2.4 2.4 0 0 0 3.4 0L12 18l-6-6-2.3 2.3a2.4 2.4 0 0 0 0 3.4Z",
            "M7.5 13.5 10 11",
            "M10.5 16.5 13 14",
            "m12 6 6 6 2.3-2.3a2.4 2.4 0 0 0 0-3.4l-2.6-2.6a2.4 2.4 0 0 0-3.4 0Z"
        )
    }

    /** lucide «wand-sparkles» */
    val WandSparkles: ImageVector by lazy {
        lucide(
            "wand-sparkles",
            "m21.64 3.64-1.28-1.28a1.21 1.21 0 0 0-1.72 0L2.36 18.64a1.21 1.21 0 0 0 0 1.72l1.28 1.28a1.2 1.2 0 0 0 1.72 0L21.64 5.36a1.2 1.2 0 0 0 0-1.72",
            "m14 7 3 3",
            "M5 6v4",
            "M19 14v4",
            "M10 2v2",
            "M7 8H3",
            "M21 16h-4",
            "M11 3H9"
        )
    }

    /** lucide «x» */
    val X: ImageVector by lazy {
        lucide(
            "x",
            "M18 6 6 18",
            "m6 6 12 12"
        )
    }

    /** lucide «zap» */
    val Zap: ImageVector by lazy {
        lucide(
            "zap",
            "M15.914 4a1.5 1.5 0 00-2.474-1.561l-9 9A1.5 1.5 0 005.5 14h4.002a.5.5 0 01.471.666L8.086 20a1.5 1.5 0 002.475 1.56l9-9A1.5 1.5 0 0018.5 10h-3.997a.5.5 0 01-.472-.667z"
        )
    }
}

private fun lucide(name: String, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        for (d in paths) {
            addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()
