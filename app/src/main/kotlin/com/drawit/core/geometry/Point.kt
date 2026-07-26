package com.drawit.core.geometry

import kotlin.math.sqrt

/**
 * 2D point / vector in document space (float precision, millimeters as base unit).
 */
data class Point(val x: Float = 0f, val y: Float = 0f) {

    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun minus(other: Point) = Point(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Point(x * scalar, y * scalar)
    operator fun div(scalar: Float) = Point(x / scalar, y / scalar)
    operator fun unaryMinus() = Point(-x, -y)

    fun dot(other: Point): Float = x * other.x + y * other.y
    fun cross(other: Point): Float = x * other.y - y * other.x

    val length: Float get() = sqrt(x * x + y * y)

    fun normalized(): Point {
        val len = length
        return if (len > 0f) this / len else ZERO
    }

    fun distanceTo(other: Point): Float = (this - other).length

    fun midpoint(other: Point): Point = (this + other) / 2f

    fun rotateAround(center: Point, angleRad: Float): Point {
        val cos = kotlin.math.cos(angleRad)
        val sin = kotlin.math.sin(angleRad)
        val translated = this - center
        return Point(
            translated.x * cos - translated.y * sin,
            translated.x * sin + translated.y * cos
        ) + center
    }

    companion object {
        val ZERO = Point(0f, 0f)
        val INFINITY = Point(Float.MAX_VALUE, Float.MAX_VALUE)
    }
}
