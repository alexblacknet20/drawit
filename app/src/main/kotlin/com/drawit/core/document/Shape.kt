package com.drawit.core.document

import com.drawit.core.color.Color
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Rect
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

enum class CornerStyle(val displayName: String) {
    ROUND("Round"),
    CHAMFER("Chamfer"),
    INSET("Inset")
}

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

/** Non-destructive visual effects applied to a shape. */
data class ShadowEffect(
    val offsetX: Float = 2f,
    val offsetY: Float = 2f,
    val blurRadius: Float = 3f,
    val color: Color = Color.BLACK,
    val opacity: Float = 0.45f
)

data class EffectStack(
    val dropShadow: ShadowEffect? = null,
    val edgeBlurRadius: Float = 0f,
    val innerShadow: ShadowEffect? = null,
    val noiseAmount: Float = 0f
)

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
    abstract val effects: EffectStack

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
        blendMode: BlendMode = this.blendMode,
        effects: EffectStack = this.effects
    ): Shape

    fun withTransform(t: Matrix): Shape = copyWith(transform = t)
    fun withFill(f: Fill): Shape = copyWith(fill = f)
    fun withStroke(s: Stroke?): Shape = copyWith(stroke = s)
    fun withVisible(v: Boolean): Shape = copyWith(visible = v)
    fun withLocked(l: Boolean): Shape = copyWith(locked = l)
    fun withName(n: String): Shape = copyWith(name = n)
    fun withOpacity(o: Float): Shape = copyWith(opacity = o.coerceIn(0f, 1f))
    fun withBlendMode(m: BlendMode): Shape = copyWith(blendMode = m)
    fun withEffects(value: EffectStack): Shape = copyWith(effects = value)

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
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: EffectStack = EffectStack()
    ) : Shape() {
        override fun localBounds(): Rect = pathData.bounds()
        override fun localPath(): PathData = pathData
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode, effects: EffectStack
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
        val cornerStyle: CornerStyle = CornerStyle.ROUND,
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.Solid(Color.LIGHT_GRAY),
        override val stroke: Stroke? = Stroke(),
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: EffectStack = EffectStack()
    ) : Shape() {
        override fun localBounds(): Rect = rect
        override fun localPath(): PathData = corneredRectPath(rect, cornerRadius, cornerStyle)
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode, effects: EffectStack
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode, effects = effects)
    }

    /** Parametric ellipse. */
    data class EllipseShape(
        override val id: String = newId(),
        override val name: String = "Ellipse",
        val rect: Rect = Rect(0f, 0f, 100f, 100f),
        /** Start of the outer arc, in degrees; 0 is the right-most point. */
        val startAngleDegrees: Float = 0f,
        /** Clockwise sweep. 360 produces a complete ellipse/ring. */
        val sweepDegrees: Float = 360f,
        /** Inner radius as a fraction of the outer radius (0 = pie, 0.95 = thin ring). */
        val arcRatio: Float = 0f,
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.Solid(Color.LIGHT_GRAY),
        override val stroke: Stroke? = Stroke(),
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: EffectStack = EffectStack()
    ) : Shape() {
        override fun localBounds(): Rect = rect
        override fun localPath(): PathData = ellipseArcPath(
            rect = rect,
            startDegrees = startAngleDegrees,
            sweepDegrees = sweepDegrees,
            innerRatio = arcRatio
        )
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode, effects: EffectStack
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode, effects = effects)
    }

    /** Parametric regular polygon fitted into [rect]. */
    data class PolygonShape(
        override val id: String = newId(),
        override val name: String = "Polygon",
        val rect: Rect = Rect(0f, 0f, 100f, 100f),
        val sides: Int = 5,
        val rotationDegrees: Float = -90f,
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.Solid(Color.LIGHT_GRAY),
        override val stroke: Stroke? = Stroke(),
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: EffectStack = EffectStack()
    ) : Shape() {
        override fun localBounds(): Rect = rect
        override fun localPath(): PathData {
            val count = sides.coerceIn(3, 64)
            val rotation = Math.toRadians(rotationDegrees.toDouble()).toFloat()
            var path = PathData.EMPTY
            repeat(count) { index ->
                val angle = rotation + (2f * PI.toFloat() * index / count)
                val point = com.drawit.core.geometry.Point(
                    rect.centerX + cos(angle) * rect.width / 2f,
                    rect.centerY + sin(angle) * rect.height / 2f
                )
                path = if (index == 0) path.moveTo(point) else path.lineTo(point)
            }
            return path.close()
        }

        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode, effects: EffectStack
        ) = copy(
            id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode, effects = effects
        )
    }

    /** Group of child shapes. */
    data class GroupShape(
        override val id: String = newId(),
        override val name: String = "Group",
        val children: List<Shape> = emptyList(),
        /** Non-null for a PowerClip group; coordinates are local to this group. */
        val clipPath: PathData? = null,
        override val transform: Matrix = Matrix.IDENTITY,
        override val fill: Fill = Fill.None,
        override val stroke: Stroke? = null,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val effects: EffectStack = EffectStack()
    ) : Shape() {
        override fun localBounds(): Rect =
            clipPath?.bounds()
                ?: Rect.unionAll(children.filter { it.visible }.map { it.bounds() })
        override fun localPath(): PathData = clipPath ?: PathData.EMPTY
        override fun copyWith(
            id: String, name: String, transform: Matrix, fill: Fill,
            stroke: Stroke?, visible: Boolean, locked: Boolean,
            opacity: Float, blendMode: BlendMode, effects: EffectStack
        ) = copy(id = id, name = name, transform = transform, fill = fill,
            stroke = stroke, visible = visible, locked = locked,
            opacity = opacity, blendMode = blendMode, effects = effects)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()

        private fun corneredRectPath(
            rect: Rect,
            requestedRadius: Float,
            style: CornerStyle
        ): PathData {
            val radius = requestedRadius.coerceIn(0f, minOf(rect.width, rect.height) / 2f)
            if (radius <= 0.0001f) return PathData.rect(rect)

            val left = rect.left
            val top = rect.top
            val right = rect.right
            val bottom = rect.bottom
            return when (style) {
                CornerStyle.CHAMFER -> PathData.EMPTY
                    .moveTo(com.drawit.core.geometry.Point(left + radius, top))
                    .lineTo(com.drawit.core.geometry.Point(right - radius, top))
                    .lineTo(com.drawit.core.geometry.Point(right, top + radius))
                    .lineTo(com.drawit.core.geometry.Point(right, bottom - radius))
                    .lineTo(com.drawit.core.geometry.Point(right - radius, bottom))
                    .lineTo(com.drawit.core.geometry.Point(left + radius, bottom))
                    .lineTo(com.drawit.core.geometry.Point(left, bottom - radius))
                    .lineTo(com.drawit.core.geometry.Point(left, top + radius))
                    .close()

                CornerStyle.INSET -> PathData.EMPTY
                    .moveTo(com.drawit.core.geometry.Point(left + radius, top))
                    .lineTo(com.drawit.core.geometry.Point(right - radius, top))
                    .quadTo(
                        com.drawit.core.geometry.Point(right - radius, top + radius),
                        com.drawit.core.geometry.Point(right, top + radius)
                    )
                    .lineTo(com.drawit.core.geometry.Point(right, bottom - radius))
                    .quadTo(
                        com.drawit.core.geometry.Point(right - radius, bottom - radius),
                        com.drawit.core.geometry.Point(right - radius, bottom)
                    )
                    .lineTo(com.drawit.core.geometry.Point(left + radius, bottom))
                    .quadTo(
                        com.drawit.core.geometry.Point(left + radius, bottom - radius),
                        com.drawit.core.geometry.Point(left, bottom - radius)
                    )
                    .lineTo(com.drawit.core.geometry.Point(left, top + radius))
                    .quadTo(
                        com.drawit.core.geometry.Point(left + radius, top + radius),
                        com.drawit.core.geometry.Point(left + radius, top)
                    )
                    .close()

                CornerStyle.ROUND -> {
                    val control = radius * 0.55228475f
                    PathData.EMPTY
                        .moveTo(com.drawit.core.geometry.Point(left + radius, top))
                        .lineTo(com.drawit.core.geometry.Point(right - radius, top))
                        .cubicTo(
                            com.drawit.core.geometry.Point(right - radius + control, top),
                            com.drawit.core.geometry.Point(right, top + radius - control),
                            com.drawit.core.geometry.Point(right, top + radius)
                        )
                        .lineTo(com.drawit.core.geometry.Point(right, bottom - radius))
                        .cubicTo(
                            com.drawit.core.geometry.Point(right, bottom - radius + control),
                            com.drawit.core.geometry.Point(right - radius + control, bottom),
                            com.drawit.core.geometry.Point(right - radius, bottom)
                        )
                        .lineTo(com.drawit.core.geometry.Point(left + radius, bottom))
                        .cubicTo(
                            com.drawit.core.geometry.Point(left + radius - control, bottom),
                            com.drawit.core.geometry.Point(left, bottom - radius + control),
                            com.drawit.core.geometry.Point(left, bottom - radius)
                        )
                        .lineTo(com.drawit.core.geometry.Point(left, top + radius))
                        .cubicTo(
                            com.drawit.core.geometry.Point(left, top + radius - control),
                            com.drawit.core.geometry.Point(left + radius - control, top),
                            com.drawit.core.geometry.Point(left + radius, top)
                        )
                        .close()
                }
            }
        }

        private fun ellipseArcPath(
            rect: Rect,
            startDegrees: Float,
            sweepDegrees: Float,
            innerRatio: Float
        ): PathData {
            if (rect.isEmpty) return PathData.EMPTY
            val sweep = sweepDegrees.coerceIn(0.1f, 360f)
            val ratio = innerRatio.coerceIn(0f, 0.95f)
            if (sweep >= 359.999f && ratio <= 0.0001f) {
                return PathData.ellipse(rect)
            }

            val start = Math.toRadians(startDegrees.toDouble()).toFloat()
            val sweepRadians = Math.toRadians(sweep.toDouble()).toFloat()
            val cx = rect.centerX
            val cy = rect.centerY
            val rx = rect.width / 2f
            val ry = rect.height / 2f

            fun point(angle: Float, radiusRatio: Float = 1f): com.drawit.core.geometry.Point =
                com.drawit.core.geometry.Point(
                    cx + cos(angle) * rx * radiusRatio,
                    cy + sin(angle) * ry * radiusRatio
                )

            fun appendArc(
                source: PathData,
                arcStart: Float,
                arcSweep: Float,
                radiusRatio: Float
            ): PathData {
                val segments = kotlin.math.ceil(
                    kotlin.math.abs(arcSweep) / (PI.toFloat() / 2f)
                ).toInt().coerceAtLeast(1)
                val delta = arcSweep / segments
                var path = source
                var angle0 = arcStart
                repeat(segments) {
                    val angle1 = angle0 + delta
                    val k = 4f / 3f * tan(delta / 4f)
                    val p0 = point(angle0, radiusRatio)
                    val p3 = point(angle1, radiusRatio)
                    val cp1 = com.drawit.core.geometry.Point(
                        p0.x - sin(angle0) * rx * radiusRatio * k,
                        p0.y + cos(angle0) * ry * radiusRatio * k
                    )
                    val cp2 = com.drawit.core.geometry.Point(
                        p3.x + sin(angle1) * rx * radiusRatio * k,
                        p3.y - cos(angle1) * ry * radiusRatio * k
                    )
                    path = path.cubicTo(cp1, cp2, p3)
                    angle0 = angle1
                }
                return path
            }

            val outerStart = point(start)
            var result: PathData
            if (sweep >= 359.999f && ratio > 0.0001f) {
                result = PathData.EMPTY.moveTo(outerStart)
                result = appendArc(result, start, sweepRadians, 1f).close()
                val innerStart = point(start, ratio)
                result = result.moveTo(innerStart)
                result = appendArc(result, start, -sweepRadians, ratio).close()
                return result.copy(fillRule = PathData.FillRule.EVEN_ODD)
            }

            result = if (ratio <= 0.0001f) {
                PathData.EMPTY
                    .moveTo(com.drawit.core.geometry.Point(cx, cy))
                    .lineTo(outerStart)
            } else {
                PathData.EMPTY.moveTo(outerStart)
            }
            result = appendArc(result, start, sweepRadians, 1f)
            if (ratio <= 0.0001f) {
                return result.lineTo(com.drawit.core.geometry.Point(cx, cy)).close()
            }

            val innerEnd = point(start + sweepRadians, ratio)
            result = result.lineTo(innerEnd)
            result = appendArc(result, start + sweepRadians, -sweepRadians, ratio)
            return result.close()
        }
    }
}
