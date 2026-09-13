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

    companion object {
        fun fromName(name: String): BlendMode =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: NORMAL
    }
}
