package com.focus.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.ui.Focus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LaunchableApp(val label: String, val packageName: String)

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HomeScreen() }
    }

    /** Home button returns here; clear whatever was being searched. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recreate()
    }
}

@Composable
private fun HomeScreen() {
    val context = LocalContext.current
    val enforcer = remember { Enforcer(context) }
    var query by remember { mutableStateOf("") }

    val apps = remember {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(main, 0)
            .map { it.toLaunchable(context.packageManager) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    LaunchedEffect(Unit) { enforcer.apply() }

    val filtered = remember(query, apps) {
        if (query.isBlank()) emptyList()
        else apps.filter { it.label.contains(query, ignoreCase = true) }.take(7)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(88.dp))

        Clock()

        Spacer(Modifier.height(44.dp))

        SearchField(value = query, onValueChange = { query = it })

        Spacer(Modifier.height(12.dp))

        LazyColumn {
            items(filtered) { app ->
                AppRow(app, enforcer) {
                    context.packageManager
                        .getLaunchIntentForPackage(app.packageName)
                        ?.let { context.startActivity(it) }
                    query = ""
                }
            }
        }

        Spacer(Modifier.weight(1f))

        SettingsLink {
            context.startActivity(Intent(context, SettingsActivity::class.java))
        }
    }
}

/**
 * The hour is stated plainly; the minutes recede. You get the time without
 * the precision inviting you to stand there reading it.
 */
@Composable
private fun Clock() {
    val now = Date()
    val hour = SimpleDateFormat("HH", Locale.US).format(now)
    val minute = SimpleDateFormat("mm", Locale.US).format(now)
    val date = SimpleDateFormat("EEEE d MMMM", Locale.US).format(now)

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = Focus.Primary)) { append(hour) }
            withStyle(SpanStyle(color = Focus.Ghost)) { append(":") }
            withStyle(SpanStyle(color = Focus.Secondary)) { append(minute) }
        },
        fontSize = Focus.ClockSize,
        fontWeight = FontWeight.Light,
        letterSpacing = (-1).sp
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = date.lowercase(),
        color = Focus.Tertiary,
        fontSize = Focus.MetaSize,
        letterSpacing = Focus.Tracking
    )
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
        textStyle = TextStyle(
            color = Focus.Primary,
            fontSize = Focus.SearchSize,
            letterSpacing = Focus.Tracking
        ),
        cursorBrush = SolidColor(Focus.Secondary),
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Focus.RadiusField))
                    .background(Focus.Surface)
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                if (value.isEmpty()) {
                    Text(
                        "search",
                        color = Focus.Tertiary,
                        fontSize = Focus.SearchSize,
                        letterSpacing = Focus.Tracking
                    )
                }
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AppRow(app: LaunchableApp, enforcer: Enforcer, onClick: () -> Unit) {
    val remaining = remember(app.packageName) { enforcer.remainingMinutes(app.packageName) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(if (pressed) Focus.SurfacePressed else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = Focus.RowInset)
    ) {
        Text(
            text = app.label.lowercase(),
            color = Focus.Primary,
            fontSize = Focus.AppSize,
            letterSpacing = Focus.Tracking,
            modifier = Modifier.weight(1f)
        )
        if (remaining != null) {
            Text(
                text = if (remaining > 0) "${remaining}m" else "blocked",
                color = if (remaining > 0) Focus.Tertiary else Focus.Secondary,
                fontSize = Focus.MetaSize,
                letterSpacing = Focus.Tracking
            )
        }
    }
}

@Composable
private fun SettingsLink(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "settings",
            color = if (pressed) Focus.Secondary else Focus.Ghost,
            fontSize = Focus.MetaSize,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 22.dp, vertical = 10.dp)
        )
    }
}

private fun ResolveInfo.toLaunchable(pm: PackageManager) =
    LaunchableApp(
        label = loadLabel(pm).toString(),
        packageName = activityInfo.packageName
    )
