package com.drawit.core.geometry

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2D affine transformation matrix: [a c e; b d f; 0 0 1]
 * Used for view transforms, object transforms, etc.
 */
data class Matrix(
    val a: Float = 1f, val c: Float = 0f, val e: Float = 0f,
    val b: Float = 0f, val d: Float = 1f, val f: Float = 0f
) {
    val isIdentity: Boolean
        get() = a == 1f && b == 0f && c == 0f && d == 1f && e == 0f && f == 0f

    fun transform(point: Point): Point = Point(
        a * point.x + c * point.y + e,
        b * point.x + d * point.y + f
    )

    fun transform(rect: Rect): Rect {
        if (isIdentity) return rect
        val points = rect.corners().map { transform(it) }
        return Rect.unionAll(points.map { Rect(it.x, it.y, it.x, it.y) })
    }

    fun transform(points: List<Point>): List<Point> = points.map { transform(it) }

    operator fun times(other: Matrix): Matrix = Matrix(
        a = a * other.a + c * other.b,
        c = a * other.c + c * other.d,
        e = a * other.e + c * other.f + e,
        b = b * other.a + d * other.b,
        d = b * other.c + d * other.d,
        f = b * other.e + d * other.f + f
    )

    fun invert(): Matrix {
        val det = a * d - b * c
        if (det == 0f) return IDENTITY // degenerate; return identity
        val invDet = 1f / det
        return Matrix(
            a = d * invDet,
            c = -c * invDet,
            e = (c * f - d * e) * invDet,
            b = -b * invDet,
            d = a * invDet,
            f = (b * e - a * f) * invDet
        )
    }

    val scaleX: Float get() = kotlin.math.sqrt(a * a + b * b)
    val scaleY: Float get() = kotlin.math.sqrt(c * c + d * d)

    companion object {
        val IDENTITY = Matrix()

        fun translate(dx: Float, dy: Float) = Matrix(e = dx, f = dy)
        fun scale(sx: Float, sy: Float) = Matrix(a = sx, d = sy)
        fun scale(s: Float) = scale(s, s)
        fun rotate(angleRad: Float): Matrix {
            val c = cos(angleRad)
            val s = sin(angleRad)
            return Matrix(a = c, c = -s, b = s, d = c)
        }
        fun rotate(angleRad: Float, pivot: Point): Matrix =
            translate(pivot.x, pivot.y) * rotate(angleRad) * translate(-pivot.x, -pivot.y)
        fun scale(sx: Float, sy: Float, pivot: Point): Matrix =
            translate(pivot.x, pivot.y) * scale(sx, sy) * translate(-pivot.x, -pivot.y)
        fun skew(kx: Float, ky: Float) = Matrix(a = 1f, c = kx, b = ky, d = 1f)
    }
}
