package com.drawit.core.color

/**
 * Color in document. Stored as 8-bit RGBA; CMYK conversion happens at export.
 */
data class Color(
    val r: Int, // 0-255
    val g: Int,
    val b: Int,
    val a: Int = 255
) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255 && a in 0..255) {
            "Color components must be 0-255, got r=$r g=$g b=$b a=$a"
        }
    }

    fun toArgb(): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Convert to CMYK (naive conversion for preview; export uses ICC profiles). */
    fun toCmyk(): CmykColor {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val k = 1f - maxOf(rf, gf, bf)
        if (k >= 1f) return CmykColor(0f, 0f, 0f, 1f)
        val c = (1f - rf - k) / (1f - k)
        val m = (1f - gf - k) / (1f - k)
        val y = (1f - bf - k) / (1f - k)
        return CmykColor(c, m, y, k)
    }

    fun withAlpha(alpha: Int) = copy(a = alpha)
    fun darker(factor: Float = 0.8f) = Color(
        (r * factor).toInt().coerceIn(0, 255),
        (g * factor).toInt().coerceIn(0, 255),
        (b * factor).toInt().coerceIn(0, 255),
        a
    )
    fun lighter(factor: Float = 1.25f) = Color(
        (r * factor).toInt().coerceIn(0, 255),
        (g * factor).toInt().coerceIn(0, 255),
        (b * factor).toInt().coerceIn(0, 255),
        a
    )

    fun toHexString(includeAlpha: Boolean = false): String {
        return if (includeAlpha) {
            String.format("#%02X%02X%02X%02X", a, r, g, b)
        } else {
            String.format("#%02X%02X%02X", r, g, b)
        }
    }

    companion object {
        val BLACK = Color(0, 0, 0)
        val WHITE = Color(255, 255, 255)
        val RED = Color(255, 0, 0)
        val GREEN = Color(0, 255, 0)
        val BLUE = Color(0, 0, 255)
        val TRANSPARENT = Color(0, 0, 0, 0)
        val GRAY = Color(128, 128, 128)
        val LIGHT_GRAY = Color(211, 211, 211)

        fun fromArgb(argb: Int) = Color(
            r = (argb shr 16) and 0xFF,
            g = (argb shr 8) and 0xFF,
            b = argb and 0xFF,
            a = (argb shr 24) and 0xFF
        )

        fun fromHex(hex: String): Color {
            val cleaned = hex.removePrefix("#")
            return when (cleaned.length) {
                6 -> Color(
                    cleaned.substring(0, 2).toInt(16),
                    cleaned.substring(2, 4).toInt(16),
                    cleaned.substring(4, 6).toInt(16)
                )
                8 -> Color(
                    cleaned.substring(2, 4).toInt(16),
                    cleaned.substring(4, 6).toInt(16),
                    cleaned.substring(6, 8).toInt(16),
                    cleaned.substring(0, 2).toInt(16)
                )
                else -> throw IllegalArgumentException("Invalid hex color: $hex")
            }
        }
    }
}

/**
 * CMYK color, 0.0–1.0 per channel. Used at export/print time.
 */
data class CmykColor(val c: Float, val m: Float, val y: Float, val k: Float) {
    init {
        require(c in 0f..1f && m in 0f..1f && y in 0f..1f && k in 0f..1f)
    }
}
