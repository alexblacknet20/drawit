package com.drawit.file

import android.graphics.Bitmap
import android.graphics.Canvas
import com.drawit.core.document.Document
import com.drawit.core.geometry.Matrix
import com.drawit.core.renderer.SkiaRenderer
import com.drawit.text.FontManager
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Raster export of the active page at the document DPI. */
object RasterExporter {
    enum class Format(
        val displayName: String,
        val compressFormat: Bitmap.CompressFormat
    ) {
        PNG("PNG", Bitmap.CompressFormat.PNG),
        JPEG("JPG", Bitmap.CompressFormat.JPEG)
    }

    data class Result(
        val widthPx: Int,
        val heightPx: Int,
        val effectiveDpi: Float
    )

    private const val MM_PER_INCH = 25.4f
    private const val MAX_PIXELS = 48_000_000L
    private const val MAX_EDGE = 12_000

    fun write(
        document: Document,
        output: OutputStream,
        imageStore: ImageStore,
        fontManager: FontManager,
        format: Format,
        dpi: Float = document.dpi,
        jpegQuality: Int = 95
    ): Result {
        val page = document.activePage
        val requestedDpi = dpi.coerceIn(36f, 1200f)
        var scale = requestedDpi / MM_PER_INCH
        var width = (page.width * scale).roundToInt().coerceAtLeast(1)
        var height = (page.height * scale).roundToInt().coerceAtLeast(1)

        val pixelScale = if (width.toLong() * height > MAX_PIXELS) {
            sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height)).toFloat()
        } else {
            1f
        }
        val edgeScale = minOf(
            1f,
            MAX_EDGE.toFloat() / width,
            MAX_EDGE.toFloat() / height
        )
        val safetyScale = minOf(pixelScale, edgeScale)
        if (safetyScale < 1f) {
            scale *= safetyScale
            width = (page.width * scale).roundToInt().coerceAtLeast(1)
            height = (page.height * scale).roundToInt().coerceAtLeast(1)
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val renderer = SkiaRenderer(
                imageStore = imageStore,
                fontManager = fontManager,
                showPageDecorations = false
            )
            try {
                renderer.setTarget(canvas)
                renderer.render(document, Matrix.scale(scale, scale))
            } finally {
                renderer.dispose()
            }
            check(
                bitmap.compress(
                    format.compressFormat,
                    if (format == Format.JPEG) jpegQuality.coerceIn(1, 100) else 100,
                    output
                )
            ) { "Android could not encode ${format.displayName}" }
            output.flush()
        } finally {
            bitmap.recycle()
        }
        return Result(
            widthPx = width,
            heightPx = height,
            effectiveDpi = scale * MM_PER_INCH
        )
    }
}
