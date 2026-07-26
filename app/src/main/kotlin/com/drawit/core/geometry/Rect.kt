package com.drawit.core.geometry

/**
 * Axis-aligned bounding rectangle in document space.
 */
data class Rect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val center: Point get() = Point(centerX, centerY)
    val topLeft: Point get() = Point(left, top)
    val topRight: Point get() = Point(right, top)
    val bottomLeft: Point get() = Point(left, bottom)
    val bottomRight: Point get() = Point(right, bottom)
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
    val area: Float get() = width * height

    fun contains(point: Point): Boolean =
        point.x in left..right && point.y in top..bottom

    fun contains(other: Rect): Boolean =
        other.left >= left && other.right <= right &&
        other.top >= top && other.bottom <= bottom

    fun intersects(other: Rect): Boolean =
        left < other.right && right > other.left &&
        top < other.bottom && bottom > other.top

    fun union(other: Rect): Rect = Rect(
        left = minOf(left, other.left),
        top = minOf(top, other.top),
        right = maxOf(right, other.right),
        bottom = maxOf(bottom, other.bottom)
    )

    fun intersect(other: Rect): Rect? {
        val l = maxOf(left, other.left)
        val t = maxOf(top, other.top)
        val r = minOf(right, other.right)
        val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) Rect(l, t, r, b) else null
    }

    fun inset(dx: Float, dy: Float): Rect =
        Rect(left + dx, top + dy, right - dx, bottom - dy)

    fun offset(dx: Float, dy: Float): Rect =
        Rect(left + dx, top + dy, right + dx, bottom + dy)

    fun scale(sx: Float, sy: Float, pivot: Point = center): Rect {
        val l = pivot.x + (left - pivot.x) * sx
        val r = pivot.x + (right - pivot.x) * sx
        val t = pivot.y + (top - pivot.y) * sy
        val b = pivot.y + (bottom - pivot.y) * sy
        return Rect(
            minOf(l, r), minOf(t, b),
            maxOf(l, r), maxOf(t, b)
        )
    }

    fun expandToInclude(point: Point): Rect = Rect(
        left = minOf(left, point.x),
        top = minOf(top, point.y),
        right = maxOf(right, point.x),
        bottom = maxOf(bottom, point.y)
    )

    fun corners(): List<Point> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    companion object {
        val EMPTY = Rect(0f, 0f, 0f, 0f)

        fun fromPoints(p1: Point, p2: Point): Rect = Rect(
            left = minOf(p1.x, p2.x),
            top = minOf(p1.y, p2.y),
            right = maxOf(p1.x, p2.x),
            bottom = maxOf(p1.y, p2.y)
        )

        fun fromCenter(center: Point, width: Float, height: Float): Rect {
            val hw = width / 2f
            val hh = height / 2f
            return Rect(center.x - hw, center.y - hh, center.x + hw, center.y + hh)
        }

        fun unionAll(rects: List<Rect>): Rect {
            if (rects.isEmpty()) return EMPTY
            return rects.reduce { acc, r -> acc.union(r) }
        }
    }
}
