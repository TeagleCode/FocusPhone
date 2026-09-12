package com.teaglecode.focusphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.teaglecode.focusphone.data.AppCatalog
import com.teaglecode.focusphone.data.AppearanceStore
import com.teaglecode.focusphone.data.BlockNotice
import com.teaglecode.focusphone.data.DockStore
import com.teaglecode.focusphone.data.IconCache
import com.teaglecode.focusphone.data.LaunchableApp
import com.teaglecode.focusphone.data.PolicyStore
import com.teaglecode.focusphone.data.TodoStore
import com.teaglecode.focusphone.data.TodoTask
import com.teaglecode.focusphone.policy.AppState
import com.teaglecode.focusphone.policy.EnforcementStatus
import com.teaglecode.focusphone.policy.Enforcer
import com.teaglecode.focusphone.policy.FocusGuardService
import com.teaglecode.focusphone.policy.PolicySnapshot
import com.teaglecode.focusphone.policy.PolicyWorker
import com.teaglecode.focusphone.ui.Focus
import com.teaglecode.focusphone.ui.QuoteBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : ComponentActivity() {

    /**
     * Bumped on every home press. The previous version called recreate() here,
     * which tore down the whole activity and re-queried every installed app's
     * label on the main thread — the single largest source of the stutter.
     */
    private val homePresses = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nothing else schedules this, so without it the periodic re-check
        // would never start until the first reboot.
        PolicyWorker.schedule(this)

        val store = PolicyStore(this)
        store.seedDomainsIfUnset()
        // Repairs section hints saved before v0.1.1, which matched a
        // navigation button and so closed the whole app rather than the feed.
        store.migrateSections(FocusGuardService.DEFAULT_SECTIONS)
        FocusGuardService.refreshScope()
        if (!store.isSetupComplete()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }

        setContent { HomeScreen(homePresses.value) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homePresses.value++
    }
}

/** How long after an interception the launcher still explains it. */
private const val NOTICE_TTL_MS = 60_000L

/**
 * Four across rather than eight leaves roughly 84dp per cell, which is enough
 * for the 48dp icon every other launcher uses and a comfortable tap target
 * around it.
 */
