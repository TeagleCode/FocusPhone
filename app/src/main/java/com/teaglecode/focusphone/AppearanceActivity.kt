package com.focus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.AppearanceStore
import com.focus.launcher.data.QuoteFont
import com.focus.launcher.ui.Focus
import com.focus.launcher.ui.QuoteBlock
import com.focus.launcher.ui.toFontFamily

/**
 * The quote shown at the bottom of the launcher, and the only place the
 * two-colour rule is relaxed — deliberately, because this line is the user's
 * rather than the app's.
 */
class AppearanceActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppearanceScreen() }
    }
}

@Composable
private fun AppearanceScreen() {
    val context = LocalContext.current
    val store = remember { AppearanceStore(context) }

    var style by remember { mutableStateOf(store.quote()) }
    var hex by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    // Saving on every edit would mean the launcher shows half-typed lines, so
    // changes are held here and written on an explicit save.
    fun edit(block: () -> Unit) {
        block()
        saved = false
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "your line",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Shown large at the bottom of the home screen. Leave it empty to hide it.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(24.dp))

        BasicTextField(
            value = style.text,
            onValueChange = { edit { style = style.copy(text = it) } },
            textStyle = TextStyle(color = Focus.Primary, fontSize = 17.sp, lineHeight = 25.sp),
            cursorBrush = SolidColor(Focus.Secondary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                        .clip(RoundedCornerShape(Focus.RadiusField))
                        .background(Focus.Surface)
                        .padding(18.dp)
                ) {
                    if (style.text.isEmpty()) {
                        Text("write something worth reading daily", color = Focus.Tertiary, fontSize = 17.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(26.dp))

        // ---- Colour ------------------------------------------------------

        Label("colour")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            AppearanceStore.PALETTE.forEach { (_, argb) ->
                val selected = style.colorArgb == argb
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .size(if (selected) 30.dp else 26.dp)
                        .clip(CircleShape)
                        .background(Color(argb.toInt()))
                        .border(
                            width = if (selected) 2.dp else 0.dp,
                            color = Focus.Primary,
                            shape = CircleShape
                        )
                        .clickable { edit { style = style.copy(colorArgb = argb) } }
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = hex,
                onValueChange = { hex = it.filter { c -> c.isLetterOrDigit() }.take(6) },
                singleLine = true,
                textStyle = TextStyle(color = Focus.Primary, fontSize = 14.sp),
                cursorBrush = SolidColor(Focus.Secondary),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(Focus.RadiusRow))
                            .background(Focus.Surface)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        if (hex.isEmpty()) {
                            Text("or a hex code", color = Focus.Tertiary, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.width(170.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "use",
                color = Focus.Secondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable {
                        hex.toLongOrNull(16)?.takeIf { hex.length == 6 }?.let { rgb ->
                            edit { style = style.copy(colorArgb = 0xFF000000L or rgb) }
                        }
                    }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }

        Spacer(Modifier.height(26.dp))

        // ---- Size --------------------------------------------------------

        Label("size · ${style.sizeSp}")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stepper("−") {
                edit { style = style.copy(sizeSp = (style.sizeSp - 2).coerceAtLeast(AppearanceStore.MIN_SIZE)) }
            }
            Spacer(Modifier.width(10.dp))
            Stepper("+") {
                edit { style = style.copy(sizeSp = (style.sizeSp + 2).coerceAtMost(AppearanceStore.MAX_SIZE)) }
            }
        }

        Spacer(Modifier.height(26.dp))

        // ---- Font --------------------------------------------------------

        Label("typeface")
        QuoteFont.entries.forEach { font ->
            val selected = style.font == font
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(if (selected) Focus.SurfacePressed else Focus.Surface)
                    .clickable { edit { style = style.copy(font = font) } }
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Text(
                    font.label,
                    color = if (selected) Focus.Primary else Focus.Secondary,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "Aa",
                    color = if (selected) Focus.Primary else Focus.Tertiary,
                    fontSize = 17.sp,
                    fontFamily = font.toFontFamily()
                )
            }
        }

        Spacer(Modifier.height(30.dp))

        // ---- Preview -----------------------------------------------------

        Label("preview")
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Focus.RadiusField))
                .background(Focus.Ink)
                .border(1.dp, Focus.Surface, RoundedCornerShape(Focus.RadiusField))
                .padding(vertical = 26.dp, horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            if (style.isSet) {
                QuoteBlock(style)
            } else {
                Text("nothing to show", color = Focus.Ghost, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(26.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "save",
                color = Focus.Primary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable {
                        store.saveQuote(style)
                        saved = true
                    }
                    .padding(horizontal = 26.dp, vertical = 13.dp)
            )
            if (saved) {
                Spacer(Modifier.width(14.dp))
                Text("saved", color = Focus.Secondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Stepper(glyph: String, onClick: () -> Unit) {
    Text(
        glyph,
        color = Focus.Primary,
        fontSize = 20.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(Focus.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 10.dp)
    )
}
