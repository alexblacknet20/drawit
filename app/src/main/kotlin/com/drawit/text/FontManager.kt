package com.drawit.text

import android.content.Context
import android.graphics.Typeface
import java.io.File
import java.io.InputStream

/**
 * Font sources, in priority order:
 *  1. Bundled (assets/fonts/) — always available
 *  2. Imported (filesDir/fonts/) — user TTF/OTF, embedded into .drawit
 *  3. System — via Typeface family-name fallback
 *
 * Keys are stable strings stored in TextShape.fontFamily and in .drawit files:
 *  - "bundled:inter" etc.
 *  - "imported:<filename>"
 *  - "system:<familyName>" (also plain "sans-serif", "serif", "monospace")
 */
class FontManager(private val context: Context) {

    data class FontInfo(val key: String, val displayName: String, val source: Source)
    enum class Source { BUNDLED, IMPORTED, SYSTEM }

    private val typefaceCache = mutableMapOf<String, Typeface>()

    private val importedDir: File get() = File(context.filesDir, "fonts").apply { mkdirs() }

    // ---------- Catalog ----------

    fun availableFonts(): List<FontInfo> {
        val bundled = listBundled().map {
            FontInfo("bundled:$it", it.displayNameFromKey(), Source.BUNDLED)
        }
        val imported = listImported().map {
            FontInfo("imported:${it.name}", it.nameWithoutExtension.displayNameFromKey(), Source.IMPORTED)
        }
        val system = listOf(
            FontInfo("sans-serif", "Sans Serif", Source.SYSTEM),
            FontInfo("serif", "Serif", Source.SYSTEM),
            FontInfo("monospace", "Monospace", Source.SYSTEM),
            FontInfo("cursive", "Cursive", Source.SYSTEM)
        )
        return bundled + imported + system
    }

    private fun listBundled(): List<String> =
        context.assets.list("fonts")?.map { it.removeSuffix(".ttf").removeSuffix(".otf") }
            ?: emptyList()

    private fun listImported(): List<File> =
        importedDir.listFiles { f -> f.extension.lowercase() in listOf("ttf", "otf") }
            ?.toList() ?: emptyList()

    // ---------- Resolution ----------

    fun typefaceFor(key: String): Typeface {
        typefaceCache[key]?.let { return it }
        val tf = resolve(key)
        typefaceCache[key] = tf
        return tf
    }

    private fun resolve(key: String): Typeface = when {
        key.startsWith("bundled:") -> runCatching {
            Typeface.createFromAsset(context.assets, "fonts/${key.removePrefix("bundled:")}.ttf")
        }.getOrElse {
            runCatching {
                Typeface.createFromAsset(context.assets, "fonts/${key.removePrefix("bundled:")}.otf")
            }.getOrDefault(Typeface.DEFAULT)
        }
        key.startsWith("imported:") -> {
            val file = File(importedDir, key.removePrefix("imported:"))
            if (file.exists()) runCatching { Typeface.createFromFile(file) }
                .getOrDefault(Typeface.DEFAULT)
            else Typeface.DEFAULT
        }
        key.startsWith("system:") -> Typeface.create(key.removePrefix("system:"), Typeface.NORMAL)
        else -> Typeface.create(key, Typeface.NORMAL) // "sans-serif" etc.
    }

    // ---------- Import / embed ----------

    /** Import a TTF/OTF from a stream. Returns the font key. */
    fun importFont(input: InputStream, fileName: String): String {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(importedDir, safe)
        out.outputStream().use { input.copyTo(it) }
        // Validate it loads
        runCatching { Typeface.createFromFile(out) }
            .onFailure { out.delete(); throw IllegalArgumentException("Not a valid font file") }
        typefaceCache.remove("imported:$safe")
        return "imported:$safe"
    }

    /** Read font bytes for embedding into .drawit (only imported fonts are embedded). */
    fun fontBytesForEmbedding(key: String): ByteArray? {
        if (!key.startsWith("imported:")) return null
        val file = File(importedDir, key.removePrefix("imported:"))
        return if (file.exists()) file.readBytes() else null
    }

    /** Restore an embedded font from a .drawit file. Returns the font key. */
    fun restoreEmbeddedFont(fileName: String, bytes: ByteArray): String {
        val safe = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(importedDir, safe)
        if (!out.exists()) out.writeBytes(bytes)
        typefaceCache.remove("imported:$safe")
        return "imported:$safe"
    }

    private fun String.displayNameFromKey(): String =
        replace(Regex("[-_]"), " ")
            .split(" ")
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
}
