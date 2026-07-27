package com.drawit.canvas

import com.drawit.core.document.CornerStyle
import com.drawit.core.document.EffectStack
import com.drawit.core.document.Shape
import com.drawit.core.document.ShadowEffect
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorFeaturesTest {

    @Test
    fun groupAndUngroupPreserveChildren() {
        val state = EditorState()
        val rectangle = Shape.RectShape(rect = Rect(10f, 10f, 30f, 30f))
        val ellipse = Shape.EllipseShape(rect = Rect(40f, 10f, 60f, 30f))
        state.addShape(rectangle)
        state.addShape(ellipse)
        state.select(setOf(rectangle.id, ellipse.id))

        state.groupSelected()

        val group = state.selectedShapes().single() as Shape.GroupShape
        assertEquals(listOf(rectangle.id, ellipse.id), group.children.map { it.id })

        state.ungroupSelected()
        assertEquals(setOf(rectangle.id, ellipse.id), state.selectedShapeIds)
        assertEquals(2, state.document.activePage.activeLayer().shapes.size)
    }

    @Test
    fun powerClipUsesTopmostShapeAsContainer() {
        val state = EditorState()
        val content = Shape.EllipseShape(rect = Rect(0f, 0f, 100f, 100f))
        val frame = Shape.RectShape(rect = Rect(10f, 10f, 90f, 90f))
        state.addShape(content)
        state.addShape(frame)
        state.select(setOf(content.id, frame.id))

        state.createPowerClip()

        val clip = state.selectedShapes().single() as Shape.GroupShape
        assertNotNull(clip.clipPath)
        assertEquals(listOf(content.id), clip.children.map { it.id })
    }

    @Test
    fun polygonAndCornerTreatmentsProduceEditablePaths() {
        val polygon = Shape.PolygonShape(
            rect = Rect(0f, 0f, 100f, 100f),
            sides = 8
        )
        assertEquals(9, polygon.localPath().commands.size)

        CornerStyle.entries.forEach { style ->
            val rectangle = Shape.RectShape(
                rect = Rect(0f, 0f, 100f, 50f),
                cornerRadius = 8f,
                cornerStyle = style
            )
            assertTrue(rectangle.localPath().commands.size > 5)
        }
    }

    @Test
    fun effectsAndFontStyleSurviveShapeCopies() {
        val effects = EffectStack(
            dropShadow = ShadowEffect(offsetX = 4f, blurRadius = 6f),
            edgeBlurRadius = 1.5f,
            innerShadow = ShadowEffect(offsetY = -2f),
            noiseAmount = 0.25f
        )
        val text = TextShape(
            text = "DrawIt",
            fontWeight = TextShape.Weight.BOLD,
            italic = true,
            effects = effects
        )

        val moved = text.withTransform(
            com.drawit.core.geometry.Matrix.translate(10f, 20f)
        ) as TextShape

        assertEquals(TextShape.Weight.BOLD, moved.fontWeight)
        assertTrue(moved.italic)
        assertEquals(effects, moved.effects)
    }

    @Test
    fun smartAlignmentsCanBeDisabled() {
        val state = EditorState()
        assertTrue(state.smartAlignmentsEnabled)
        state.smartAlignmentsEnabled = false
        assertEquals(false, state.smartAlignmentsEnabled)
    }

    @Test
    fun ellipseSupportsPieArcAndRingParameters() {
        val pie = Shape.EllipseShape(
            rect = Rect(0f, 0f, 100f, 80f),
            startAngleDegrees = 30f,
            sweepDegrees = 120f,
            arcRatio = 0f
        )
        assertTrue(pie.localPath().commands.size >= 5)

        val ring = pie.copy(sweepDegrees = 360f, arcRatio = 0.6f)
        assertEquals(
            com.drawit.core.geometry.PathData.FillRule.EVEN_ODD,
            ring.localPath().fillRule
        )
        assertTrue(ring.localPath().commands.size > pie.localPath().commands.size)
    }

    @Test
    fun controlHandleSizeIsConfigurable() {
        val state = EditorState()
        state.controlHandleSizePx = 11f
        assertEquals(11f, state.controlHandleSizePx)
    }

    @Test
    fun viewportActionsAlwaysRequestCanvasRedraw() {
        val state = EditorState()
        state.setCanvasSize(1080f, 1920f)
        val afterFit = state.viewportVersion

        state.zoomTo100Percent()
        val afterActualSize = state.viewportVersion
        state.centerSelectionOrArtboard()

        assertTrue(afterActualSize > afterFit)
        assertTrue(state.viewportVersion > afterActualSize)
    }

    @Test
    fun artboardsHaveIndependentNamesAndSizes() {
        val state = EditorState()
        state.addArtboard(name = "Poster", width = 500f, height = 700f)

        assertEquals(2, state.document.pages.size)
        assertEquals("Poster", state.document.activePage.name)
        assertEquals(500f, state.document.activePage.width)
        assertEquals(700f, state.document.activePage.height)

        state.setActiveArtboard(0)
        state.updateActiveArtboard("Business card", 90f, 50f)

        assertEquals("Business card", state.document.pages[0].name)
        assertEquals(90f, state.document.pages[0].width)
        assertEquals("Poster", state.document.pages[1].name)
    }
}
