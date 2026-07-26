package com.drawit.core.geometry

/**
 * Immutable path command list (SVG-compatible semantics).
 * Commands reference absolute coordinates in document space.
 */
sealed class PathCommand {
    data class MoveTo(val point: Point) : PathCommand()
    data class LineTo(val point: Point) : PathCommand()
    data class CubicTo(val cp1: Point, val cp2: Point, val end: Point) : PathCommand()
    data class QuadTo(val cp: Point, val end: Point) : PathCommand()
    data object Close : PathCommand()
}

/**
 * A vector path: a list of commands with a winding rule.
 */
data class PathData(
    val commands: List<PathCommand> = emptyList(),
    val fillRule: FillRule = FillRule.NON_ZERO
) {
    enum class FillRule { NON_ZERO, EVEN_ODD }

    val isEmpty: Boolean get() = commands.isEmpty()

    fun moveTo(p: Point) = copy(commands = commands + PathCommand.MoveTo(p))
    fun lineTo(p: Point) = copy(commands = commands + PathCommand.LineTo(p))
    fun cubicTo(cp1: Point, cp2: Point, end: Point) =
        copy(commands = commands + PathCommand.CubicTo(cp1, cp2, end))
    fun quadTo(cp: Point, end: Point) =
        copy(commands = commands + PathCommand.QuadTo(cp, end))
    fun close() = copy(commands = commands + PathCommand.Close)

    /** Compute the axis-aligned bounding box by flattening curves. */
    fun bounds(): Rect {
        if (commands.isEmpty()) return Rect.EMPTY
        var bounds: Rect? = null
        var current = Point.ZERO
        var subpathStart = Point.ZERO

        fun expand(p: Point) {
            bounds = bounds?.expandToInclude(p) ?: Rect(p.x, p.y, p.x, p.y)
        }

        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo -> {
                    current = cmd.point
                    subpathStart = cmd.point
                    expand(current)
                }
                is PathCommand.LineTo -> {
                    current = cmd.point
                    expand(current)
                }
                is PathCommand.CubicTo -> {
                    // Approximate curve bounds by sampling (control hull is a safe over-estimate)
                    expand(cmd.cp1)
                    expand(cmd.cp2)
                    expand(cmd.end)
                    current = cmd.end
                }
                is PathCommand.QuadTo -> {
                    expand(cmd.cp)
                    expand(cmd.end)
                    current = cmd.end
                }
                PathCommand.Close -> current = subpathStart
            }
        }
        return bounds ?: Rect.EMPTY
    }

    fun transform(matrix: Matrix): PathData {
        if (matrix.isIdentity) return this
        return copy(commands = commands.map { cmd ->
            when (cmd) {
                is PathCommand.MoveTo -> PathCommand.MoveTo(matrix.transform(cmd.point))
                is PathCommand.LineTo -> PathCommand.LineTo(matrix.transform(cmd.point))
                is PathCommand.CubicTo -> PathCommand.CubicTo(
                    matrix.transform(cmd.cp1),
                    matrix.transform(cmd.cp2),
                    matrix.transform(cmd.end)
                )
                is PathCommand.QuadTo -> PathCommand.QuadTo(
                    matrix.transform(cmd.cp),
                    matrix.transform(cmd.end)
                )
                PathCommand.Close -> PathCommand.Close
            }
        })
    }

    companion object {
        val EMPTY = PathData()

        /** Build a rectangle path. */
        fun rect(rect: Rect): PathData = EMPTY
            .moveTo(rect.topLeft)
            .lineTo(rect.topRight)
            .lineTo(rect.bottomRight)
            .lineTo(rect.bottomLeft)
            .close()

        /** Build an ellipse path via 4 cubic beziers (kappa approximation). */
        fun ellipse(rect: Rect): PathData {
            val kappa = 0.5522847498307936f
            val cx = rect.centerX
            val cy = rect.centerY
            val rx = rect.width / 2f
            val ry = rect.height / 2f
            val kx = rx * kappa
            val ky = ry * kappa
            return EMPTY
                .moveTo(Point(cx - rx, cy))
                .cubicTo(Point(cx - rx, cy - ky), Point(cx - kx, cy - ry), Point(cx, cy - ry))
                .cubicTo(Point(cx + kx, cy - ry), Point(cx + rx, cy - ky), Point(cx + rx, cy))
                .cubicTo(Point(cx + rx, cy + ky), Point(cx + kx, cy + ry), Point(cx, cy + ry))
                .cubicTo(Point(cx - kx, cy + ry), Point(cx - rx, cy + ky), Point(cx - rx, cy))
                .close()
        }

        /** Build a regular polygon path. */
        fun polygon(center: Point, radius: Float, sides: Int, startAngleRad: Float = 0f): PathData {
            require(sides >= 3) { "Polygon needs at least 3 sides" }
            var path = EMPTY
            for (i in 0 until sides) {
                val angle = startAngleRad + (2f * Math.PI.toFloat() * i / sides)
                val p = Point(
                    center.x + radius * kotlin.math.cos(angle),
                    center.y + radius * kotlin.math.sin(angle)
                )
                path = if (i == 0) path.moveTo(p) else path.lineTo(p)
            }
            return path.close()
        }
    }
}
