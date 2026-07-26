package com.drawit.core.document

/**
 * Blend modes restricted to the set that survives SVG 2 / PDF export losslessly.
 * (PDF supports all of these natively; SVG via CSS mix-blend-mode.)
 */
enum class BlendMode(val displayName: String) {
    NORMAL("Normal"),
    MULTIPLY("Multiply"),
    SCREEN("Screen"),
    OVERLAY("Overlay"),
    DARKEN("Darken"),
    LIGHTEN("Lighten");

    /** Map to Android PorterDuff for Skia rendering (works on all API levels). */
    fun toPorterDuff(): android.graphics.PorterDuff.Mode = when (this) {
        NORMAL -> android.graphics.PorterDuff.Mode.SRC_OVER
        MULTIPLY -> android.graphics.PorterDuff.Mode.MULTIPLY
        SCREEN -> android.graphics.PorterDuff.Mode.SCREEN
        OVERLAY -> android.graphics.PorterDuff.Mode.OVERLAY
        DARKEN -> android.graphics.PorterDuff.Mode.DARKEN
        LIGHTEN -> android.graphics.PorterDuff.Mode.LIGHTEN
    }

    companion object {
        fun fromName(name: String): BlendMode =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}
