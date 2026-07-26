package com.drawit.core.document

import com.drawit.core.color.Color
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTest {

    @Test
    fun `new document has one page with one layer`() {
        val doc = Document()
        assertEquals(1, doc.pages.size)
        assertEquals(1, doc.activePage.layers.size)
        // A4 default
        assertEquals(210f, doc.activePage.width, 1e-4f)
        assertEquals(297f, doc.activePage.height, 1e-4f)
    }

    @Test
    fun `add shape to active layer`() {
        val doc = Document()
        val rect = Shape.RectShape(rect = Rect(0f, 0f, 50f, 50f))
        val newDoc = doc.addShape(rect)

        assertEquals(1, newDoc.activePage.activeLayer().shapes.size)
        assertNotNull(newDoc.findShape(rect.id))
        // Original unchanged (immutability)
        assertEquals(0, doc.activePage.activeLayer().shapes.size)
    }

    @Test
    fun `remove shape`() {
        val rect = Shape.RectShape()
        val doc = Document().addShape(rect)
        val removed = doc.removeShape(rect.id)
        assertNull(removed.findShape(rect.id))
    }

    @Test
    fun `replace shape preserves others`() {
        val r1 = Shape.RectShape(name = "A")
        val r2 = Shape.RectShape(name = "B")
        val doc = Document().addShape(r1).addShape(r2)

        val modified = r1.withFill(Fill.Solid(Color.RED))
        val newDoc = doc.replaceShape(r1.id, modified)

        assertEquals(2, newDoc.activePage.activeLayer().shapes.size)
        assertEquals(Fill.Solid(Color.RED), newDoc.findShape(r1.id)?.fill)
        assertNotNull(newDoc.findShape(r2.id))
    }

    @Test
    fun `layers cannot be reduced below one`() {
        val doc = Document()
        val page = doc.activePage
        val reduced = page.removeLayer(page.layers.first().id)
        assertEquals(1, reduced.layers.size)
    }

    @Test
    fun `add layer makes it active`() {
        val doc = Document()
        val page = doc.activePage.addLayer("Layer 2")
        assertEquals(2, page.layers.size)
        assertEquals("Layer 2", page.activeLayer().name)
    }

    @Test
    fun `shape transform affects bounds`() {
        val rect = Shape.RectShape(rect = Rect(0f, 0f, 10f, 10f))
        val moved = rect.withTransform(Matrix.translate(100f, 0f))
        val bounds = moved.bounds()
        assertEquals(100f, bounds.left, 1e-4f)
        assertEquals(110f, bounds.right, 1e-4f)
    }

    @Test
    fun `group bounds union of children`() {
        val a = Shape.RectShape(rect = Rect(0f, 0f, 10f, 10f))
        val b = Shape.RectShape(rect = Rect(50f, 50f, 60f, 60f))
        val group = Shape.GroupShape(children = listOf(a, b))
        val bounds = group.bounds()
        assertEquals(0f, bounds.left, 1e-4f)
        assertEquals(60f, bounds.right, 1e-4f)
    }
}
