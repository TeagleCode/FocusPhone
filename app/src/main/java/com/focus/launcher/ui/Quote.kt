package com.focus.launcher.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.QuoteFont
import com.focus.launcher.data.QuoteStyle

fun QuoteFont.toFontFamily(): FontFamily = when (this) {
    QuoteFont.DEFAULT -> FontFamily.Default
    QuoteFont.SANS -> FontFamily.SansSerif
    QuoteFont.SERIF -> FontFamily.Serif
    QuoteFont.MONO -> FontFamily.Monospace
    QuoteFont.CURSIVE -> FontFamily.Cursive
}

/**
 * The user's own line, rendered exactly as they set it. Shared between the
 * launcher and the editor's preview so what they choose is what they get.
 */
@Composable
fun QuoteBlock(style: QuoteStyle, modifier: Modifier = Modifier) {
    if (!style.isSet) return
    Text(
        text = style.text,
        color = Color(style.colorArgb.toInt()),
        fontSize = style.sizeSp.sp,
        lineHeight = (style.sizeSp * 1.25f).sp,
        fontFamily = style.font.toFontFamily(),
        fontWeight = FontWeight.Light,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth()
    )
}
