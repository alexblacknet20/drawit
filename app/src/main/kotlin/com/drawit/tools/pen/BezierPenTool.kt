package com.drawit.tools.pen

import android.view.KeyEvent
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
 * Bézier pen: click for a corner, click-drag for symmetric smooth handles.
 * Enter or a double-click finishes an open path; clicking the first anchor closes it.
 */
class BezierPenTool(
    private val state: EditorState,
    private val strokeColor: Color = Color.BLACK,
    private val strokeWidth: Float = 0.3f
) : Tool {

    override val id = "bezier-pen"
    override val name = "Bézier Pen"

    private data class Anchor(
        val point: Point,
        val inHandle: Point? = null,
        val outHandle: Point? = null
    )

    private var context: ToolContext? = null
    private val points = mutableListOf<Anchor>()
    private var downPosition: Point? = null
    private var cursorPosition = Point.ZERO
    private var lastPlacedTimestamp = Long.MIN_VALUE

    override fun activate(context: ToolContext) {
        this.context = context
    }

    override fun deactivate() {
        cancelPath()
        context = null
    }

    override fun onEvent(event: ToolEvent): Boolean = when (event) {
        is ToolEvent.Down -> onDown(event)
        is ToolEvent.Move -> onMove(event)
        is ToolEvent.Up -> onUp(event)
        is ToolEvent.Hover -> {
            if (downPosition == null) {
                cursorPosition = event.position
                context?.invalidate()
            }
            true
        }
        is ToolEvent.Key -> onKey(event)
        is ToolEvent.Cancel -> {
            cancelPath()
            true
        }
        else -> false
    }

    private fun onDown(event: ToolEvent.Down): Boolean {
        val tolerance = context?.hitTolerance ?: 2f
        if (points.size >= 2 && event.position.distanceTo(points.first().point) <= tolerance) {
            finishPath(closed = true)
            return true
        }

        val isDoubleClick = points.size >= 2 &&
            event.timestamp - lastPlacedTimestamp in 0..350L &&
            event.position.distanceTo(points.last().point) <= tolerance
        if (isDoubleClick) {
            finishPath(closed = false)
            return true
        }

        downPosition = event.position
        cursorPosition = event.position
        context?.invalidate()
        return true
    }

    private fun onMove(event: ToolEvent.Move): Boolean {
        if (downPosition == null) return false
        cursorPosition = event.position
        context?.invalidate()
        return true
    }

    private fun onUp(event: ToolEvent.Up): Boolean {
        val anchorPoint = downPosition ?: return false
        val handle = event.position - anchorPoint
        val dragThreshold = (context?.hitTolerance ?: 2f) * 0.35f
        points += if (handle.length <= dragThreshold) {
            Anchor(anchorPoint)
        } else {
            Anchor(anchorPoint, inHandle = handle * -1f, outHandle = handle)
        }

        downPosition = null
        cursorPosition = event.position
        lastPlacedTimestamp = event.timestamp
        context?.invalidate()
        return true
    }

    private fun onKey(event: ToolEvent.Key): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL -> {
                if (points.isNotEmpty()) {
                    points.removeLast()
                    context?.invalidate()
                }
                true
            }
            KeyEvent.KEYCODE_ESCAPE -> {
                cancelPath()
                true
            }
            KeyEvent.KEYCODE_ENTER -> {
                if (points.size >= 2) finishPath(closed = false) else cancelPath()
                true
            }
            else -> false
        }
    }

    private fun finishPath(closed: Boolean) {
        if (points.size < 2) {
            cancelPath()
            return
        }

        var path = PathData.EMPTY.moveTo(points.first().point)
        for (index in 1 until points.size) {
            path = appendSegment(path, points[index - 1], points[index])
        }

        if (closed) {
            val last = points.last()
            val first = points.first()
            if (last.outHandle != null || first.inHandle != null) {
                path = path.cubicTo(
                    last.point + (last.outHandle ?: Point.ZERO),
                    first.point + (first.inHandle ?: Point.ZERO),
                    first.point
                )
            }
            path = path.close()
        }

        state.addShape(
            Shape.PathShape(
                name = "Path",
                pathData = path,
                fill = Fill.None,
                stroke = Stroke(
                    color = strokeColor,
                    width = strokeWidth,
                    cap = Stroke.Cap.ROUND,
                    join = Stroke.Join.ROUND
                )
            )
        )
        cancelPath()
    }

    private fun appendSegment(path: PathData, from: Anchor, to: Anchor): PathData {
        return if (from.outHandle != null || to.inHandle != null) {
            path.cubicTo(
                from.point + (from.outHandle ?: Point.ZERO),
                to.point + (to.inHandle ?: Point.ZERO),
                to.point
            )
        } else {
            path.lineTo(to.point)
        }
    }

    private fun cancelPath() {
        points.clear()
        downPosition = null
        lastPlacedTimestamp = Long.MIN_VALUE
        context?.invalidate()
    }

    override fun drawOverlay(canvas: Any, context: ToolContext) {
        val target = canvas as? android.graphics.Canvas ?: return
        val toScreen = { point: Point -> context.documentToScreen(point) }

        val pathPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor.toArgb()
            strokeWidth = 1.5f
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        val anchorPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(0, 120, 215)
            style = android.graphics.Paint.Style.FILL
        }
        val handlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(180, 0, 120, 215)
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1f
        }

        if (points.isNotEmpty()) {
            val androidPath = android.graphics.Path()
            val first = toScreen(points.first().point)
            androidPath.moveTo(first.x, first.y)
            for (index in 1 until points.size) {
                appendAndroidSegment(androidPath, points[index - 1], points[index], toScreen)
            }

            downPosition?.let { currentPoint ->
                val handle = cursorPosition - currentPoint
                appendAndroidSegment(
                    androidPath,
                    points.last(),
                    Anchor(currentPoint, inHandle = handle * -1f, outHandle = handle),
                    toScreen
                )
            }
            target.drawPath(androidPath, pathPaint)
        }

        if (points.isNotEmpty() && downPosition == null) {
            val last = toScreen(points.last().point)
            val cursor = toScreen(cursorPosition)
            target.drawLine(last.x, last.y, cursor.x, cursor.y,
                android.graphics.Paint(pathPaint).apply {
                    color = android.graphics.Color.argb(120, 0, 120, 215)
                    strokeWidth = 1f
                    pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 4f), 0f)
                })
        }

        points.forEach { anchor ->
            drawAnchor(target, anchor, toScreen, anchorPaint, handlePaint)
        }

        downPosition?.let { point ->
            val handle = cursorPosition - point
            drawAnchor(
                target,
                Anchor(point, inHandle = handle * -1f, outHandle = handle),
                toScreen,
                anchorPaint,
                handlePaint
            )
        }
    }

    private fun appendAndroidSegment(
        path: android.graphics.Path,
        from: Anchor,
        to: Anchor,
        toScreen: (Point) -> Point
    ) {
        val end = toScreen(to.point)
        if (from.outHandle != null || to.inHandle != null) {
            val cp1 = toScreen(from.point + (from.outHandle ?: Point.ZERO))
            val cp2 = toScreen(to.point + (to.inHandle ?: Point.ZERO))
            path.cubicTo(cp1.x, cp1.y, cp2.x, cp2.y, end.x, end.y)
        } else {
            path.lineTo(end.x, end.y)
        }
    }

    private fun drawAnchor(
        canvas: android.graphics.Canvas,
        anchor: Anchor,
        toScreen: (Point) -> Point,
        anchorPaint: android.graphics.Paint,
        handlePaint: android.graphics.Paint
    ) {
        val center = toScreen(anchor.point)
        val handleSize = state.controlHandleSizePx.coerceIn(3f, 14f)
        anchor.inHandle?.let {
            val handle = toScreen(anchor.point + it)
            canvas.drawLine(center.x, center.y, handle.x, handle.y, handlePaint)
            canvas.drawCircle(handle.x, handle.y, handleSize * 0.8f, handlePaint)
        }
        anchor.outHandle?.let {
            val handle = toScreen(anchor.point + it)
            canvas.drawLine(center.x, center.y, handle.x, handle.y, handlePaint)
            canvas.drawCircle(handle.x, handle.y, handleSize * 0.8f, handlePaint)
        }
        canvas.drawRect(
            center.x - handleSize,
            center.y - handleSize,
            center.x + handleSize,
            center.y + handleSize,
            anchorPaint
        )
    }
}
