package com.drawit.core.document

import com.drawit.core.color.Color
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Rect

/**
 * Text object. Two flavors:
 *  - ARTISTIC: single line (or manual line breaks), grows from anchor point
 *  - PARAGRAPH: wrapped to [frameWidth], auto-height
 *
 * Text color comes from [fill] (solid only); [stroke] outlines glyphs.
 * Bounds are measured by the platform text engine (FontManager) and
 * cached in [measuredBounds] — kept in sync on every edit.
 */
data class TextShape(
    override val id: String = Shape.newId(),
    override val name: String = "Text",
    val text: String = "",
    val kind: Kind = Kind.ARTISTIC,
    val fontFamily: String = "sans-serif",   // FontManager key
    val textSize: Float = 12f,               // mm
    val frameWidth: Float = 0f,              // paragraph only (mm)
    val align: Align = Align.LEFT,
    val lineSpacing: Float = 1.2f,           // multiplier
    /** Bounds measured by text engine; updated on edit. */
    val measuredBounds: Rect = Rect.EMPTY,
    override val transform: Matrix = Matrix.IDENTITY,
    override val fill: Fill = Fill.Solid(Color.BLACK),
    override val stroke: Stroke? = null,
    override val visible: Boolean = true,
    override val locked: Boolean = false,
    override val opacity: Float = 1f,
    override val blendMode: BlendMode = BlendMode.NORMAL
) : Shape() {

    enum class Kind { ARTISTIC, PARAGRAPH }
    enum class Align(val displayName: String) { LEFT("Left"), CENTER("Center"), RIGHT("Right") }

    override fun localBounds(): Rect = measuredBounds
    override fun localPath(): PathData = PathData.EMPTY // text outlines via FontManager when needed

    override fun copyWith(
        id: String, name: String, transform: Matrix, fill: Fill,
        stroke: Stroke?, visible: Boolean, locked: Boolean,
        opacity: Float, blendMode: BlendMode
    ) = copy(
        id = id, name = name, transform = transform, fill = fill,
        stroke = stroke, visible = visible, locked = locked,
        opacity = opacity, blendMode = blendMode
    )
}
