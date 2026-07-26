package com.drawit.core.document

/**
 * Measurement units. Internal document space is always millimeters.
 */
enum class Unit(val displayName: String, val shortName: String, val mmPerUnit: Float) {
    MM("Millimeters", "mm", 1f),
    CM("Centimeters", "cm", 10f),
    INCH("Inches", "in", 25.4f),
    PT("Points", "pt", 25.4f / 72f),
    PX("Pixels", "px", 25.4f / 96f); // at 96 dpi reference

    fun toMm(value: Float): Float = value * mmPerUnit
    fun fromMm(mm: Float): Float = mm / mmPerUnit

    fun format(mm: Float, decimals: Int = 2): String =
        "%.${decimals}f %s".format(fromMm(mm), shortName)

    companion object {
        fun fromName(name: String): Unit =
            entries.find { it.name.equals(name, ignoreCase = true) } ?: MM
    }
}

/**
 * A page size preset for the New Document dialog.
 */
data class PagePreset(
    val name: String,
    val category: Category,
    val widthMm: Float,
    val heightMm: Float,
    val landscape: Boolean = false
) {
    enum class Category(val displayName: String) {
        PRINT("Print"),
        SIGN("Sign & Vinyl"),
        DIGITAL("Digital & Social"),
        CUSTOM("Custom")
    }

    val displaySize: String
        get() = "%.0f × %.0f mm".format(widthMm, heightMm)

    companion object {
        val ALL: List<PagePreset> = listOf(
            // --- Print (ISO A-series) ---
            PagePreset("A6", Category.PRINT, 105f, 148f),
            PagePreset("A5", Category.PRINT, 148f, 210f),
            PagePreset("A4", Category.PRINT, 210f, 297f),
            PagePreset("A3", Category.PRINT, 297f, 420f),
            PagePreset("A2", Category.PRINT, 420f, 594f),
            PagePreset("A1", Category.PRINT, 594f, 841f),
            PagePreset("A0", Category.PRINT, 841f, 1189f),
            // --- Print (US) ---
            PagePreset("Letter", Category.PRINT, 215.9f, 279.4f),
            PagePreset("Legal", Category.PRINT, 215.9f, 355.6f),
            PagePreset("Tabloid", Category.PRINT, 279.4f, 431.8f),
            // --- Sign & vinyl ---
            PagePreset("Sign 600×300", Category.SIGN, 600f, 300f, landscape = true),
            PagePreset("Sign 1200×600", Category.SIGN, 1200f, 600f, landscape = true),
            PagePreset("Sign 2400×1200", Category.SIGN, 2400f, 1200f, landscape = true),
            PagePreset("Vinyl roll 610", Category.SIGN, 610f, 1000f),
            PagePreset("Vinyl roll 1220", Category.SIGN, 1220f, 1000f),
            PagePreset("Vinyl roll 1370", Category.SIGN, 1370f, 1000f),
            // --- Digital ---
            PagePreset("Full HD", Category.DIGITAL, 508f, 285.75f, landscape = true),   // 1920×1080 @96dpi
            PagePreset("4K UHD", Category.DIGITAL, 1016f, 571.5f, landscape = true),    // 3840×2160
            PagePreset("Instagram Post", Category.DIGITAL, 285.75f, 285.75f),           // 1080×1080
            PagePreset("Instagram Story", Category.DIGITAL, 285.75f, 508f),             // 1080×1920
        )

        fun byCategory(cat: Category): List<PagePreset> = ALL.filter { it.category == cat }
    }
}
