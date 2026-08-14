package com.focus.launcher

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.graphics.Color
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.policy.AppState
import com.focus.launcher.policy.EnforcementBlocker
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.policy.PolicyWorker
import com.focus.launcher.ui.Focus
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LaunchableApp(val label: String, val packageName: String)

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nothing else schedules this, so without it the periodic re-check
        // would never start until the first reboot.
        PolicyWorker.schedule(this)

        val store = PolicyStore(this)
        store.seedDomainsIfUnset()
        if (!store.isSetupComplete()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        setContent { HomeScreen() }
    }

    /** Home button returns here; clear whatever was being searched. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        recreate()
    }
}

@OptIn(ExperimentalFoundationApi::class)
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

    // Re-read on every resume, not just first composition: returning from an
    // app with the back gesture does not recreate the activity, and a limit
    // reached while that app was open must show up here.
    var pass by remember { mutableStateOf(0) }
    var blocker by remember { mutableStateOf<EnforcementBlocker?>(null) }
    var usage by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }

    ObserveResume {
        enforcer.apply()
        blocker = enforcer.blocker()
        usage = if (blocker == EnforcementBlocker.NO_USAGE_ACCESS) emptyMap()
        else enforcer.usageTodayByPackage()
        pass++
    }

    val filtered = remember(query, apps) {
        if (query.isBlank()) emptyList()
        else apps.filter { it.label.contains(query, ignoreCase = true) }.take(7)
    }

    val openSettings = {
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            // A long press anywhere on the home screen is the second route
            // into settings, so it is reachable even with the field focused.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = openSettings,
                onClick = {}
            )
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(88.dp))

        Clock()

        Spacer(Modifier.height(44.dp))

        SearchField(value = query, onValueChange = { query = it })

        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(filtered) { app ->
                AppRow(app, remember(pass, usage) { enforcer.stateOf(app.packageName, usage) }) {
                    context.packageManager
                        .getLaunchIntentForPackage(app.packageName)
                        ?.let { context.startActivity(it) }
                    query = ""
                }
            }
        }

        blocker?.let { EnforcementBanner(it) { context.startActivity(Intent(context, SetupActivity::class.java)) } }

        SettingsLink(onClick = openSettings)
    }
}

/**
 * Spec §8: when enforcement cannot work, say so on the home screen rather than
 * appearing to be running.
 */
@Composable
private fun EnforcementBanner(blocker: EnforcementBlocker, onFix: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(Focus.Surface)
            .clickable(onClick = onFix)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            when (blocker) {
                EnforcementBlocker.NOT_DEVICE_OWNER -> "not enforcing"
                EnforcementBlocker.NO_USAGE_ACCESS -> "cannot measure usage"
            },
            color = Focus.Primary,
            fontSize = 14.sp,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(5.dp))
        Text(
            when (blocker) {
                EnforcementBlocker.NOT_DEVICE_OWNER ->
                    "Focus is not the device owner, so nothing is being blocked. Tap to fix."
                EnforcementBlocker.NO_USAGE_ACCESS ->
                    "Usage access is off, so time limits do nothing. Tap to fix."
            },
            color = Focus.Tertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/**
 * The hour is stated plainly; the minutes recede. You get the time without
 * the precision inviting you to stand there reading it.
 */
@Composable
private fun Clock() {
    // Recomposed on a tick, otherwise the clock freezes at the moment the
    // launcher was composed and quietly shows the wrong time all day.
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = Date()
        }
    }

    val hour = remember(now) { SimpleDateFormat("HH", Locale.US).format(now) }
    val minute = remember(now) { SimpleDateFormat("mm", Locale.US).format(now) }
    val date = remember(now) { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(now) }

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
private fun AppRow(app: LaunchableApp, state: AppState, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(if (pressed) Focus.SurfacePressed else Color.Transparent)
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
        when (state) {
            is AppState.Remaining -> Text(
                "${state.minutes}m",
                color = Focus.Tertiary,
                fontSize = Focus.MetaSize,
                letterSpacing = Focus.Tracking
            )
            AppState.Blocked -> Text(
                "blocked",
                color = Focus.Secondary,
                fontSize = Focus.MetaSize,
                letterSpacing = Focus.Tracking
            )
            AppState.Unrestricted -> Unit
        }
    }
}

/**
 * Legible on its own. The old version drew this at 14% opacity against a
 * near-black background, which is why the launcher looked like it had no way
 * into settings at all.
 */
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
            color = if (pressed) Focus.Primary else Focus.Secondary,
            fontSize = 15.sp,
            letterSpacing = 1.2.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(if (pressed) Focus.SurfacePressed else Focus.Surface)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(horizontal = 28.dp, vertical = 12.dp)
        )
    }
}

@Composable
private fun ObserveResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

private fun ResolveInfo.toLaunchable(pm: PackageManager) =
    LaunchableApp(
        label = loadLabel(pm).toString(),
        packageName = activityInfo.packageName
    )
