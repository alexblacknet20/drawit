package com.drawit.core.renderer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix as AndroidMatrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import com.drawit.core.color.Color
import com.drawit.core.document.Document
import com.drawit.core.document.Fill
import com.drawit.core.document.GradientStop
import com.drawit.core.document.ImageShape
import com.drawit.core.document.Shape
import com.drawit.core.document.ShadowEffect
import com.drawit.core.document.Stroke
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Matrix
import com.drawit.core.geometry.PathCommand
import com.drawit.core.geometry.PathData
import com.drawit.core.geometry.Point
import com.drawit.core.geometry.Rect
import com.drawit.file.ImageStore
import com.drawit.text.FontManager
import com.drawit.text.TextEngine
import java.util.Random

/**
 * Phase 1 renderer: Android Canvas (Skia). Now supports:
 *  - Fill: Solid, Gradient (linear/radial), Pattern (tile/fit/fill/stretch), None
 *  - Shapes: Path, Rect, Ellipse, Group, Text, Image
 *  - Opacity + blend mode per shape
 */
class SkiaRenderer(
    private val imageStore: ImageStore? = null,
    private val fontManager: FontManager? = null,
    private val showPageDecorations: Boolean = true
) : IRenderer {

    private var canvas: Canvas? = null
    private val qualityFlags =
        Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG
    private val fillPaint = Paint(qualityFlags).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(qualityFlags).apply { style = Paint.Style.STROKE }
    private val reusablePath = Path()
    private val textEngine = fontManager?.let { TextEngine(it) }

    override val capabilities = RenderCapabilities(
        supportsTiling = false, supportsLod = false, hardwareAccelerated = true
    )

    override fun setTarget(target: Any?) { canvas = target as? Canvas }

    override fun render(document: Document, viewMatrix: Matrix, dirtyRect: Rect?) {
        val c = canvas ?: return
        c.save()
        applyMatrix(c, viewMatrix)

        val page = document.activePage
        // Page white
        fillPaint.color = android.graphics.Color.WHITE
        fillPaint.style = Paint.Style.FILL
        fillPaint.xfermode = null
        c.drawRect(0f, 0f, page.width, page.height, fillPaint)
        if (showPageDecorations) {
            // Editor-only page border and bleed guides.
            strokePaint.color = android.graphics.Color.LTGRAY
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = 0.2f
            strokePaint.pathEffect = null
            c.drawRect(0f, 0f, page.width, page.height, strokePaint)
            if (!page.bleed.isZero) {
                strokePaint.color = android.graphics.Color.rgb(220, 80, 80)
                strokePaint.strokeWidth = 0.15f
                strokePaint.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
                val b = page.sizeWithBleed
                c.drawRect(b.left, b.top, b.right, b.bottom, strokePaint)
            }
            strokePaint.pathEffect = null
        }

        for (layer in page.layers) {
            if (!layer.visible) continue
            for (shape in layer.shapes) {
                if (shape.visible) renderShape(c, shape)
            }
        }
        c.restore()
    }

    override fun renderOverlay(draw: (Any) -> Unit) { canvas?.let { draw(it) } }
    override fun dispose() { canvas = null }

    // ================= Shape dispatch =================

    private fun renderShape(
        canvas: Canvas,
        shape: Shape,
        inheritedEdgeBlurRadius: Float = 0f
    ) {
        canvas.save()
        applyMatrix(canvas, shape.transform)

        val opacityScale = shape.opacity.coerceIn(0f, 1f)
        val xfermode = if (shape.blendMode != com.drawit.core.document.BlendMode.NORMAL)
            android.graphics.PorterDuffXfermode(shape.blendMode.toPorterDuff()) else null
        val effectPath = effectPath(shape)

        shape.effects.dropShadow?.let { shadow ->
            if (effectPath != null) {
                drawDropShadow(canvas, effectPath, shadow, opacityScale)
            }
        }

        val edgeBlurRadius = maxOf(
            inheritedEdgeBlurRadius,
            shape.effects.edgeBlurRadius.coerceAtLeast(0f)
        )
        val contentBlur = if (edgeBlurRadius > 0.001f) {
            BlurMaskFilter(
                (edgeBlurRadius * deviceScale(canvas)).coerceAtLeast(0.5f),
                BlurMaskFilter.Blur.NORMAL
            )
        } else {
            null
        }

        when (shape) {
            is Shape.GroupShape -> {
                val needsLayer = opacityScale < 1f || xfermode != null
                if (needsLayer) {
                    canvas.saveLayer(null, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        alpha = (255 * opacityScale).toInt()
                        this.xfermode = xfermode
                    })
                }
                val clip = shape.clipPath?.let { Path(toAndroidPath(it)) }
                if (clip != null) {
                    canvas.save()
                    canvas.clipPath(clip)
                }
                for (child in shape.children) {
                    if (child.visible) renderShape(canvas, child, edgeBlurRadius)
                }
                if (clip != null) {
                    canvas.restore()
                    shape.stroke?.let { stroke ->
                        applyStroke(strokePaint, stroke)
                        strokePaint.maskFilter = contentBlur
                        canvas.drawPath(clip, strokePaint)
                        strokePaint.maskFilter = null
                    }
                }
                if (needsLayer) canvas.restore()
            }
            is TextShape -> renderText(
                canvas,
                shape,
                opacityScale,
                xfermode,
                contentBlur
            )
            is ImageShape -> renderImage(
                canvas,
                shape,
                opacityScale,
                xfermode,
                contentBlur
            )
            else -> {
                val androidPath = toAndroidPath(shape.localPath())
                fillPaint.maskFilter = contentBlur
                strokePaint.maskFilter = contentBlur

                // Fill
                when (val fill = shape.fill) {
                    is Fill.Solid -> {
                        setupFillPaint(fillPaint, fill.color.toArgb(), opacityScale, xfermode)
                        fillPaint.style = Paint.Style.FILL
                        canvas.drawPath(androidPath, fillPaint)
                    }
                    is Fill.Gradient -> {
                        val shader = gradientShader(shape, fill)
                        fillPaint.shader = shader
                        fillPaint.style = Paint.Style.FILL
                        fillPaint.alpha = (255 * opacityScale).toInt()
                        fillPaint.xfermode = xfermode
                        canvas.drawPath(androidPath, fillPaint)
                        fillPaint.shader = null
                    }
                    is Fill.Pattern -> {
                        val bmp = imageStore?.get(fill.imageId)
                        if (bmp != null) {
                            val tileMode = if (fill.placement == Fill.Pattern.Placement.TILE) {
                                Shader.TileMode.REPEAT
                            } else {
                                Shader.TileMode.CLAMP
                            }
                            val tileShader = BitmapShader(bmp, tileMode, tileMode)
                            val m = patternMatrix(shape, fill, bmp)
                            tileShader.setLocalMatrix(m)
                            fillPaint.shader = tileShader
                            fillPaint.style = Paint.Style.FILL
                            fillPaint.alpha = (255 * opacityScale).toInt()
                            fillPaint.xfermode = xfermode
                            canvas.drawPath(androidPath, fillPaint)
                            fillPaint.shader = null
                        }
                    }
                    Fill.None -> {}
                }
                fillPaint.xfermode = null
                fillPaint.alpha = 255

                // Stroke
                shape.stroke?.let { s ->
                    applyStroke(strokePaint, s)
                    strokePaint.alpha = (strokePaint.alpha * opacityScale).toInt()
                    strokePaint.xfermode = xfermode
                    canvas.drawPath(androidPath, strokePaint)
                    strokePaint.xfermode = null
                    strokePaint.alpha = 255
                }
                fillPaint.maskFilter = null
                strokePaint.maskFilter = null
            }
        }

        shape.effects.innerShadow?.let { shadow ->
            if (effectPath != null) {
                drawInnerShadow(canvas, effectPath, shape.localBounds(), shadow, opacityScale)
            }
        }
        if (shape.effects.noiseAmount > 0.001f && effectPath != null) {
            drawNoise(
                canvas,
                effectPath,
                shape.localBounds(),
                shape.effects.noiseAmount,
                opacityScale,
                shape.id.hashCode().toLong()
            )
        }
        canvas.restore()
    }

    // ================= Text =================

    private fun renderText(
        canvas: Canvas,
        shape: TextShape,
        opacity: Float,
        xfermode: android.graphics.Xfermode?,
        blurMask: BlurMaskFilter?
    ) {
        val engine = textEngine ?: return
        val layout = engine.layout(shape)
        val paint = layout.paint
        val hasFill = when (val fill = shape.fill) {
            is Fill.Solid -> {
                paint.color = fill.color.toArgb()
                true
            }
            is Fill.Gradient -> {
                paint.shader = gradientShader(shape, fill)
                true
            }
            is Fill.Pattern -> {
                val bitmap = imageStore?.get(fill.imageId)
                if (bitmap != null) {
                    val tileMode = if (fill.placement == Fill.Pattern.Placement.TILE) {
                        Shader.TileMode.REPEAT
                    } else {
                        Shader.TileMode.CLAMP
                    }
                    paint.shader = BitmapShader(bitmap, tileMode, tileMode).apply {
                        setLocalMatrix(patternMatrix(shape, fill, bitmap))
                    }
                    true
                } else {
                    false
                }
            }
            Fill.None -> false
        }
        paint.alpha = (paint.alpha * opacity).toInt()
        paint.xfermode = xfermode
        paint.style = Paint.Style.FILL
        paint.maskFilter = blurMask

        if (hasFill) {
            for (line in layout.lines) {
                val x = engine.lineXOffset(shape, line, layout)
                canvas.drawText(line.text, x, line.baselineY, paint)
            }
        }
        paint.shader = null
        paint.xfermode = null
        paint.alpha = 255
        paint.maskFilter = null

        // Stroke text outlines
        shape.stroke?.let { s ->
            applyStroke(strokePaint, s)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.alpha = (strokePaint.alpha * opacity).toInt()
            strokePaint.xfermode = xfermode
            strokePaint.maskFilter = blurMask
            for (line in layout.lines) {
                val x = engine.lineXOffset(shape, line, layout)
                reusablePath.rewind()
                paint.getTextPath(line.text, 0, line.text.length, x, line.baselineY, reusablePath)
                canvas.drawPath(reusablePath, strokePaint)
            }
            strokePaint.xfermode = null
            strokePaint.alpha = 255
            strokePaint.maskFilter = null
        }
    }

    // ================= Image =================

    private fun renderImage(
        canvas: Canvas,
        shape: ImageShape,
        opacity: Float,
        xfermode: android.graphics.Xfermode?,
        blurMask: BlurMaskFilter?
    ) {
        val bmp = imageStore?.get(shape.imageId) ?: return
        fillPaint.alpha = (255 * opacity).toInt()
        fillPaint.xfermode = xfermode
        fillPaint.maskFilter = blurMask
        canvas.drawBitmap(bmp, null, shape.rect.toAndroidRect(), fillPaint)
        fillPaint.xfermode = null
        fillPaint.alpha = 255
        fillPaint.maskFilter = null
        shape.stroke?.let { stroke ->
            applyStroke(strokePaint, stroke)
            strokePaint.alpha = (strokePaint.alpha * opacity).toInt()
            strokePaint.xfermode = xfermode
            strokePaint.maskFilter = blurMask
            canvas.drawRect(shape.rect.toAndroidRect(), strokePaint)
            strokePaint.xfermode = null
            strokePaint.alpha = 255
            strokePaint.maskFilter = null
        }
    }

    // ================= Gradient / Pattern shaders =================

    private fun gradientShader(shape: Shape, fill: Fill.Gradient): Shader {
        val bounds = shape.localBounds()
        val stops = smoothGradientStops(fill)
        val colors = stops.map { it.color.toArgb() }.toIntArray()
        val positions = stops.map { it.position }.toFloatArray()

        return when (fill.type) {
            Fill.Gradient.Type.LINEAR -> {
                val angleRad = Math.toRadians(fill.angleDegrees.toDouble()).toFloat()
                val cos = kotlin.math.cos(angleRad)
                val sin = kotlin.math.sin(angleRad)
                val halfLength = kotlin.math.abs(cos) * bounds.width * 0.5f +
                    kotlin.math.abs(sin) * bounds.height * 0.5f
                val dx = cos * halfLength
                val dy = sin * halfLength
                LinearGradient(
                    bounds.centerX - dx, bounds.centerY - dy,
                    bounds.centerX + dx, bounds.centerY + dy,
                    colors, positions, Shader.TileMode.CLAMP
                )
            }
            Fill.Gradient.Type.RADIAL -> {
                val radius = maxOf(bounds.width, bounds.height) * 0.5f
                    .coerceAtLeast(0.001f)
                RadialGradient(
                    bounds.centerX, bounds.centerY, radius,
                    colors, positions, Shader.TileMode.CLAMP
                )
            }
        }
    }

    /**
     * Expands user stops into a small high-quality ramp. This avoids visible
     * banding in Android's PDF canvas while preserving every authored stop.
     */
    private fun smoothGradientStops(fill: Fill.Gradient): List<GradientStop> {
        val source = fill.sortedStops()
            .map { it.copy(position = it.position.coerceIn(0f, 1f)) }
        if (source.isEmpty()) {
            return listOf(
                GradientStop(0f, Color.TRANSPARENT),
                GradientStop(1f, Color.TRANSPARENT)
            )
        }
        if (source.size == 1) {
            return listOf(
                source.first().copy(position = 0f),
                source.first().copy(position = 1f)
            )
        }

        val result = mutableListOf<GradientStop>()
        source.zipWithNext().forEachIndexed { index, (start, end) ->
            if (index == 0) result += start
            val distance = (end.position - start.position).coerceAtLeast(0f)
            val subdivisions = kotlin.math.ceil(distance * 64f).toInt().coerceIn(1, 32)
            for (step in 1..subdivisions) {
                val t = step.toFloat() / subdivisions
                result += GradientStop(
                    position = start.position + distance * t,
                    color = interpolateColor(start.color, end.color, t)
                )
            }
        }
        return result
    }

    private fun interpolateColor(start: Color, end: Color, t: Float): Color {
        fun channel(a: Int, b: Int): Int =
            (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color(
            channel(start.r, end.r),
            channel(start.g, end.g),
            channel(start.b, end.b),
            channel(start.a, end.a)
        )
    }

    private fun effectPath(shape: Shape): Path? {
        val path = when (shape) {
            is TextShape -> {
                val engine = textEngine ?: return null
                val layout = engine.layout(shape)
                Path().also { result ->
                    for (line in layout.lines) {
                        val linePath = Path()
                        val x = engine.lineXOffset(shape, line, layout)
                        layout.paint.getTextPath(
                            line.text,
                            0,
                            line.text.length,
                            x,
                            line.baselineY,
                            linePath
                        )
                        result.addPath(linePath)
                    }
                }
            }
            is Shape.GroupShape -> {
                shape.clipPath?.let { Path(toAndroidPath(it)) } ?: Path().apply {
                    val bounds = shape.localBounds()
                    if (bounds.width > 0f && bounds.height > 0f) {
                        addRect(bounds.toAndroidRect(), Path.Direction.CW)
                    }
                }
            }
            else -> Path(toAndroidPath(shape.localPath()))
        }
        return path.takeUnless { it.isEmpty }
    }

    private fun drawDropShadow(
        canvas: Canvas,
        path: Path,
        shadow: ShadowEffect,
        shapeOpacity: Float
    ) {
        val alpha = (shadow.color.a * shadow.opacity.coerceIn(0f, 1f) * shapeOpacity)
            .toInt()
            .coerceIn(0, 255)
        if (alpha == 0) return
        val paint = Paint(qualityFlags).apply {
            style = Paint.Style.FILL
            color = shadow.color.toArgb()
            this.alpha = alpha
            if (shadow.blurRadius > 0.001f) {
                maskFilter = BlurMaskFilter(
                    (shadow.blurRadius * deviceScale(canvas)).coerceAtLeast(0.5f),
                    BlurMaskFilter.Blur.NORMAL
                )
            }
        }
        canvas.save()
        canvas.translate(shadow.offsetX, shadow.offsetY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawInnerShadow(
        canvas: Canvas,
        path: Path,
        bounds: Rect,
        shadow: ShadowEffect,
        shapeOpacity: Float
    ) {
        val alpha = (shadow.color.a * shadow.opacity.coerceIn(0f, 1f) * shapeOpacity)
            .toInt()
            .coerceIn(0, 255)
        if (alpha == 0) return
        val paint = Paint(qualityFlags).apply {
            style = Paint.Style.STROKE
            color = shadow.color.toArgb()
            this.alpha = alpha
            strokeWidth = maxOf(
                shadow.blurRadius * 2f,
                minOf(bounds.width, bounds.height) * 0.02f,
                0.25f
            )
            if (shadow.blurRadius > 0.001f) {
                maskFilter = BlurMaskFilter(
                    (shadow.blurRadius * deviceScale(canvas)).coerceAtLeast(0.5f),
                    BlurMaskFilter.Blur.NORMAL
                )
            }
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.translate(shadow.offsetX, shadow.offsetY)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    private fun drawNoise(
        canvas: Canvas,
        path: Path,
        bounds: Rect,
        amount: Float,
        shapeOpacity: Float,
        seed: Long
    ) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        val strength = amount.coerceIn(0f, 1f)
        val count = (bounds.width * bounds.height * strength * 0.6f)
            .toInt()
            .coerceIn(1, 1500)
        val random = Random(seed)
        val paint = Paint(qualityFlags).apply {
            style = Paint.Style.FILL
        }
        val dotRadius = 0.04f + strength * 0.12f
        val alpha = (90f * strength * shapeOpacity).toInt().coerceIn(1, 90)

        canvas.save()
        canvas.clipPath(path)
        repeat(count) {
            paint.color = if (random.nextBoolean()) {
                android.graphics.Color.WHITE
            } else {
                android.graphics.Color.BLACK
            }
            paint.alpha = alpha
            val x = bounds.left + random.nextFloat() * bounds.width
            val y = bounds.top + random.nextFloat() * bounds.height
            canvas.drawCircle(x, y, dotRadius, paint)
        }
        canvas.restore()
    }

    private fun patternMatrix(shape: Shape, fill: Fill.Pattern, bmp: Bitmap): AndroidMatrix {
        val bounds = shape.localBounds()
        val bw = bmp.width.toFloat() // native px; map 1:1 to mm for simplicity
        val bh = bmp.height.toFloat()
        val m = AndroidMatrix()
        var scaleX: Float
        var scaleY: Float
        var translateX = bounds.left
        var translateY = bounds.top
        when (fill.placement) {
            Fill.Pattern.Placement.TILE -> {
                val pxToMm = 25.4f / 96f
                scaleX = pxToMm * fill.tileScale.coerceAtLeast(0.01f)
                scaleY = scaleX
            }
            Fill.Pattern.Placement.FIT -> {
                val scale = minOf(bounds.width / bw, bounds.height / bh)
                scaleX = scale
                scaleY = scale
                translateX += (bounds.width - bw * scale) / 2f
                translateY += (bounds.height - bh * scale) / 2f
            }
            Fill.Pattern.Placement.FILL -> {
                val scale = maxOf(bounds.width / bw, bounds.height / bh)
                scaleX = scale
                scaleY = scale
                translateX += (bounds.width - bw * scale) / 2f
                translateY += (bounds.height - bh * scale) / 2f
            }
            Fill.Pattern.Placement.STRETCH -> {
                scaleX = bounds.width / bw
                scaleY = bounds.height / bh
            }
        }
        m.setScale(scaleX, scaleY)
        m.postTranslate(translateX, translateY)
        // Scale from image pixels → mm
        return m
    }

    // ================= Paint helpers =================

    private fun setupFillPaint(paint: Paint, color: Int, opacity: Float, xfermode: android.graphics.Xfermode?) {
        paint.shader = null
        paint.color = color
        paint.alpha = (android.graphics.Color.alpha(color) * opacity).toInt()
        paint.xfermode = xfermode
    }

    private fun applyStroke(paint: Paint, stroke: Stroke) {
        paint.style = Paint.Style.STROKE
        paint.color = stroke.color.toArgb()
        paint.alpha = stroke.color.a
        paint.strokeWidth = stroke.width
        paint.strokeCap = when (stroke.cap) {
            Stroke.Cap.BUTT -> Paint.Cap.BUTT
            Stroke.Cap.ROUND -> Paint.Cap.ROUND
            Stroke.Cap.SQUARE -> Paint.Cap.SQUARE
        }
        paint.strokeJoin = when (stroke.join) {
            Stroke.Join.MITER -> Paint.Join.MITER
            Stroke.Join.ROUND -> Paint.Join.ROUND
            Stroke.Join.BEVEL -> Paint.Join.BEVEL
        }
        paint.strokeMiter = stroke.miterLimit
        paint.pathEffect = if (stroke.dashPattern.isNotEmpty())
            DashPathEffect(stroke.dashPattern.toFloatArray(), 0f) else null
    }

    private fun toAndroidPath(pathData: PathData): Path {
        reusablePath.rewind()
        reusablePath.fillType = when (pathData.fillRule) {
            PathData.FillRule.EVEN_ODD -> Path.FillType.EVEN_ODD
            PathData.FillRule.NON_ZERO -> Path.FillType.WINDING
        }
        for (cmd in pathData.commands) {
            when (cmd) {
                is PathCommand.MoveTo -> reusablePath.moveTo(cmd.point.x, cmd.point.y)
                is PathCommand.LineTo -> reusablePath.lineTo(cmd.point.x, cmd.point.y)
                is PathCommand.CubicTo -> reusablePath.cubicTo(
                    cmd.cp1.x, cmd.cp1.y, cmd.cp2.x, cmd.cp2.y, cmd.end.x, cmd.end.y)
                is PathCommand.QuadTo -> reusablePath.quadTo(cmd.cp.x, cmd.cp.y, cmd.end.x, cmd.end.y)
                PathCommand.Close -> reusablePath.close()
            }
        }
        return reusablePath
    }

    private fun applyMatrix(canvas: Canvas, matrix: Matrix) {
        if (matrix.isIdentity) return
        val m = AndroidMatrix().apply {
            setValues(floatArrayOf(matrix.a, matrix.c, matrix.e, matrix.b, matrix.d, matrix.f, 0f, 0f, 1f))
        }
        canvas.concat(m)
    }

    @Suppress("DEPRECATION")
    private fun deviceScale(canvas: Canvas): Float {
        val matrix = AndroidMatrix()
        canvas.getMatrix(matrix)
        val values = FloatArray(9)
        matrix.getValues(values)
        return kotlin.math.hypot(
            values[AndroidMatrix.MSCALE_X],
            values[AndroidMatrix.MSKEW_Y]
        ).coerceAtLeast(0.001f)
    }

    private fun Rect.toAndroidRect() = android.graphics.RectF(left, top, right, bottom)
}