private const val DOCK_COLUMNS = 4
private val DOCK_ICON = 48.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(resetSignal: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enforcer = remember { Enforcer(context) }
    val policy = remember { PolicyStore(context) }
    val todos = remember { TodoStore(context) }
    val appearance = remember { AppearanceStore(context) }
    val dockStore = remember { DockStore(context) }

    var query by remember { mutableStateOf("") }
    LaunchedEffect(resetSignal) { query = "" }

    // Served from the process-wide cache first so the first frame is instant;
    // the refresh happens behind it on a background thread.
    var apps by remember { mutableStateOf(AppCatalog.snapshot().orEmpty()) }
    var snap by remember { mutableStateOf<PolicySnapshot?>(null) }
    var status by remember { mutableStateOf<EnforcementStatus?>(null) }
    var quote by remember { mutableStateOf(appearance.quote()) }
    var notice by remember { mutableStateOf<BlockNotice?>(null) }
    var dock by remember { mutableStateOf(dockStore.packages()) }
    var icons by remember { mutableStateOf(IconCache.snapshot()) }
    var agendaVersion by remember { mutableStateOf(0) }
    var socialLocked by remember { mutableStateOf(false) }
    var proofNote by remember { mutableStateOf<String?>(null) }

    val record = rememberProofRecorder(todos) { result ->
        agendaVersion++
        proofNote = when (result) {
            ProofResult.NoCamera -> "No camera app answered, so there is no way to film this one."
            ProofResult.Cancelled -> null
            ProofResult.Recorded -> null
        }
    }

    ObserveResume {
        // Whatever was open has ended; credit its time before anything else.
        FocusGuardService.notifyLauncherForeground()
        quote = appearance.quote()
        notice = policy.lastBlock()
            ?.takeIf { System.currentTimeMillis() - it.atMs < NOTICE_TTL_MS }
        agendaVersion++

        scope.launch {
            val loaded = AppCatalog.load(context)
            if (loaded.isNotEmpty()) apps = loaded
            withContext(Dispatchers.IO) {
                val installed = loaded.map { it.packageName }.toSet()
                policy.seedSocialIfUnset(installed)
                dockStore.seedIfUnset(context, installed)
                // Clips are megabytes each, so they are swept every time the
                // launcher comes forward rather than waiting for a job.
                todos.pruneClips()
                // Ahead of the dock work below: enforcement must not queue
                // behind icon rasterising on a first run.
                enforcer.apply()
            }
            dock = dockStore.packages()
            // Rasterising happens off the main thread; until it lands the dock
            // draws placeholders of the same size, so nothing reflows.
            icons = IconCache.load(context, dock)
            status = withContext(Dispatchers.IO) { enforcer.status() }
            snap = withContext(Dispatchers.IO) { enforcer.snapshot() }
            socialLocked = withContext(Dispatchers.IO) { todos.socialLockedToday() }
        }
    }

    val filtered = remember(query, apps) {
        if (query.isBlank()) emptyList()
        else {
            val needle = query.lowercase()
            apps.filter { it.lowerLabel.contains(needle) }.take(7)
        }
    }

    val openSettings = {
        context.startActivity(Intent(context, SettingsActivity::class.java))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            // A long press on empty space is the second route into settings,
            // so it stays reachable even with the field focused.
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = openSettings,
                onClick = {}
            )
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(64.dp))

        Clock()

        Spacer(Modifier.height(26.dp))

        SearchField(value = query, onValueChange = { query = it })

        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.weight(1f)) {
            if (query.isNotBlank()) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(app, snap?.let { enforcer.stateOf(app.packageName, it) }) {
                        context.packageManager
                            .getLaunchIntentForPackage(app.packageName)
                            ?.let { context.startActivity(it) }
                        query = ""
                    }
                }
                return@LazyColumn
            }

            notice?.let { n ->
                item(key = "notice") {
                    // Resolved against the loaded list rather than through a
                    // static lookup, so the label appears as soon as the
                    // catalogue arrives instead of staying a package name.
                    val label = apps.firstOrNull { it.packageName == n.packageName }?.label
                        ?: n.packageName
                    BlockNoticeCard(n, label) {
                        policy.clearBlockNotice()
                        notice = null
                    }
                }
            }

            status?.takeIf { !it.canBlock }?.let {
                item(key = "banner") {
                    EnforcementBanner {
                        context.startActivity(Intent(context, SetupActivity::class.java))
                    }
                }
            }

            item(key = "agenda") {
                AgendaCard(
                    todos = todos,
                    version = agendaVersion,
                    socialLocked = socialLocked,
                    note = proofNote,
                    onTap = { task ->
                        proofNote = null
                        val day = TodoStore.todayKey()
                        when {
                            // Only footage counts: an unfilmed proof task is
                            // ticked by the camera coming back, never by a tap.
                            task.requireVideo && !todos.isDone(day, task.id) ->
                                record(day, task.id)
                            task.requireVideo ->
                                todos.clearProof(day, task.id)
                            else -> todos.toggle(day, task.id)
                        }
                        agendaVersion++
                        scope.launch {
                            socialLocked = withContext(Dispatchers.IO) { todos.socialLockedToday() }
                        }
                    },
                    onManage = { context.startActivity(Intent(context, TodoActivity::class.java)) }
                )
            }

            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
        }

        DockBar(packages = dock, icons = icons) { pkg ->
            context.packageManager.getLaunchIntentForPackage(pkg)
                ?.let { context.startActivity(it) }
        }

        if (quote.isSet) {
            QuoteBlock(quote, Modifier.padding(bottom = 18.dp, top = 8.dp))
        }

        SettingsLink(onClick = openSettings)
    }
}

// ---- Agenda ---------------------------------------------------------------

/**
 * The daily list, and the reason it matters: leaving it unfinished locks the
 * apps flagged as social for the whole of the next day.
 */
@Composable
private fun AgendaCard(
    todos: TodoStore,
    version: Int,
    socialLocked: Boolean,
    note: String?,
    onTap: (TodoTask) -> Unit,
    onManage: () -> Unit
) {
    val today = TodoStore.todayKey()
    val tasks = remember(version) { todos.agenda(today) }
    val done = remember(version) { todos.completed(today) }
    val doneCount = tasks.count { it.id in done }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.Surface)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "today",
                color = Focus.Tertiary,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (tasks.isEmpty()) "" else "$doneCount of ${tasks.size}",
                color = if (tasks.isNotEmpty() && doneCount == tasks.size) Focus.Primary
                else Focus.Tertiary,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        if (tasks.isEmpty()) {
            Text(
                "nothing on the list yet",
                color = Focus.Ghost,
                fontSize = 15.sp
            )
        } else {
            tasks.forEach { task ->
                val isDone = task.id in done
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Focus.RadiusRow))
                        .clickable { onTap(task) }
                        .padding(vertical = 9.dp)
                ) {
                    Text(
                        if (isDone) "●" else "○",
                        color = if (isDone) Focus.Secondary else Focus.Ghost,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        task.text,
                        color = if (isDone) Focus.Ghost else Focus.Primary,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        textDecoration = if (isDone) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f)
                    )
                    if (task.requireVideo) {
                        Text(
                            if (isDone) "filmed" else "film it",
                            color = if (isDone) Focus.Ghost else Focus.Secondary,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    if (task.recurring) {
                        Text("daily", color = Focus.Ghost, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            when {
                note != null -> note
                socialLocked ->
                    "social apps are locked today — yesterday's list was left unfinished"
                tasks.isEmpty() ->
                    "an empty list has no consequence. add something to make the day count."
                doneCount == tasks.size ->
                    "all done. social apps stay open tomorrow."
                else ->
                    "finish these before midnight or social apps lock tomorrow"
            },
            color = Focus.Tertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(12.dp))

        Text(
            "edit list",
            color = Focus.Secondary,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.SurfacePressed)
                .clickable(onClick = onManage)
                .padding(horizontal = 16.dp, vertical = 9.dp)
        )
    }
}

