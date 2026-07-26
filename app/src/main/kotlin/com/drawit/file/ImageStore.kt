package com.drawit.file

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content-addressed bitmap store. Images are:
 *  - decoded and downscaled on import (max 2048px on long edge)
 *  - stored as PNG files named by SHA-256 hash in <files>/images/
 *  - embedded into .drawit ZIPs under images/<hash>.png
 *  - memory-cached via LruCache
 */
class ImageStore(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "images").apply { mkdirs() }

    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) { // 32 MB
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** In-memory registry of images used by the current document session. */
    private val sessionIds = mutableSetOf<String>()

    /** Import from a stream (gallery/SAF). Downscales, hashes, stores. Returns imageId. */
    fun importImage(input: InputStream): String {
        val bytes = input.readBytes()
        val hash = sha256(bytes)

        if (!File(dir, "$hash.png").exists()) {
            val bitmap = decodeDownscaled(bytes, maxEdge = 2048)
                ?: throw IllegalArgumentException("Unsupported image data")
            File(dir, "$hash.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, it)
            }
            cache.put(hash, bitmap)
        }
        sessionIds.add(hash)
        return hash
    }

    /** Get bitmap by id (loads from disk if not cached). */
    fun get(imageId: String): Bitmap? {
        cache.get(imageId)?.let { return it }
        val file = File(dir, "$imageId.png")
        if (!file.exists()) return null
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        cache.put(imageId, bmp)
        return bmp
    }

    /** Pixel size of an image (decodes bounds only). */
    fun sizeOf(imageId: String): Pair<Int, Int>? {
        get(imageId)?.let { return it.width to it.height }
        return null
    }

    /** Register an id as used by the current session (e.g., after loading a file). */
    fun register(imageId: String) { sessionIds.add(imageId) }

    /** All ids used this session (for save embedding). */
    fun sessionImageIds(): Set<String> = sessionIds.toSet()

    /** Write an image to a stream as PNG (for .drawit embedding). */
    fun writeTo(imageId: String, out: java.io.OutputStream): Boolean {
        val bmp = get(imageId) ?: return false
        bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
        return true
    }

    /** Read an image from a .drawit ZIP entry into the store. */
    fun readFrom(imageId: String, bytes: ByteArray) {
        if (File(dir, "$imageId.png").exists()) { sessionIds.add(imageId); return }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
        File(dir, "$imageId.png").outputStream().use {
            bmp.compress(Bitmap.CompressFormat.PNG, 90, it)
        }
        cache.put(imageId, bmp)
        sessionIds.add(imageId)
    }

    private fun decodeDownscaled(bytes: ByteArray, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sample * 2) > maxEdge) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }.substring(0, 16)
}
