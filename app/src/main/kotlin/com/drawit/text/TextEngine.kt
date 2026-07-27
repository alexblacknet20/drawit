package com.drawit.text

import android.graphics.Paint
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Rect
import kotlin.math.abs

/**
 * Shared text measurement and layout. All coordinates are document millimetres:
 * the renderer applies the document-to-screen transform to the Canvas.
 */
class TextEngine(private val fontManager: FontManager) {

    data class Line(
        val text: String,
        val startIndex: Int,
        val width: Float,
        val baselineY: Float
    )

    data class Layout(
        val lines: List<Line>,
        val bounds: Rect,
        val lineHeight: Float,
        val paint: Paint
    )

    fun paintFor(shape: TextShape): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = fontManager.typefaceFor(
                shape.fontFamily,
                shape.fontWeight.value,
                shape.italic
            )
            textSize = shape.textSize
        }

    fun layout(shape: TextShape): Layout {
        val paint = paintFor(shape)
        val lineHeight = paint.fontSpacing * shape.lineSpacing
        val lines = mutableListOf<Line>()
        var baseline = -paint.ascent()
        var paragraphStart = 0
        val paragraphs = shape.text.split("\n")

        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (shape.kind == TextShape.Kind.PARAGRAPH && shape.frameWidth > 0f) {
                if (paragraph.isEmpty()) {
                    lines += Line("", paragraphStart, 0f, baseline)
                    baseline += lineHeight
                }

                var offset = 0
                while (offset < paragraph.length) {
                    val remaining = paragraph.substring(offset)
                    var consumed = paint.breakText(
                        remaining,
                        true,
                        shape.frameWidth,
                        null
                    ).coerceAtLeast(1)

                    // Prefer wrapping at whitespace. Long words still make
                    // progress because breakText supplies a character boundary.
                    if (consumed < remaining.length) {
                        val candidate = remaining.substring(0, consumed)
                        val boundary = candidate.indexOfLast { it.isWhitespace() }
                        if (boundary > 0) consumed = boundary + 1
                    }

                    val displayed = remaining.substring(0, consumed).trimEnd()
                    lines += Line(
                        text = displayed,
                        startIndex = paragraphStart + offset,
                        width = paint.measureText(displayed),
                        baselineY = baseline
                    )
                    offset += consumed
                    while (offset < paragraph.length && paragraph[offset].isWhitespace()) offset++
                    baseline += lineHeight
                }
            } else {
                lines += Line(
                    text = paragraph,
                    startIndex = paragraphStart,
                    width = paint.measureText(paragraph),
                    baselineY = baseline
                )
                baseline += lineHeight
            }

            paragraphStart += paragraph.length
            if (paragraphIndex < paragraphs.lastIndex) paragraphStart++
        }

        if (lines.isEmpty()) {
            lines += Line("", 0, 0f, -paint.ascent())
        }

        val width = if (shape.kind == TextShape.Kind.PARAGRAPH && shape.frameWidth > 0f) {
            shape.frameWidth
        } else {
            lines.maxOf { it.width }
        }
        val height = lines.last().baselineY + paint.descent()
        return Layout(lines, Rect(0f, 0f, width, height), lineHeight, paint)
    }

    fun lineXOffset(shape: TextShape, line: Line, layout: Layout): Float = when (shape.align) {
        TextShape.Align.LEFT -> 0f
        TextShape.Align.CENTER -> (layout.bounds.width - line.width) / 2f
        TextShape.Align.RIGHT -> layout.bounds.width - line.width
    }

    /** Returns caret x, top and bottom in local document coordinates. */
    fun caretFor(shape: TextShape, layout: Layout, index: Int): Triple<Float, Float, Float> {
        val clamped = index.coerceIn(0, shape.text.length)
        val line = layout.lines.lastOrNull { it.startIndex <= clamped } ?: layout.lines.first()
        val inLine = (clamped - line.startIndex).coerceIn(0, line.text.length)
        val x = lineXOffset(shape, line, layout) +
            layout.paint.measureText(line.text.substring(0, inLine))
        val top = line.baselineY + layout.paint.ascent()
        val bottom = line.baselineY + layout.paint.descent()
        return Triple(x, top, bottom)
    }

    fun indexForPoint(shape: TextShape, layout: Layout, x: Float, y: Float): Int {
        val line = layout.lines.minByOrNull {
            val top = it.baselineY + layout.paint.ascent()
            val bottom = it.baselineY + layout.paint.descent()
            abs((top + bottom) / 2f - y)
        } ?: return 0

        val localX = x - lineXOffset(shape, line, layout)
        var bestIndex = 0
        var bestDistance = Float.MAX_VALUE
        for (index in 0..line.text.length) {
            val caretX = layout.paint.measureText(line.text.substring(0, index))
            val distance = abs(caretX - localX)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        return (line.startIndex + bestIndex).coerceIn(0, shape.text.length)
    }

    fun measure(shape: TextShape): TextShape =
        shape.copy(measuredBounds = layout(shape).bounds)
}
