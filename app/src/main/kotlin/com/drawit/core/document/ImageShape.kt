package com.drawit.core.document

import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Rect

/**
 * Embedded bitmap image. Pixel data lives in ImageStore, referenced by [imageId]
 * (content hash). The shape just positions/sizes it.
 */
data class ImageShape(
    override val id: String = Shape.newId(),
    override val name: String = "Image",
    val imageId: String = "",
    val rect: Rect = Rect(0f, 0f, 100f, 100f), // placement in local coords (mm)
    override val transform: Matrix = Matrix.IDENTITY,
    override val fill: Fill = Fill.None,
    override val stroke: Stroke? = null,
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
    ) = copy(
        id = id, name = name, transform = transform, fill = fill,
        stroke = stroke, visible = visible, locked = locked,
        opacity = opacity, blendMode = blendMode
    )
}
