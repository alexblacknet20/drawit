package com.drawit.core.document

import com.drawit.core.color.Color

/**
 * A single gradient stop: position 0.0–1.0 along the gradient + color.
 */
data class GradientStop(
    val position: Float, // 0.0–1.0
    val color: Color
) {
    init { require(position in 0f..1f) { "Stop position must be 0..1" } }
}

/**
 * Fill style for a shape.
 */
sealed class Fill {
    data object None : Fill()

    data class Solid(val color: Color) : Fill()

    /** Multi-stop gradient. Angle in degrees (0 = left→right). Radial ignores angle. */
    data class Gradient(
        val type: Type,
        val stops: List<GradientStop>,
        val angleDegrees: Float = 0f
    ) : Fill() {
        enum class Type(val displayName: String) { LINEAR("Linear"), RADIAL("Radial") }

        init {
            require(stops.size >= 2) { "Gradient needs at least 2 stops" }
            require(stops.size <= 8) { "Gradient supports at most 8 stops" }
        }

        fun sortedStops(): List<GradientStop> = stops.sortedBy { it.position }

        fun reversed(): Gradient = copy(
            stops = stops.map { GradientStop(1f - it.position, it.color) }.sortedBy { it.position }
        )

        companion object {
            fun twoStop(type: Type, from: Color, to: Color, angle: Float = 0f) =
                Gradient(type, listOf(GradientStop(0f, from), GradientStop(1f, to)), angle)
        }
    }

    /**
     * Bitmap pattern fill. [imageId] references ImageStore content hash.
     * Placement: TILE (repeat at natural size), FIT (scale to fit, preserve aspect),
     * FILL (scale to cover, preserve aspect), STRETCH (distort to bounds).
     */
    data class Pattern(
        val imageId: String,
        val placement: Placement = Placement.TILE,
        val tileScale: Float = 1f // additional scale for TILE mode
    ) : Fill() {
        enum class Placement(val displayName: String) {
            TILE("Tile"), FIT("Fit"), FILL("Fill"), STRETCH("Stretch")
        }
    }
}
