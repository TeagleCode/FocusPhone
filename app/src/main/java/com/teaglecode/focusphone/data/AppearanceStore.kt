package com.teaglecode.focusphone.data

import android.content.Context

/** Typeface choices that ship with Android, so nothing has to be bundled. */
enum class QuoteFont(val label: String) {
    DEFAULT("default"),
    SANS("sans"),
    SERIF("serif"),
    MONO("mono"),
    CURSIVE("cursive")
}

data class QuoteStyle(
    val text: String,
    /** Packed ARGB. Stored as a Long because prefs have no colour type. */
    val colorArgb: Long,
    val sizeSp: Int,
    val font: QuoteFont
) {
    val isSet get() = text.isNotBlank()
}

/**
 * The one piece of the launcher the user is meant to make their own. Kept in
 * its own store so appearance edits never invalidate the policy parse cache.
 */
class AppearanceStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("focus_appearance", Context.MODE_PRIVATE)

    fun quote(): QuoteStyle = QuoteStyle(
        text = prefs.getString(KEY_TEXT, "") ?: "",
        colorArgb = prefs.getLong(KEY_COLOR, DEFAULT_COLOR),
        sizeSp = prefs.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE),
        font = runCatching { QuoteFont.valueOf(prefs.getString(KEY_FONT, null) ?: "") }
            .getOrDefault(QuoteFont.DEFAULT)
    )

    fun saveQuote(style: QuoteStyle) {
        prefs.edit()
            .putString(KEY_TEXT, style.text.trim())
            .putLong(KEY_COLOR, style.colorArgb)
            .putInt(KEY_SIZE, style.sizeSp.coerceIn(MIN_SIZE, MAX_SIZE))
            .putString(KEY_FONT, style.font.name)
            .apply()
    }

    companion object {
        private const val KEY_TEXT = "quote_text"
        private const val KEY_COLOR = "quote_color"
        private const val KEY_SIZE = "quote_size"
        private const val KEY_FONT = "quote_font"

        const val MIN_SIZE = 12
        const val MAX_SIZE = 56
        const val DEFAULT_SIZE = 26
        const val DEFAULT_COLOR = 0xFFE7E2D8L

        /**
         * Muted rather than saturated, so a chosen colour still sits inside
         * the two-tone scheme instead of fighting it.
         */
        val PALETTE = listOf(
            "bone" to 0xFFE7E2D8L,
            "dim" to 0xFF8A857CL,
            "amber" to 0xFFD9A441L,
            "rust" to 0xFFC96F4AL,
            "sage" to 0xFF8FA98AL,
            "sky" to 0xFF7FA6C4L,
            "violet" to 0xFF9C8AC4L,
            "rose" to 0xFFC98A9EL
        )
    }
}
