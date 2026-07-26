package com.drawit.text

import android.graphics.Paint
import android.graphics.Typeface
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Rect

/**
 * Measures and lays out text for TextShape — shared by renderer (drawing)
 * and TextTool (caret/selection math). All units in document mm.
 */
class TextEngine(private val fontManager: FontManager) {

    companion object {
        const val MM_PER_PX = 25.4f / 96f
    }

    data class Line(
        val text: String,          // substring of the shape text for this line
        val startIndex: Int,       // index into full text where this line starts
        val width: Float,          // mm
        val baselineY: Float       // mm, from text block top
    )

    data class Layout(
        val lines: List<Line>,
        val bounds: Rect,          // mm, local coords (top-left at 0,0)
        val lineHeight: Float,     // mm
        val paint: Paint
    )

    fun paintFor(shape: TextShape): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        paint.typeface = fontManager.typefaceFor(shape.fontFamily)
        // textSize is in mm; Paint works in px — use a fixed reference and scale
        paint.textSize = shape.textSize / MM_PER_PX
        return paint
    }

    /** Lay out the shape's text: wraps if paragraph, splits on \n always. */
    fun layout(shape: TextShape): Layout {
        val paint = paintFor(shape)
        val lineHeightPx = paint.fontSpacing
        val lineHeightMm = lineHeightPx * MM_PER_PX * shape.lineSpacing / 1.0f

        val lines = mutableListOf<Line>()
        var baselineY = -paint.ascent() * MM_PER_PX // first baseline from top

        val paragraphs = shape.text.split("\n")
        var index = 0
        for (para in paragraphs) {
            if (shape.kind == TextShape.Kind.PARAGRAPH && shape.frameWidth > 0f) {
                // Greedy word wrap
                var remaining = para
                if (remaining.isEmpty()) {
                    lines.add(Line("", index, 0f, baselineY))
                    baselineY += lineHeightMm
                }
                while (remaining.isNotEmpty()) {
                    val maxPx = shape.frameWidth / MM_PER_PX
                    val count = paint.breakText(remaining, true, maxPx, null)
                    if (count <= 0) {
                        // Single word longer than frame: force-break by chars
                        var i = 1
                        while (i < remaining.length &&
                            paint.measureText(remaining.substring(0, i + 1)) <= maxPx) i++
                        val part = remaining.substring(0, i)
                        lines.add(Line(part, index, paint.measureText(part) * MM_PER_PX, baselineY))
                        index += part.length
                        remaining = remaining.substring(i)
                    } else {
                        // Displayed part trims trailing spaces; consumed skips them
                        var end = count
                        while (end > 0 && remaining[end - 1] == ' ') end--
                        if (end == 0) end = count
                        val part = remaining.substring(0, end)
                        lines.add(Line(part, index, paint.measureText(part) * MM_PER_PX, baselineY))
                        var consumed = count
                        while (consumed < remaining.length && remaining[consumed] == ' ') consumed++
                        index += consumed
                        remaining = remaining.substring(consumed)
                    }
                    baselineY += lineHeightMm
                }
            } else {
                val w = paint.measureText(para) * MM_PER_PX
                lines.add(Line(para, index, w, baselineY))
                index += para.length + 1 // +1 for \n
                baselineY += lineHeightMm
            }
        }

        if (lines.isEmpty()) {
            lines.add(Line("", 0, 0f, -paint.ascent() * MM_PER_PX))
        }

        // Alignment offsets per line (paragraph mode only; artistic left-aligns at anchor)
        val maxWidth = if (shape.kind == TextShape.Kind.PARAGRAPH) shape.frameWidth
        else lines.maxOf { it.width }

        val totalHeight = (lines.last().baselineY + paint.descent() * MM_PER_PX)
        val bounds = Rect(0f, 0f, maxWidth, totalHeight)

        return Layout(lines, bounds, lineHeightMm, paint)
    }

    /** X offset for a line given alignment (mm). */
    fun lineXOffset(shape: TextShape, line: Line, layout: Layout): Float = when (shape.align) {
        TextShape.Align.LEFT -> 0f
        TextShape.Align.CENTER -> (layout.bounds.width - line.width) / 2f
        TextShape.Align.RIGHT -> layout.bounds.width - line.width
    }

    /** Caret (x, top, bottom) in local mm for a text index. */
    fun caretFor(shape: TextShape, layout: Layout, index: Int): Triple<Float, Float, Float> {
        val clamped = index.coerceIn(0, shape.text.length)
        val line = layout.lines.lastOrNull { it.startIndex <= clamped } ?: layout.lines.first()
        val inLine = (clamped - line.startIndex).coerceIn(0, line.text.length)
        val xPx = layout.paint.measureText(line.text.substring(0, inLine))
        val x = lineXOffset(shape, line, layout) + xPx * MM_PER_PX
        val top = line.baselineY + layout.paint.ascent() * MM_PER_PX
        val bottom = line.baselineY + layout.paint.descent() * MM_PER_PX
        return Triple(x, top, bottom)
    }

    /** Nearest text index for a local point (mm). */
    fun indexForPoint(shape: TextShape, layout: Layout, x: Float, y: Float): Int {
        val line = layout.lines.minByOrNull { kotlin.math.abs(it.baselineY - y) }
            ?: return 0
        val localX = x - lineXOffset(shape, line, layout)
        val xPx = localX / MM_PER_PX
        // Walk to nearest char boundary
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (i in 0..line.text.length) {
            val cx = layout.paint.measureText(line.text.substring(0, i))
            val d = kotlin.math.abs(cx - xPx)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return (line.startIndex + best).coerceIn(0, shape.text.length)
    }

    /** Measure and update a TextShape's cached bounds. */
    fun measure(shape: TextShape): TextShape {
        val layout = layout(shape)
        return shape.copy(measuredBounds = layout.bounds)
    }
}
