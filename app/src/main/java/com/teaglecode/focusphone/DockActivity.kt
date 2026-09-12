package com.teaglecode.focusphone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teaglecode.focusphone.data.AppCatalog
import com.teaglecode.focusphone.data.DockStore
import com.teaglecode.focusphone.data.IconCache
import com.teaglecode.focusphone.ui.Focus

/**
 * Choose the apps pinned to the home screen.
 *
 * Nothing here is a restriction, so nothing waits 24 hours — a dock is a
 * shortcut, and making shortcuts expensive to change would be friction with
 * no self-binding behind it. It sits under settings only because that is
 * where configuration lives, and settings is already behind the gate.
 */
class DockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DockScreen() }
    }
}

@Composable
private fun DockScreen() {
    val context = LocalContext.current
    val store = remember { DockStore(context) }

    var chosen by remember { mutableStateOf(store.packages()) }
    var query by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf(AppCatalog.snapshot().orEmpty()) }
    var icons by remember { mutableStateOf(IconCache.snapshot()) }

    // Every icon on this screen, not just the chosen ones, so the list below
    // is recognisable at a glance.
    LaunchedEffect(Unit) {
        installed = AppCatalog.load(context)
        icons = IconCache.load(context, installed.map { it.packageName })
    }

    fun commit(next: List<String>) {
        chosen = next
        store.save(next)
    }

    val shown = remember(query, installed) {
        if (query.isBlank()) installed
        else installed.filter { it.lowerLabel.contains(query.lowercase()) }
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .padding(horizontal = Focus.Gutter)
    ) {
        item(key = "head") {
            Spacer(Modifier.height(72.dp))
            Text(
                "app dock",
                color = Focus.Primary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = Focus.Tracking
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Up to ${DockStore.SLOTS} apps, one tap each from the home screen. " +
                    "Tap one below to add it, tap it in the dock to take it out. " +
                    "The order here is the order they appear.",
                color = Focus.Tertiary,
                fontSize = Focus.MetaSize,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(22.dp))
            Text(
                "in the dock — ${chosen.size} of ${DockStore.SLOTS}",
                color = Focus.Tertiary,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp
            )
            Spacer(Modifier.height(10.dp))
            DockPreview(chosen, icons) { pkg -> commit(chosen - pkg) }
            Spacer(Modifier.height(28.dp))

            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Focus.Primary, fontSize = 17.sp),
                cursorBrush = SolidColor(Focus.Secondary),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Focus.RadiusField))
                            .background(Focus.Surface)
                            .padding(horizontal = 18.dp, vertical = 15.dp)
                    ) {
                        if (query.isEmpty()) {
                            Text("filter", color = Focus.Tertiary, fontSize = 17.sp)
                        }
                        inner()
                    }
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        items(shown, key = { it.packageName }) { app ->
            val inDock = app.packageName in chosen
            val full = chosen.size >= DockStore.SLOTS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable(enabled = inDock || !full) {
                        commit(
                            if (inDock) chosen - app.packageName
                            else chosen + app.packageName
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) {
                AppIcon(icons[app.packageName], 30.dp)
                Spacer(Modifier.width(14.dp))
                Text(
                    app.label,
                    color = when {
                        inDock -> Focus.Primary
                        full -> Focus.Ghost
                        else -> Focus.Secondary
                    },
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f)
                )
                if (inDock) {
                    Text(
                        "${chosen.indexOf(app.packageName) + 1}",
                        color = Focus.Tertiary,
                        fontSize = 13.sp
                    )
                }
            }
        }

        item(key = "tail") { Spacer(Modifier.height(48.dp)) }
    }
}

/** The dock as it will look, and the only place to take an app out of it. */
@Composable
private fun DockPreview(
    chosen: List<String>,
    icons: Map<String, ImageBitmap>,
    onRemove: (String) -> Unit
) {
    if (chosen.isEmpty()) {
        Text(
            "empty — the home screen shows no dock until you add something",
            color = Focus.Ghost,
            fontSize = 14.sp
        )
        return
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.Surface)
            .padding(vertical = 6.dp)
    ) {
        // Laid out exactly as the home screen lays it out, so this preview is
        // the thing itself rather than a differently shaped approximation.
        chosen.chunked(COLUMNS).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.forEach { pkg ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Focus.RadiusRow))
                            .clickable { onRemove(pkg) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AppIcon(icons[pkg], 44.dp)
                    }
                }
                // Keeps cells the same width whether the row is full or not,
                // so icons do not resize as apps are added.
                repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Matches DOCK_COLUMNS on the home screen. */
private const val COLUMNS = 4

@Composable
private fun AppIcon(icon: ImageBitmap?, size: androidx.compose.ui.unit.Dp) {
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(size))
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Focus.SurfacePressed))
    }
}
