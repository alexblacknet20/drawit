package com.drawit.file

import android.graphics.Bitmap
import android.graphics.Canvas
import com.drawit.core.document.Document
import com.drawit.core.document.Fill
import com.drawit.core.document.ImageShape
import com.drawit.core.document.Shape
import com.drawit.core.document.TextShape
import com.drawit.core.geometry.Matrix
import com.drawit.core.renderer.SkiaRenderer
import com.drawit.text.FontManager
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .drawit v2 ZIP container:
 * document.json, manifest.json, preview.png, plus image and imported-font entries.
 */
object DrawItFile {

    const val EXTENSION = "drawit"
    const val MIME_TYPE = "application/x-drawit"
    private const val MAX_RESOURCE_BYTES = 64 * 1024 * 1024

    fun write(
        doc: Document,
        out: OutputStream,
        imageStore: ImageStore? = null,
        fontManager: FontManager? = null
    ) {
        ZipOutputStream(out.buffered()).use { zip ->
            writeEntry(
                zip,
                "manifest.json",
                JSONObject()
                    .put("format", "drawit")
                    .put("formatVersion", DocumentSerializer.FORMAT_VERSION)
                    .put("generator", "DrawIt 0.1.0-alpha")
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            )
            writeEntry(
                zip,
                "document.json",
                DocumentSerializer.toJson(doc).toString(2).toByteArray(Charsets.UTF_8)
            )

            val shapes = allShapes(doc)
            val imageIds = shapes.flatMap { shape ->
                buildList {
                    if (shape is ImageShape && shape.imageId.isNotBlank()) add(shape.imageId)
                    val pattern = shape.fill as? Fill.Pattern
                    if (pattern != null && pattern.imageId.isNotBlank()) add(pattern.imageId)
                }
            }.toSortedSet()
            imageIds.forEach { imageId ->
                val bitmap = imageStore?.get(imageId) ?: return@forEach
                zip.putNextEntry(ZipEntry("images/$imageId.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
            }

            val fontKeys = shapes.filterIsInstance<TextShape>()
                .map { it.fontFamily }
                .filter { it.startsWith("imported:") }
                .toSortedSet()
            fontKeys.forEach { key ->
                val bytes = fontManager?.fontBytesForEmbedding(key) ?: return@forEach
                val fileName = key.removePrefix("imported:")
                if (isSafeFileName(fileName)) writeEntry(zip, "fonts/$fileName", bytes)
            }

            runCatching { renderPreview(doc, imageStore, fontManager) }.getOrNull()?.let { bitmap ->
                zip.putNextEntry(ZipEntry("preview.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, zip)
                zip.closeEntry()
                bitmap.recycle()
            }
        }
    }

    fun read(
        input: InputStream,
        imageStore: ImageStore? = null,
        fontManager: FontManager? = null
    ): Document {
        var documentJson: String? = null
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    when {
                        entry.name == "document.json" -> {
                            documentJson = readEntryBytes(zip).toString(Charsets.UTF_8)
                        }
                        entry.name.startsWith("images/") && entry.name.endsWith(".png") -> {
                            val imageId = entry.name.removePrefix("images/").removeSuffix(".png")
                            if (imageId.matches(Regex("[A-Za-z0-9_-]+"))) {
                                imageStore?.readFrom(imageId, readEntryBytes(zip))
                            }
                        }
                        entry.name.startsWith("fonts/") -> {
                            val fileName = entry.name.removePrefix("fonts/")
                            if (isSafeFileName(fileName)) {
                                fontManager?.restoreEmbeddedFont(fileName, readEntryBytes(zip))
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        requireNotNull(documentJson) { "Not a valid .drawit file (no document.json)" }
        return DocumentSerializer.fromJson(JSONObject(documentJson))
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readEntryBytes(zip: ZipInputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_RESOURCE_BYTES) { "Resource in .drawit file is too large" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun allShapes(doc: Document): List<Shape> = buildList {
        fun visit(shape: Shape) {
            add(shape)
            if (shape is Shape.GroupShape) shape.children.forEach(::visit)
        }
        doc.pages.forEach { page ->
            page.layers.forEach { layer -> layer.shapes.forEach(::visit) }
        }
    }

    private fun isSafeFileName(value: String): Boolean =
        value.isNotBlank() &&
            '/' !in value &&
            '\\' !in value &&
            value != "." &&
            value != ".."

    private fun renderPreview(
        doc: Document,
        imageStore: ImageStore?,
        fontManager: FontManager?
    ): Bitmap {
        val page = doc.activePage
        val targetWidth = 512
        val scale = targetWidth / page.width
        val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val renderer = SkiaRenderer(imageStore, fontManager)
        renderer.setTarget(canvas)
        renderer.render(doc, Matrix.scale(scale, scale))
        renderer.dispose()
        return bitmap
    }

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
