package com.drawit.core.renderer

import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import com.drawit.core.document.ImageShape
import com.drawit.core.document.Shape
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

/**
 * Phase 1 renderer: Android Canvas (Skia). Now supports:
 *  - Fill: Solid, Gradient (linear/radial), Pattern (tile/fit/fill/stretch), None
 *  - Shapes: Path, Rect, Ellipse, Group, Text, Image
 *  - Opacity + blend mode per shape
 */
class SkiaRenderer(
    private val imageStore: ImageStore? = null,
    private val fontManager: FontManager? = null
) : IRenderer {

    private var canvas: Canvas? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val reusablePath = Path()
    private val textEngine = fontManager?.let { TextEngine(it) }

    private val imagesShaderMatrix = AndroidMatrix()

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
        // Page border
        strokePaint.color = android.graphics.Color.LTGRAY
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = 0.2f
        strokePaint.pathEffect = null
        c.drawRect(0f, 0f, page.width, page.height, strokePaint)
        // Bleed
        if (!page.bleed.isZero) {
            strokePaint.color = android.graphics.Color.rgb(220, 80, 80)
            strokePaint.strokeWidth = 0.15f
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
            val b = page.sizeWithBleed
            c.drawRect(b.left, b.top, b.right, b.bottom, strokePaint)
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

    private fun renderShape(canvas: Canvas, shape: Shape) {
        canvas.save()
        applyMatrix(canvas, shape.transform)

        val opacityScale = shape.opacity.coerceIn(0f, 1f)
        val xfermode = if (shape.blendMode != com.drawit.core.document.BlendMode.NORMAL)
            android.graphics.PorterDuffXfermode(shape.blendMode.toPorterDuff()) else null

        when (shape) {
            is Shape.GroupShape -> {
                for (child in shape.children) if (child.visible) renderShape(canvas, child)
            }
            is TextShape -> renderText(canvas, shape, opacityScale, xfermode)
            is ImageShape -> renderImage(canvas, shape, opacityScale, xfermode)
            else -> {
                val androidPath = toAndroidPath(shape.localPath())

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
                            val tileShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
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
            }
        }

        canvas.restore()
    }

    // ================= Text =================

    private fun renderText(canvas: Canvas, shape: TextShape, opacity: Float, xfermode: android.graphics.Xfermode?) {
        val engine = textEngine ?: return
        val layout = engine.layout(shape)
        val paint = layout.paint
        paint.color = (shape.fill as? Fill.Solid)?.color?.toArgb() ?: android.graphics.Color.BLACK
        paint.alpha = (paint.alpha * opacity).toInt()
        paint.xfermode = xfermode
        paint.style = Paint.Style.FILL

        for (line in layout.lines) {
            val x = engine.lineXOffset(shape, line, layout)
            canvas.drawText(line.text, x, line.baselineY, paint)
        }
        paint.xfermode = null
        paint.alpha = 255

        // Stroke text outlines
        shape.stroke?.let { s ->
            applyStroke(strokePaint, s)
            strokePaint.style = Paint.Style.STROKE
            strokePaint.alpha = (strokePaint.alpha * opacity).toInt()
            strokePaint.xfermode = xfermode
            for (line in layout.lines) {
                val x = engine.lineXOffset(shape, line, layout)
                paint.getTextPath(line.text, 0, line.text.length, x, line.baselineY, reusablePath)
                canvas.drawPath(reusablePath, strokePaint)
            }
            strokePaint.xfermode = null
            strokePaint.alpha = 255
        }
    }

    // ================= Image =================

    private fun renderImage(canvas: Canvas, shape: ImageShape, opacity: Float, xfermode: android.graphics.Xfermode?) {
        val bmp = imageStore?.get(shape.imageId) ?: return
        fillPaint.alpha = (255 * opacity).toInt()
        fillPaint.xfermode = xfermode
        canvas.drawBitmap(bmp, null, shape.rect.toAndroidRect(), fillPaint)
        fillPaint.xfermode = null
        fillPaint.alpha = 255
    }

    // ================= Gradient / Pattern shaders =================

    private fun gradientShader(shape: Shape, fill: Fill.Gradient): Shader {
        val bounds = shape.localBounds()
        val stops = fill.sortedStops()
        val colors = stops.map { it.color.toArgb() }.toIntArray()
        val positions = stops.map { it.position }.toFloatArray()

        return when (fill.type) {
            Fill.Gradient.Type.LINEAR -> {
                val angleRad = Math.toRadians(fill.angleDegrees.toDouble()).toFloat()
                val dx = kotlin.math.cos(angleRad) * bounds.width * 0.5f
                val dy = kotlin.math.sin(angleRad) * bounds.width * 0.5f
                LinearGradient(
                    bounds.centerX - dx, bounds.centerY - dy,
                    bounds.centerX + dx, bounds.centerY + dy,
                    colors, positions, Shader.TileMode.CLAMP
                )
            }
            Fill.Gradient.Type.RADIAL -> {
                val radius = bounds.width.coerceAtLeast(bounds.height) * 0.6f
                RadialGradient(
                    bounds.centerX, bounds.centerY, radius,
                    colors, positions, Shader.TileMode.CLAMP
                )
            }
        }
    }

    private fun patternMatrix(shape: Shape, fill: Fill.Pattern, bmp: Bitmap): AndroidMatrix {
        val bounds = shape.localBounds()
        val bw = bmp.width.toFloat() // native px; map 1:1 to mm for simplicity
        val bh = bmp.height.toFloat()
        val m = AndroidMatrix()
        when (fill.placement) {
            Fill.Pattern.Placement.TILE -> {
                m.reset()
                m.postScale(fill.tileScale, fill.tileScale)
            }
            Fill.Pattern.Placement.FIT -> {
                val scale = minOf(bounds.width / bw, bounds.height / bh)
                m.postScale(scale, scale)
            }
            Fill.Pattern.Placement.FILL -> {
                val scale = maxOf(bounds.width / bw, bounds.height / bh)
                m.postScale(scale, scale)
            }
            Fill.Pattern.Placement.STRETCH -> {
                m.setScale(bounds.width / bw, bounds.height / bh)
            }
        }
        m.postTranslate(bounds.left, bounds.top)
        // Scale from image pixels → mm
        val pxToMm = 25.4f / 96f
        m.preScale(pxToMm, pxToMm)
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
        paint.alpha = 255
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

    private fun Rect.toAndroidRect() = android.graphics.RectF(left, top, right, bottom)
}
