package com.drawit.tools.shape

import com.drawit.canvas.EditorState
import com.drawit.core.color.Color
import com.drawit.core.document.Fill
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.geometry.Rect
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent

/**
 * Rectangle / ellipse drag tool.
 * - Drag: draw shape
 * - Shift: constrain to square/circle
 */
class ShapeTool(
    private val state: EditorState,
    private val mode: Mode,
    private val fillColor: Color = Color(0, 120, 215, 60),
    private val strokeColor: Color = Color(0, 120, 215),
    private val strokeWidth: Float = 0.5f
) : Tool {

    enum class Mode { RECTANGLE, ELLIPSE }

    override val id = when (mode) {
        Mode.RECTANGLE -> "rect"
        Mode.ELLIPSE -> "ellipse"
    }
    override val name = when (mode) {
        Mode.RECTANGLE -> "Rectangle"
        Mode.ELLIPSE -> "Ellipse"
    }

    private var context: ToolContext? = null
    private var dragStart: com.drawit.core.geometry.Point? = null
    private var currentRect: Rect? = null
    private var constrainSquare = false

    override fun activate(context: ToolContext) {
        this.context = context
    }

    override fun deactivate() {
        dragStart = null
        currentRect = null
    }

    override fun onEvent(event: ToolEvent): Boolean {
        return when (event) {
            is ToolEvent.Down -> {
                dragStart = event.position
                constrainSquare = event.modifiers.shift
                currentRect = Rect(event.position.x, event.position.y, event.position.x, event.position.y)
                true
            }
            is ToolEvent.Move -> {
                val start = dragStart ?: return false
                constrainSquare = event.modifiers.shift
                currentRect = computeRect(start, event.position)
                context?.invalidate()
                true
            }
            is ToolEvent.Up -> {
                val rect = currentRect
                dragStart = null
                currentRect = null
                if (rect != null && !rect.isEmpty) {
                    commitShape(rect)
                }
                context?.invalidate()
                true
            }
            is ToolEvent.Cancel -> {
                dragStart = null
                currentRect = null
                context?.invalidate()
                true
            }
            else -> false
        }
    }

    private fun computeRect(
        start: com.drawit.core.geometry.Point,
        current: com.drawit.core.geometry.Point
    ): Rect {
        if (!constrainSquare) return Rect.fromPoints(start, current)
        // Constrain to square: use the larger dimension
        val dx = current.x - start.x
        val dy = current.y - start.y
        val size = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
        return Rect.fromPoints(
            start,
            com.drawit.core.geometry.Point(
                start.x + size * if (dx >= 0) 1f else -1f,
                start.y + size * if (dy >= 0) 1f else -1f
            )
        )
    }

    private fun commitShape(rect: Rect) {
        val shape = when (mode) {
            Mode.RECTANGLE -> Shape.RectShape(
                rect = rect,
                fill = Fill.Solid(fillColor),
                stroke = Stroke(color = strokeColor, width = strokeWidth)
            )
            Mode.ELLIPSE -> Shape.EllipseShape(
                rect = rect,
                fill = Fill.Solid(fillColor),
                stroke = Stroke(color = strokeColor, width = strokeWidth)
            )
        }
        state.addShape(shape)
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val rect = currentRect ?: return
        val c = canvas as? android.graphics.Canvas ?: return
        val tl = context.documentToScreen(rect.topLeft)
        val br = context.documentToScreen(rect.bottomRight)

        val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor.toArgb()
            style = android.graphics.Paint.Style.FILL
        }
        val strokePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (strokeWidth * context.zoom * 3.7795275591f).coerceAtLeast(1f)
        }

        when (mode) {
            Mode.RECTANGLE -> {
                c.drawRect(tl.x, tl.y, br.x, br.y, fillPaint)
                c.drawRect(tl.x, tl.y, br.x, br.y, strokePaint)
            }
            Mode.ELLIPSE -> {
                c.drawOval(tl.x, tl.y, br.x, br.y, fillPaint)
                c.drawOval(tl.x, tl.y, br.x, br.y, strokePaint)
            }
        }
    }
}
