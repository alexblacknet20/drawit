package com.drawit.core.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pure-JVM tests for geometry core — no Android dependencies.
 * These run on the host machine, fast.
 */
class GeometryTest {

    private fun assertFloatEquals(expected: Float, actual: Float, epsilon: Float = 1e-5f) {
        assertTrue("expected $expected but was $actual", abs(expected - actual) < epsilon)
    }

    // --- Point ---

    @Test
    fun `point arithmetic`() {
        val a = Point(3f, 4f)
        val b = Point(1f, 2f)
        assertEquals(Point(4f, 6f), a + b)
        assertEquals(Point(2f, 2f), a - b)
        assertEquals(Point(6f, 8f), a * 2f)
        assertFloatEquals(5f, a.length)
        assertFloatEquals(5f, a.distanceTo(Point.ZERO))
    }

    @Test
    fun `point rotation 90 degrees`() {
        val p = Point(1f, 0f)
        val rotated = p.rotateAround(Point.ZERO, (Math.PI / 2).toFloat())
        assertFloatEquals(0f, rotated.x, 1e-4f)
        assertFloatEquals(1f, rotated.y, 1e-4f)
    }

    // --- Rect ---

    @Test
    fun `rect union and intersect`() {
        val a = Rect(0f, 0f, 10f, 10f)
        val b = Rect(5f, 5f, 15f, 15f)

        val union = a.union(b)
        assertEquals(Rect(0f, 0f, 15f, 15f), union)

        val intersect = a.intersect(b)
        assertEquals(Rect(5f, 5f, 10f, 10f), intersect)
    }

    @Test
    fun `rect disjoint has no intersection`() {
        val a = Rect(0f, 0f, 5f, 5f)
        val b = Rect(10f, 10f, 15f, 15f)
        assertFalse(a.intersects(b))
        assertEquals(null, a.intersect(b))
    }

    @Test
    fun `rect contains point`() {
        val r = Rect(0f, 0f, 10f, 10f)
        assertTrue(r.contains(Point(5f, 5f)))
        assertFalse(r.contains(Point(15f, 5f)))
    }

    // --- Matrix ---

    @Test
    fun `matrix identity leaves points unchanged`() {
        val p = Point(7f, -3f)
        assertEquals(p, Matrix.IDENTITY.transform(p))
    }

    @Test
    fun `matrix translate`() {
        val m = Matrix.translate(5f, 10f)
        assertEquals(Point(6f, 12f), m.transform(Point(1f, 2f)))
    }

    @Test
    fun `matrix scale`() {
        val m = Matrix.scale(2f, 3f)
        assertEquals(Point(4f, 9f), m.transform(Point(2f, 3f)))
    }

    @Test
    fun `matrix inverse roundtrip`() {
        val m = Matrix.translate(3f, 7f) * Matrix.scale(2f) * Matrix.rotate(0.5f)
        val p = Point(4f, 9f)
        val roundtrip = m.invert().transform(m.transform(p))
        assertFloatEquals(p.x, roundtrip.x, 1e-3f)
        assertFloatEquals(p.y, roundtrip.y, 1e-3f)
    }

    @Test
    fun `matrix composition order`() {
        // translate THEN scale: point moves first, then scales
        val m = Matrix.scale(2f) * Matrix.translate(1f, 0f)
        // transform(2,0): translate → (3,0), scale → (6,0)
        assertEquals(Point(6f, 0f), m.transform(Point(2f, 0f)))
    }

    // --- PathData ---

    @Test
    fun `path bounds of rect`() {
        val path = PathData.rect(Rect(10f, 20f, 30f, 50f))
        val bounds = path.bounds()
        assertFloatEquals(10f, bounds.left)
        assertFloatEquals(20f, bounds.top)
        assertFloatEquals(30f, bounds.right)
        assertFloatEquals(50f, bounds.bottom)
    }

    @Test
    fun `path transform moves bounds`() {
        val path = PathData.rect(Rect(0f, 0f, 10f, 10f))
        val moved = path.transform(Matrix.translate(100f, 50f))
        val bounds = moved.bounds()
        assertFloatEquals(100f, bounds.left)
        assertFloatEquals(50f, bounds.top)
        assertFloatEquals(110f, bounds.right)
        assertFloatEquals(60f, bounds.bottom)
    }

    @Test
    fun `ellipse path fits bounds`() {
        val rect = Rect(0f, 0f, 100f, 60f)
        val path = PathData.ellipse(rect)
        val bounds = path.bounds()
        // Control-hull bounds may slightly exceed, but should be close
        assertFloatEquals(0f, bounds.left, 1f)
        assertFloatEquals(100f, bounds.right, 1f)
    }

    @Test
    fun `polygon has correct number of points`() {
        val path = PathData.polygon(Point(0f, 0f), 10f, sides = 5)
        val moveCount = path.commands.count { it is PathCommand.MoveTo }
        val lineCount = path.commands.count { it is PathCommand.LineTo }
        val closeCount = path.commands.count { it is PathCommand.Close }
        assertEquals(1, moveCount)
        assertEquals(4, lineCount) // 5 sides = 1 move + 4 lines + close
        assertEquals(1, closeCount)
    }
}
