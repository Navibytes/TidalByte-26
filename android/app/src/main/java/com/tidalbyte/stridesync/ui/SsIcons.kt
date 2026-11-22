package com.tidalbyte.stridesync.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Outline icons on a 24-unit grid, drawn here to match the mockups (no icon library dependency). The footprints
 *  and music shapes follow Lucide (ISC license). Tint them with Icon(tint = …). */
object SsIcons {
    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    private fun icon(name: String, strokes: List<String>, fills: List<String> = emptyList(), width: Float = 2f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            strokes.forEach {
                addPath(
                    addPathNodes(it),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
            fills.forEach { addPath(addPathNodes(it), fill = SolidColor(Color.Black)) }
        }.build()

    val Walk = icon(
        "walk",
        listOf(
            "M4 16v-2.38C4 11.5 2.97 10.5 3 8c.03-2.72 1.49-6 4.5-6C9.37 2 10 3.8 10 5.5c0 3.11-2 5.66-2 8.68V16a2 2 0 1 1-4 0Z",
            "M20 20v-2.38c0-2.12 1.03-3.12 1-5.62-.03-2.72-1.49-6-4.5-6C14.63 6 14 7.8 14 9.5c0 3.11 2 5.66 2 8.68V20a2 2 0 1 0 4 0Z",
            "M16 17h4",
            "M4 13h4",
        ),
    )
    val Music = icon("music", listOf("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)))
    val History = icon("history", listOf("M4 21h16", "M7 17v-5", "M11 17V7", "M15 17v-8", "M19 17v-3"))
    val Profile = icon("profile", listOf(circle(12f, 8f, 4f), "M4.5 21c.6-4 3.6-6.5 7.5-6.5s6.9 2.5 7.5 6.5"))
    val Settings = icon("settings", listOf("M20 7h-9", "M14 17H5", circle(17f, 17f, 3f), circle(7f, 7f, 3f)))
    val Play = icon(
        "play",
        emptyList(),
        listOf("M7 5.2v13.6c0 .8.9 1.3 1.6.8l10.4-6.8c.6-.4.6-1.2 0-1.6L8.6 4.4C7.9 3.9 7 4.4 7 5.2Z"),
    )
    val Stop = icon("stop", emptyList(), listOf("M8 6h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2Z"))
    val Share = icon("share", listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M17 8l-5-5-5 5", "M12 3v12"))
    val ArrowRight = icon("arrow-right", listOf("M5 12h14", "M13 6l6 6-6 6"))
    val ChevronRight = icon("chevron-right", listOf("M9 18l6-6-6-6"))
    val ChevronUp = icon("chevron-up", listOf("M6 15l6-6 6 6"), width = 2.5f)
    val TriangleUp = icon("triangle-up", emptyList(), listOf("M12 5.5l7 11.5H5Z"))
    val More = icon("more", emptyList(), listOf(circle(5f, 12f, 1.8f), circle(12f, 12f, 1.8f), circle(19f, 12f, 1.8f)))
    val Click = icon("click", listOf(circle(12f, 16f, 4.2f), "M12 11.8V9", "M7.5 4.5l1.8 3", "M16.5 4.5l-1.8 3"))
    val Woodblock = icon(
        "woodblock",
        listOf("M5 10h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2Z", "M7 14h10", "M15 10l3-5.5"),
    )
    val Plus = icon("plus", listOf("M12 5v14", "M5 12h14"))
}
