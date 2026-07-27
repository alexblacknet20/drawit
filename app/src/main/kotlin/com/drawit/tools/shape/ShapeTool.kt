package com.drawit.tools.shape

import com.drawit.canvas.EditorState
import com.drawit.core.color.Color
import com.drawit.core.document.Fill
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent
import kotlin.math.abs

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
    private val strokeWidth: Float = 0.5f,
    private val polygonSides: Int = 5
) : Tool {

    override val isConstrainableGestureActive: Boolean
        get() = dragStart != null

    enum class Mode { RECTANGLE, ELLIPSE, POLYGON }

    override val id = when (mode) {
        Mode.RECTANGLE -> "rect"
        Mode.ELLIPSE -> "ellipse"
        Mode.POLYGON -> "polygon"
    }
    override val name = when (mode) {
        Mode.RECTANGLE -> "Rectangle"
        Mode.ELLIPSE -> "Ellipse"
        Mode.POLYGON -> "Polygon"
    }

    private var context: ToolContext? = null
    private var dragStart: com.drawit.core.geometry.Point? = null
    private var currentRect: Rect? = null
    private var constrainSquare = false
    private var snapGuideX: Float? = null
    private var snapGuideY: Float? = null

    override fun activate(context: ToolContext) {
        this.context = context
    }

    override fun deactivate() {
        dragStart = null
        currentRect = null
        snapGuideX = null
        snapGuideY = null
    }

    override fun onEvent(event: ToolEvent): Boolean {
        return when (event) {
            is ToolEvent.Down -> {
                val position = smartSnap(event.position)
                dragStart = position
                constrainSquare = event.modifiers.shift
                currentRect = Rect(position.x, position.y, position.x, position.y)
                true
            }
            is ToolEvent.Move -> {
                val start = dragStart ?: return false
                constrainSquare = event.modifiers.shift
                currentRect = computeRect(start, smartSnap(event.position))
                context?.invalidate()
                true
            }
            is ToolEvent.Up -> {
                val start = dragStart
                constrainSquare = event.modifiers.shift
                val rect = start?.let { computeRect(it, smartSnap(event.position)) } ?: currentRect
                dragStart = null
                currentRect = null
                snapGuideX = null
                snapGuideY = null
                if (rect != null && !rect.isEmpty) {
                    commitShape(rect)
                }
                context?.invalidate()
                true
            }
            is ToolEvent.Cancel -> {
                dragStart = null
                currentRect = null
                snapGuideX = null
                snapGuideY = null
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
            Mode.POLYGON -> Shape.PolygonShape(
                rect = rect,
                sides = polygonSides.coerceIn(3, 64),
                fill = Fill.Solid(fillColor),
                stroke = Stroke(color = strokeColor, width = strokeWidth)
            )
        }
        state.addShape(shape)
    }

    private fun smartSnap(point: Point): Point {
        if (!state.smartAlignmentsEnabled) {
            snapGuideX = null
            snapGuideY = null
            return point
        }
        val page = state.document.activePage
        val targetX = mutableListOf(0f, page.width / 2f, page.width)
        val targetY = mutableListOf(0f, page.height / 2f, page.height)
        page.layers
            .asSequence()
            .filter { it.visible }
            .flatMap { it.shapes.asSequence() }
            .filter { it.visible }
            .forEach { shape ->
                val bounds = shape.bounds()
                targetX += listOf(bounds.left, bounds.centerX, bounds.right)
                targetY += listOf(bounds.top, bounds.centerY, bounds.bottom)
            }
        val tolerance = context?.hitTolerance ?: 1f
        val x = targetX.minByOrNull { abs(it - point.x) }
            ?.takeIf { abs(it - point.x) <= tolerance }
        val y = targetY.minByOrNull { abs(it - point.y) }
            ?.takeIf { abs(it - point.y) <= tolerance }
        snapGuideX = x
        snapGuideY = y
        return Point(x ?: point.x, y ?: point.y)
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val rect = currentRect ?: return
        val c = canvas as? android.graphics.Canvas ?: return
        val guidePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(222, 45, 125)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f
        }
        snapGuideX?.let { x ->
            val top = context.documentToScreen(Point(x, 0f))
            val bottom = context.documentToScreen(Point(x, state.document.activePage.height))
            c.drawLine(top.x, top.y, bottom.x, bottom.y, guidePaint)
        }
        snapGuideY?.let { y ->
            val left = context.documentToScreen(Point(0f, y))
            val right = context.documentToScreen(Point(state.document.activePage.width, y))
            c.drawLine(left.x, left.y, right.x, right.y, guidePaint)
        }
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
            Mode.POLYGON -> {
                val count = polygonSides.coerceIn(3, 64)
                val path = android.graphics.Path()
                repeat(count) { index ->
                    val angle = -Math.PI / 2.0 + 2.0 * Math.PI * index / count
                    val point = context.documentToScreen(
                        Point(
                            rect.centerX + kotlin.math.cos(angle).toFloat() * rect.width / 2f,
                            rect.centerY + kotlin.math.sin(angle).toFloat() * rect.height / 2f
                        )
                    )
                    if (index == 0) path.moveTo(point.x, point.y)
                    else path.lineTo(point.x, point.y)
                }
                path.close()
                c.drawPath(path, fillPaint)
                c.drawPath(path, strokePaint)
            }
        }
    }
}
