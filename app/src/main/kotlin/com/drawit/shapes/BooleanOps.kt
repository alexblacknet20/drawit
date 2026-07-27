package com.drawit.shapes

import android.graphics.Path as AndroidPath
import android.graphics.PathMeasure
import com.drawit.core.document.Fill
import com.drawit.core.document.Shape
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathCommand
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point
import kotlin.math.abs

/**
 * Destructive boolean operations via Skia (android.graphics.Path.op).
 *
 * Result curves are flattened to polylines with adaptive sampling
 * (android.graphics.Path doesn't expose command iteration).
 * Phase 3 (native Skia) will preserve beziers — the op engine call sites
 * won't change.
 */
object BooleanOps {

    enum class Op(val displayName: String) {
        UNION("Weld"),
        DIFFERENCE("Trim"),
        INTERSECT("Intersect"),
        XOR("Exclude")
    }

    /**
     * Combine shapes in z-order (bottom first).
     * UNION/INTERSECT/XOR fold pairwise; DIFFERENCE subtracts everything
     * above the bottom shape from the bottom shape.
     * Returns null if the result is empty or inputs invalid.
     */
    fun combine(shapesInZOrder: List<Shape>, op: Op, toleranceMm: Float = 0.05f): Shape.PathShape? {
        if (shapesInZOrder.size < 2) return null
        val paths = shapesInZOrder.map { toAndroidPath(it.path()) }

        var result = paths.first()
        when (op) {
            Op.DIFFERENCE -> {
                for (i in 1 until paths.size) {
                    val next = AndroidPath()
                    if (!next.op(result, paths[i], AndroidPath.Op.DIFFERENCE)) return null
                    result = next
                }
            }
            else -> {
                val androidOp = when (op) {
                    Op.UNION -> AndroidPath.Op.UNION
                    Op.INTERSECT -> AndroidPath.Op.INTERSECT
                    Op.XOR -> AndroidPath.Op.XOR
                    Op.DIFFERENCE -> AndroidPath.Op.DIFFERENCE // unreachable
                }
                for (i in 1 until paths.size) {
                    val next = AndroidPath()
                    if (!next.op(result, paths[i], androidOp)) return null
                    result = next
                }
            }
        }

        val pathData = flatten(result, toleranceMm)
        if (pathData.isEmpty) return null

        // Inherit style from the bottom shape (Corel behavior)
        val source = shapesInZOrder.first()
        return Shape.PathShape(
            name = op.displayName,
            pathData = pathData,
            fill = source.fill,
            stroke = source.stroke,
            opacity = source.opacity,
            blendMode = source.blendMode,
            effects = source.effects
        )
    }

    fun toAndroidPath(pathData: PathData): AndroidPath {
        val p = AndroidPath()
        p.fillType = when (pathData.fillRule) {
            PathData.FillRule.EVEN_ODD -> AndroidPath.FillType.EVEN_ODD
            PathData.FillRule.NON_ZERO -> AndroidPath.FillType.WINDING
        }
        for (cmd in pathData.commands) {
            when (cmd) {
                is PathCommand.MoveTo -> p.moveTo(cmd.point.x, cmd.point.y)
                is PathCommand.LineTo -> p.lineTo(cmd.point.x, cmd.point.y)
                is PathCommand.CubicTo -> p.cubicTo(
                    cmd.cp1.x, cmd.cp1.y, cmd.cp2.x, cmd.cp2.y, cmd.end.x, cmd.end.y)
                is PathCommand.QuadTo -> p.quadTo(cmd.cp.x, cmd.cp.y, cmd.end.x, cmd.end.y)
                PathCommand.Close -> p.close()
            }
        }
        return p
    }

    /**
     * Flatten an android.graphics.Path to PathData polylines via
     * adaptive recursive subdivision on PathMeasure:
     * split each length-chunk until the curve midpoint deviates
     * from the chord midpoint by less than [tolerance].
     */
    fun flatten(path: AndroidPath, tolerance: Float = 0.05f): PathData {
        val commands = mutableListOf<PathCommand>()
        val measure = PathMeasure(path, false)
        val pos = FloatArray(2)
        var pathData = PathData.EMPTY

        do {
            val length = measure.length
            if (length <= 0f) continue

            var contourStart: Point? = null
            var last: Point

            fun sample(d: Float): Point {
                measure.getPosTan(d.coerceIn(0f, length), pos, null)
                return Point(pos[0], pos[1])
            }

            // Recursive subdivision emitting line segments
            fun subdivide(d0: Float, d1: Float, p0: Point, p1: Point, depth: Int) {
                val midD = (d0 + d1) / 2f
                val midP = sample(midD)
                val chordMid = p0.midpoint(p1)
                val deviation = midP.distanceTo(chordMid)
                if ((deviation < tolerance || depth > 12 || (d1 - d0) < 0.01f)) {
                    commands.add(PathCommand.LineTo(p1))
                } else {
                    subdivide(d0, midD, p0, midP, depth + 1)
                    subdivide(midD, d1, midP, p1, depth + 1)
                }
            }

            val start = sample(0f)
            contourStart = start
            commands.add(PathCommand.MoveTo(start))

            // Coarse initial chunks, refined adaptively
            val coarseStep = maxOf(length / 64f, tolerance * 4f)
            var d0 = 0f
            var p0 = start
            while (d0 < length) {
                val d1 = minOf(d0 + coarseStep, length)
                val p1 = sample(d1)
                subdivide(d0, d1, p0, p1, 0)
                d0 = d1
                p0 = p1
            }
            last = p0

            // Close if the contour loops back to its start
            if (contourStart.distanceTo(last) < tolerance * 2f) {
                commands.add(PathCommand.Close)
            }
        } while (measure.nextContour())

        pathData = PathData(commands, PathData.FillRule.NON_ZERO)
        return pathData
    }

    private fun AndroidPath.op(a: AndroidPath, b: AndroidPath, op: AndroidPath.Op): Boolean =
        try {
            this.set(a)
            this.op(b, op)
        } catch (e: Exception) {
            false
        }
}

/** Convenience: absolute difference between two floats. */
private fun absf(v: Float) = abs(v)
