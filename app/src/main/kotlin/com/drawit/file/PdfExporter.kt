package com.drawit.file

import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.drawit.core.document.Document
import com.drawit.core.document.Page
import com.drawit.core.geometry.Matrix
import com.drawit.core.renderer.SkiaRenderer
import com.drawit.text.FontManager
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * Exact-size vector PDF export with page bleed and crop marks.
 *
 * Android's PdfDocument writes device-independent vector drawing commands and
 * embeds text through the platform PDF backend. Output is RGB PDF, not PDF/X.
 */
object PdfExporter {
    private const val POINTS_PER_MM = 72f / 25.4f
    private const val MARK_MARGIN_MM = 8f
    private const val MARK_LENGTH_MM = 5f
    private const val MARK_GAP_MM = 1.5f

    fun write(
        document: Document,
        output: OutputStream,
        imageStore: ImageStore,
        fontManager: FontManager,
        pageIndices: List<Int> = document.pages.indices.toList()
    ) {
        val pdf = PdfDocument()
        val renderer = SkiaRenderer(
            imageStore = imageStore,
            fontManager = fontManager,
            showPageDecorations = false
        )
        try {
            pageIndices
                .distinct()
                .filter { it in document.pages.indices }
                .forEachIndexed { outputIndex, documentIndex ->
                val page = document.pages[documentIndex]
                val mediaWidthMm = page.width + page.bleed.left + page.bleed.right +
                    MARK_MARGIN_MM * 2f
                val mediaHeightMm = page.height + page.bleed.top + page.bleed.bottom +
                    MARK_MARGIN_MM * 2f
                val pageInfo = PdfDocument.PageInfo.Builder(
                    (mediaWidthMm * POINTS_PER_MM).roundToInt().coerceAtLeast(1),
                    (mediaHeightMm * POINTS_PER_MM).roundToInt().coerceAtLeast(1),
                    outputIndex + 1
                ).create()
                val pdfPage = pdf.startPage(pageInfo)
                val canvas = pdfPage.canvas
                canvas.drawColor(android.graphics.Color.WHITE)

                canvas.save()
                canvas.translate(
                    (MARK_MARGIN_MM + page.bleed.left) * POINTS_PER_MM,
                    (MARK_MARGIN_MM + page.bleed.top) * POINTS_PER_MM
                )
                canvas.scale(POINTS_PER_MM, POINTS_PER_MM)
                renderer.setTarget(canvas)
                renderer.render(
                    document.copy(activePageIndex = documentIndex),
                    Matrix.IDENTITY
                )
                canvas.restore()

                drawCropMarks(canvas, page)
                pdf.finishPage(pdfPage)
            }
            pdf.writeTo(output)
        } finally {
            renderer.dispose()
            pdf.close()
        }
    }

    private fun drawCropMarks(canvas: android.graphics.Canvas, page: Page) {
        val trimLeft = (MARK_MARGIN_MM + page.bleed.left) * POINTS_PER_MM
        val trimTop = (MARK_MARGIN_MM + page.bleed.top) * POINTS_PER_MM
        val trimRight = trimLeft + page.width * POINTS_PER_MM
        val trimBottom = trimTop + page.height * POINTS_PER_MM
        val length = MARK_LENGTH_MM * POINTS_PER_MM
        val gap = MARK_GAP_MM * POINTS_PER_MM
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.25f
        }

        // Horizontal marks aligned with the top/bottom trim edges.
        listOf(trimTop, trimBottom).forEach { y ->
            canvas.drawLine(trimLeft - gap - length, y, trimLeft - gap, y, paint)
            canvas.drawLine(trimRight + gap, y, trimRight + gap + length, y, paint)
        }
        // Vertical marks aligned with the left/right trim edges.
        listOf(trimLeft, trimRight).forEach { x ->
            canvas.drawLine(x, trimTop - gap - length, x, trimTop - gap, paint)
            canvas.drawLine(x, trimBottom + gap, x, trimBottom + gap + length, paint)
        }
    }
}