// ---- Dock -----------------------------------------------------------------

/**
 * The eight pinned apps, one tap each, as two rows of four.
 *
 * Four across leaves roughly double the width per cell that eight did, which
 * is the difference between a 34dp icon and a properly tappable one. No
 * labels: a dock is recognised by icon and position the way a home row is, and
 * the searchable list above is what you use when you have to think about it.
 * Cells are equally weighted rather than fixed, so a row fits whatever width
 * it is given instead of overflowing on a narrow screen.
 */
@Composable
private fun DockBar(
    packages: List<String>,
    icons: Map<String, ImageBitmap>,
    onOpen: (String) -> Unit
) {
    if (packages.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        packages.chunked(DOCK_COLUMNS).forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                row.forEach { pkg ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Focus.RadiusRow))
                            .clickable { onOpen(pkg) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DockIcon(icons[pkg], AppCatalog.labelFor(pkg))
                    }
                }
                // A short last row keeps its icons under the ones above rather
                // than spreading to fill the width.
                repeat(DOCK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DockIcon(icon: ImageBitmap?, label: String) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = label,
            modifier = Modifier.size(DOCK_ICON)
        )
    } else {
        // Same footprint as the real icon, so the grid does not reflow when
        // the bitmaps finish rasterising.
        Box(
            Modifier
                .size(DOCK_ICON)
                .clip(CircleShape)
                .background(Focus.Surface)
        )
    }
}

// ---- Notices --------------------------------------------------------------

/** Explains an interception that has just happened, since the guard closes the app silently. */
@Composable
private fun BlockNoticeCard(notice: BlockNotice, label: String, onDismiss: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.SurfacePressed)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            "${label.lowercase()} was closed",
            color = Focus.Primary,
            fontSize = 15.sp,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(6.dp))
        Text(
            Enforcer.explainLong(notice.reason),
            color = Focus.Tertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/**
 * Shown only when neither enforcement layer is available, because at that
 * point the app is decoration and should say so.
 */
@Composable
private fun EnforcementBanner(onFix: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.Surface)
            .clickable(onClick = onFix)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text("not enforcing", color = Focus.Primary, fontSize = 15.sp, letterSpacing = Focus.Tracking)
        Spacer(Modifier.height(6.dp))
        Text(
            "Nothing is being blocked. Turn on the accessibility service to fix it — tap here.",
            color = Focus.Tertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

// ---- Chrome ---------------------------------------------------------------

/**
 * The hour is stated plainly; the minutes recede. You get the time without
 * the precision inviting you to stand there reading it.
 */
@Composable
private fun Clock() {
    var now by remember { mutableStateOf(Date()) }
    // Wakes on the minute boundary rather than on a fixed interval, so the
    // displayed time is never stale and the launcher recomposes 60 times an
    // hour instead of 360.
    LaunchedEffect(Unit) {
        while (true) {
            delay(msToNextMinute())
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
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
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
private fun AppRow(app: LaunchableApp, state: AppState?, onClick: () -> Unit) {
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
            text = app.lowerLabel,
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
            is AppState.Blocked -> Text(
                Enforcer.explain(state.reason),
                color = Focus.Secondary,
                fontSize = Focus.MetaSize,
                letterSpacing = Focus.Tracking
            )
            else -> Unit
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
            .padding(bottom = 30.dp),
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

// ---- Date helpers ---------------------------------------------------------

private fun msToNextMinute(): Long {
    val now = System.currentTimeMillis()
    return 60_000L - (now % 60_000L)
}

