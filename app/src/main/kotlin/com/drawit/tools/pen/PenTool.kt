package com.drawit.tools.pen

import com.drawit.canvas.EditorState
import com.drawit.core.color.Color
import com.drawit.core.document.Fill
import com.drawit.core.document.Shape
import com.drawit.core.document.Stroke
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point
import com.drawit.core.input.Tool
import com.drawit.core.input.ToolContext
import com.drawit.core.input.ToolEvent

/**
 * Freehand pen tool — draws smooth paths with optional stylus pressure.
 * Uses quadratic smoothing through midpoints for clean curves.
 */
class PenTool(
    private val state: EditorState,
    private val strokeColor: Color = Color.BLACK,
    private val baseStrokeWidth: Float = 0.5f, // mm
    private val usePressure: Boolean = true
) : Tool {

    override val id = "pen"
    override val name = "Pen"

    private var context: ToolContext? = null
    private var currentPoints = mutableListOf<Pair<Point, Float>>() // point + pressure
    private var isDrawing = false

    override fun activate(context: ToolContext) {
        this.context = context
    }

    override fun deactivate() {
        cancelStroke()
    }

    override fun onEvent(event: ToolEvent): Boolean {
        return when (event) {
            is ToolEvent.Down -> {
                isDrawing = true
                currentPoints.clear()
                currentPoints.add(event.position to event.pressure)
                true
            }
            is ToolEvent.Move -> {
                if (!isDrawing) return false
                // Skip points too close together (reduces node count)
                val last = currentPoints.lastOrNull()
                val minDist = (context?.hitTolerance ?: 1f) * 0.25f
                if (last == null || event.position.distanceTo(last.first) > minDist) {
                    currentPoints.add(event.position to event.pressure)
                    context?.invalidate()
                }
                true
            }
            is ToolEvent.Up -> {
                if (!isDrawing) return false
                finishStroke()
                true
            }
            is ToolEvent.Cancel -> {
                cancelStroke()
                true
            }
            else -> false
        }
    }

    private fun finishStroke() {
        if (currentPoints.size >= 2) {
            val pathData = smoothPath(currentPoints)
            val avgPressure = currentPoints.map { it.second }.average().toFloat()
            val width = if (usePressure) baseStrokeWidth * (0.3f + 0.7f * avgPressure) * 2f
                        else baseStrokeWidth

            val shape = Shape.PathShape(
                name = "Stroke",
                pathData = pathData,
                fill = Fill.None,
                stroke = Stroke(color = strokeColor, width = width, cap = Stroke.Cap.ROUND, join = Stroke.Join.ROUND)
            )
            state.addShape(shape)
        }
        currentPoints.clear()
        isDrawing = false
    }

    private fun cancelStroke() {
        currentPoints.clear()
        isDrawing = false
        context?.invalidate()
    }

    /**
     * Convert raw points to a smoothed path using midpoint quadratic technique.
     * Produces clean curves with ~half the nodes of naive lineTo.
     */
    private fun smoothPath(points: List<Pair<Point, Float>>): PathData {
        if (points.size < 2) return PathData.EMPTY
        var path = PathData.EMPTY.moveTo(points[0].first)

        if (points.size == 2) {
            return path.lineTo(points[1].first)
        }

        for (i in 1 until points.size - 1) {
            val current = points[i].first
            val next = points[i + 1].first
            val mid = current.midpoint(next)
            path = path.quadTo(current, mid)
        }
        path = path.lineTo(points.last().first)
        return path
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        if (currentPoints.size < 2) return
        val c = canvas as? android.graphics.Canvas ?: return

        val androidPath = android.graphics.Path()
        val first = context.documentToScreen(currentPoints[0].first)
        androidPath.moveTo(first.x, first.y)
        for (i in 1 until currentPoints.size) {
            val p = context.documentToScreen(currentPoints[i].first)
            androidPath.lineTo(p.x, p.y)
        }

        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor.toArgb()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = (baseStrokeWidth * context.zoom * 3.7795275591f).coerceAtLeast(1f)
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        c.drawPath(androidPath, paint)
    }
}
