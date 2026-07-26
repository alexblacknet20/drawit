package com.drawit.file

import android.graphics.Bitmap
import android.graphics.Canvas
import com.drawit.core.document.Document
import com.drawit.core.geometry.Matrix
import com.drawit.core.renderer.SkiaRenderer
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .drawit file = ZIP container:
 *   document.json   — full document (DocumentSerializer)
 *   preview.png     — 512px preview of active page
 *   manifest.json   — format metadata
 */
object DrawItFile {

    const val EXTENSION = "drawit"
    const val MIME_TYPE = "application/x-drawit"

    fun write(doc: Document, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            // Manifest
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(JSONObject()
                .put("format", "drawit")
                .put("formatVersion", DocumentSerializer.FORMAT_VERSION)
                .put("generator", "DrawIt 0.1.0-alpha")
                .toString().toByteArray())
            zip.closeEntry()

            // Document
            zip.putNextEntry(ZipEntry("document.json"))
            zip.write(DocumentSerializer.toJson(doc).toString(2).toByteArray())
            zip.closeEntry()

            // Preview (best effort — never fail save because of preview)
            runCatching { renderPreview(doc) }.getOrNull()?.let { bmp ->
                zip.putNextEntry(ZipEntry("preview.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, 90, zip)
                zip.closeEntry()
            }
        }
    }

    fun read(input: InputStream): Document {
        var documentJson: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "document.json") {
                    documentJson = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        requireNotNull(documentJson) { "Not a valid .drawit file (no document.json)" }
        return DocumentSerializer.fromJson(JSONObject(documentJson))
    }

    /** Render the active page to a small preview bitmap. */
    private fun renderPreview(doc: Document): Bitmap {
        val page = doc.activePage
        val targetW = 512
        val scale = targetW / page.width
        val targetH = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val renderer = SkiaRenderer()
        renderer.setTarget(canvas)
        renderer.render(doc, Matrix.scale(scale, scale))
        renderer.dispose()
        return bitmap
    }

    /** Detect file type from name or MIME. */
    fun detectType(fileName: String?): Type {
        val lower = fileName?.lowercase() ?: return Type.UNKNOWN
        return when {
            lower.endsWith(".drawit") -> Type.DRAWIT
            lower.endsWith(".svg") -> Type.SVG
            else -> Type.UNKNOWN
        }
    }

    enum class Type { DRAWIT, SVG, UNKNOWN }
}
