package com.drawit.core.document

import com.drawit.core.geometry.Rect

/**
 * Document color mode — affects export pipeline and (later) soft-proofing.
 */
enum class ColorMode(val displayName: String) {
    RGB("RGB (Digital)"),
    CMYK("CMYK (Print)")
}

/**
 * Bleed/margins around the page, in mm per side.
 */
data class Margins(
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val left: Float = 0f
) {
    val isZero: Boolean get() = top == 0f && right == 0f && bottom == 0f && left == 0f
    companion object {
        val ZERO = Margins()
        fun uniform(v: Float) = Margins(v, v, v, v)
    }
}

/**
 * A layer: ordered list of shapes with visibility/lock state.
 */
data class Layer(
    val id: String = Shape.newId(),
    val name: String = "Layer",
    val shapes: List<Shape> = emptyList(),
    val visible: Boolean = true,
    val locked: Boolean = false
) {
    fun addShape(shape: Shape): Layer = copy(shapes = shapes + shape)
    fun removeShape(shapeId: String): Layer =
        copy(shapes = shapes.filter { it.id != shapeId })
    fun replaceShape(shapeId: String, newShape: Shape): Layer =
        copy(shapes = shapes.map { if (it.id == shapeId) newShape else it })
    fun findShape(shapeId: String): Shape? = shapes.find { it.id == shapeId }
    fun moveShape(shapeId: String, toIndex: Int): Layer {
        val shape = findShape(shapeId) ?: return this
        val without = shapes.filter { it.id != shapeId }
        val idx = toIndex.coerceIn(0, without.size)
        return copy(shapes = without.toMutableList().apply { add(idx, shape) })
    }

    fun bounds(): Rect =
        Rect.unionAll(shapes.filter { it.visible }.map { it.bounds() })
}

/**
 * A page in the document (CorelDRAW-style multi-page).
 */
data class Page(
    val id: String = Shape.newId(),
    val name: String = "Page 1",
    val width: Float = 210f,   // mm (A4 default)
    val height: Float = 297f,  // mm
    val bleed: Margins = Margins.ZERO,
    val layers: List<Layer> = listOf(Layer(name = "Layer 1")),
    val activeLayerId: String = layers.first().id
) {
    val size: Rect get() = Rect(0f, 0f, width, height)

    /** Page size including bleed on all sides. */
    val sizeWithBleed: Rect get() = Rect(
        -bleed.left, -bleed.top,
        width + bleed.right, height + bleed.bottom
    )

    fun activeLayer(): Layer = layers.find { it.id == activeLayerId } ?: layers.first()

    fun addLayer(name: String): Page {
        val layer = Layer(name = name)
        return copy(layers = layers + layer, activeLayerId = layer.id)
    }

    fun removeLayer(layerId: String): Page {
        if (layers.size <= 1) return this // keep at least one layer
        val newLayers = layers.filter { it.id != layerId }
        return copy(
            layers = newLayers,
            activeLayerId = if (activeLayerId == layerId) newLayers.first().id else activeLayerId
        )
    }

    fun updateLayer(layerId: String, transform: (Layer) -> Layer): Page =
        copy(layers = layers.map { if (it.id == layerId) transform(it) else it })

    fun addShapeToActiveLayer(shape: Shape): Page =
        updateLayer(activeLayerId) { it.addShape(shape) }

    fun findShape(shapeId: String): Pair<Layer, Shape>? {
        for (layer in layers) {
            layer.findShape(shapeId)?.let { return layer to it }
        }
        return null
    }

    fun removeShape(shapeId: String): Page =
        copy(layers = layers.map { it.removeShape(shapeId) })

    fun replaceShape(shapeId: String, newShape: Shape): Page =
        copy(layers = layers.map { it.replaceShape(shapeId, newShape) })

    fun setActiveLayer(layerId: String): Page =
        if (layers.any { it.id == layerId }) copy(activeLayerId = layerId) else this
}

/**
 * The document: metadata + pages.
 * Immutable — every edit produces a new Document (works with undo/redo).
 */
data class Document(
    val id: String = Shape.newId(),
    val name: String = "Untitled",
    val pages: List<Page> = listOf(Page()),
    val activePageIndex: Int = 0,
    val dpi: Float = 96f,
    val colorMode: ColorMode = ColorMode.RGB,
    val displayUnit: Unit = Unit.MM
) {
    val activePage: Page get() = pages[activePageIndex.coerceIn(0, pages.size - 1)]

    fun addPage(name: String? = null): Document {
        val page = Page(name = name ?: "Page ${pages.size + 1}")
        return copy(pages = pages + page, activePageIndex = pages.size)
    }

    fun removePage(index: Int): Document {
        if (pages.size <= 1) return this
        val newPages = pages.filterIndexed { i, _ -> i != index }
        return copy(
            pages = newPages,
            activePageIndex = activePageIndex.coerceIn(0, newPages.size - 1)
        )
    }

    fun updateActivePage(transform: (Page) -> Page): Document =
        copy(pages = pages.mapIndexed { i, p ->
            if (i == activePageIndex) transform(p) else p
        })

    fun addShape(shape: Shape): Document =
        updateActivePage { it.addShapeToActiveLayer(shape) }

    fun removeShape(shapeId: String): Document =
        copy(pages = pages.map { it.removeShape(shapeId) })

    fun replaceShape(shapeId: String, newShape: Shape): Document =
        copy(pages = pages.map { it.replaceShape(shapeId, newShape) })

    fun findShape(shapeId: String): Shape? {
        for (page in pages) {
            page.findShape(shapeId)?.let { return it.second }
        }
        return null
    }
}
