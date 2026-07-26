package com.drawit.core.document

import com.drawit.core.color.Color
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Rect
import java.util.UUID

/**
 * Stroke style for a shape.
 */
data class Stroke(
    val color: Color = Color.BLACK,
    val width: Float = 1f,          // in document units (mm)
    val cap: Cap = Cap.BUTT,
    val join: Join = Join.MITER,
    val miterLimit: Float = 4f,
    val dashPattern: List<Float> = emptyList() // alternating on/off lengths (mm)
) {
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }

    companion object {
        val DASH_PRESETS: Map<String, List<Float>> = mapOf(
            "Solid" to emptyList(),
            "Dashed" to listOf(4f, 2f),
            "Dotted" to listOf(0.5f, 2f),
            "Dash-Dot" to listOf(4f, 2f, 0.5f, 2f)
        )
    }
}

/**
 * Base for all drawable objects in a document.
 * Transform is applied around the object's local origin.
 */
sealed class Shape {
    abstract val id: String
    abstract val name: String
    abstract val transform: Matrix
    abstract val fill: Fill
    abstract val stroke: Stroke?
    abstract val visible: Boolean
    abstract val locked: Boolean
    abstract val opacity: Float          // 0.0–1.0, multiplies fill+stroke alpha
    abstract val blendMode: BlendMode

    /** Bounding box in local coordinates (before transform). */
    abstract fun localBounds(): Rect

    /** Bounding box in parent (layer) coordinates. */
    fun bounds(): Rect = transform.transform(localBounds())

    /** Path outline in local coordinates. */
    abstract fun localPath(): PathData

    /** Path in parent coordinates. */
    fun path(): PathData = localPath().transform(transform)

    protected abstract fun copyWith(
        id: String = this.id,
        name: String = this.name,
        transform: Matrix = this.transform,
        fill: Fill = this.fill,
        stroke: Stroke? = this.stroke,
        visible: Boolean = this.visible,
        locked: Boolean = this.locked,
        opacity: Float = this.opacity,
        blendMode: BlendMode = this.blendMode
    ): Shape

    fun withTransform(t: Matrix): Shape = copyWith(transform = t)
    fun withFill(f: Fill): Shape = copyWith(fill = f)
    fun withStroke(s: Stroke?): Shape = copyWith(stroke = s)
    fun withVisible(v: Boolean): Shape = copyWith(visible = v)
    fun withLocked(l: Boolean): Shape = copyWith(locked = l)
    fun withName(n: String): Shape = copyWith(name = n)
    fun withOpacity(o: Float): Shape = copyWith(opacity = o.coerceIn(0f, 1f))
    fun withBlendMode(m: BlendMode): Shape = copyWith(blendMode = m)

    // ---------------------------------------------------------------

    /** Freeform vector path. */
    data class PathShape(
        override val id: String = newId(),
        override val name: String = "Path",
        val pathData: PathData = PathData.EMPTY,
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.None,
        override val stroke: Stroke? = Stroke(),
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL
    ) : Shape() {
        override fun localBounds(): Rect = pathData.bounds()
        override fun localPath(): PathData = pathData
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode)
    }

    /** Parametric rectangle (keeps corner radius editable). */
    data class RectShape(
        override val id: String = newId(),
        override val name: String = "Rectangle",
        val rect: Rect = Rect(0f, 0f, 100f, 100f),
        val cornerRadius: Float = 0f,
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.Solid(Color.LIGHT_GRAY),
        override val stroke: Stroke? = Stroke(),
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL
    ) : Shape() {
        override fun localBounds(): Rect = rect
        override fun localPath(): PathData = PathData.rect(rect)
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode)
    }

    /** Parametric ellipse. */
    data class EllipseShape(
        override val id: String = newId(),
        override val name: String = "Ellipse",
        val rect: Rect = Rect(0f, 0f, 100f, 100f),
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.Solid(Color.LIGHT_GRAY),
        override val stroke: Stroke? = Stroke(),
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL
    ) : Shape() {
        override fun localBounds(): Rect = rect
        override fun localPath(): PathData = PathData.ellipse(rect)
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode)
    }

    /** Group of child shapes. */
    data class GroupShape(
        override val id: String = newId(),
        override val name: String = "Group",
        val children: List<Shape> = emptyList(),
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.None,
        override val stroke: Stroke? = null,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL
    ) : Shape() {
        override fun localBounds(): Rect =
            Rect.unionAll(children.filter { it.visible }.map { it.bounds() })
        override fun localPath(): PathData =
            children.fold(PathData.EMPTY) { acc, s ->
                // Simplified for scaffold; proper subpath merge in Phase 2
                acc
            }
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
