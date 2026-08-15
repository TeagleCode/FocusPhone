package com.teaglecode.focusphone

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.teaglecode.focusphone.data.LaunchableApp
import com.teaglecode.focusphone.data.PolicyStore
import com.teaglecode.focusphone.data.TodoStore
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
import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(resetSignal: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enforcer = remember { Enforcer(context) }
    val policy = remember { PolicyStore(context) }
    val todos = remember { TodoStore(context) }
    val appearance = remember { AppearanceStore(context) }

    var query by remember { mutableStateOf("") }
    LaunchedEffect(resetSignal) { query = "" }

    // Served from the process-wide cache first so the first frame is instant;
    // the refresh happens behind it on a background thread.
    var apps by remember { mutableStateOf(AppCatalog.snapshot().orEmpty()) }
    var snap by remember { mutableStateOf<PolicySnapshot?>(null) }
    var status by remember { mutableStateOf<EnforcementStatus?>(null) }
    var quote by remember { mutableStateOf(appearance.quote()) }
    var notice by remember { mutableStateOf<BlockNotice?>(null) }
    var selectedDate by remember { mutableStateOf(TodoStore.todayKey()) }
    var agendaVersion by remember { mutableStateOf(0) }
    var socialLocked by remember { mutableStateOf(false) }

    ObserveResume {
        // Whatever was open has ended; credit its time before anything else.
        FocusGuardService.notifyLauncherForeground()
        quote = appearance.quote()
        notice = policy.lastBlock()
            ?.takeIf { System.currentTimeMillis() - it.atMs < NOTICE_TTL_MS }
        selectedDate = TodoStore.todayKey()
        agendaVersion++

        scope.launch {
            val loaded = AppCatalog.load(context)
            if (loaded.isNotEmpty()) apps = loaded
            withContext(Dispatchers.IO) {
                policy.seedSocialIfUnset(loaded.map { it.packageName }.toSet())
                enforcer.apply()
            }
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
                    date = selectedDate,
                    version = agendaVersion,
                    socialLocked = socialLocked,
                    onToggle = { id ->
                        todos.toggle(selectedDate, id)
                        agendaVersion++
                        scope.launch {
                            socialLocked = withContext(Dispatchers.IO) { todos.socialLockedToday() }
                        }
                    },
                    onManage = { context.startActivity(Intent(context, TodoActivity::class.java)) }
                )
            }

            item(key = "calendar") {
                MonthCalendar(
                    todos = todos,
                    selected = selectedDate,
                    version = agendaVersion,
                    onSelect = { selectedDate = it }
                )
            }

            item(key = "tail") { Spacer(Modifier.height(12.dp)) }
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
    date: String,
    version: Int,
    socialLocked: Boolean,
    onToggle: (String) -> Unit,
    onManage: () -> Unit
) {
    val today = TodoStore.todayKey()
    val editable = date >= today
    val tasks = remember(date, version) { todos.agenda(date) }
    val done = remember(date, version) { todos.completed(date) }
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
                if (date == today) "today" else prettyDate(date),
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
                if (editable) "nothing on the list yet" else "nothing was on the list",
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
                        .then(
                            if (editable) Modifier.clickable { onToggle(task.id) }
                            else Modifier
                        )
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
                    if (task.recurring) {
                        Text("daily", color = Focus.Ghost, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            when {
                socialLocked ->
                    "social apps are locked today — yesterday's list was left unfinished"
                date != today -> "read only"
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

// ---- Calendar -------------------------------------------------------------

/**
 * A month at a glance. A day carrying tasks gets a marker beneath it: filled
 * when the list was completed, hollow when it was not.
 */
@Composable
private fun MonthCalendar(
    todos: TodoStore,
    selected: String,
    version: Int,
    onSelect: (String) -> Unit
) {
    val today = TodoStore.todayKey()
    var anchor by remember { mutableStateOf(monthOf(selected)) }
    LaunchedEffect(selected) { anchor = monthOf(selected) }

    val (year, month) = anchor
    val summary = remember(year, month, version) { todos.monthSummary(year, month) }

    val cal = remember(year, month) {
        Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    val firstDow = cal.firstDayOfWeek
    val lead = ((cal.get(Calendar.DAY_OF_WEEK) - firstDow) + 7) % 7
    val dayCount = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val title = remember(year, month) {
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time).lowercase()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.Surface)
            .padding(horizontal = 14.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = Focus.Secondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable { anchor = shiftMonth(anchor, -1) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Text(
                title,
                color = Focus.Tertiary,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            Text(
                "›",
                color = Focus.Secondary,
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable { anchor = shiftMonth(anchor, 1) }
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        val headers = remember(firstDow) { weekdayInitials(firstDow) }
        Row(Modifier.fillMaxWidth()) {
            headers.forEach {
                Text(
                    it,
                    color = Focus.Ghost,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        val cells = lead + dayCount
        val rows = (cells + 6) / 7
        repeat(rows) { row ->
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { col ->
                    val index = row * 7 + col
                    val day = index - lead + 1
                    if (day < 1 || day > dayCount) {
                        Spacer(Modifier.weight(1f))
                        return@repeat
                    }
                    val key = String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)
                    DayCell(
                        day = day,
                        isSelected = key == selected,
                        isToday = key == today,
                        isFuture = key > today,
                        progress = summary[key],
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(key) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    isSelected: Boolean,
    isToday: Boolean,
    isFuture: Boolean,
    progress: com.teaglecode.focusphone.data.AgendaProgress?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(1.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Focus.SurfacePressed else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp)
    ) {
        Text(
            day.toString(),
            color = when {
                isToday || isSelected -> Focus.Primary
                isFuture -> Focus.Ghost
                else -> Focus.Secondary
            },
            fontSize = 13.sp
        )
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        progress == null || progress.empty -> Color.Transparent
                        progress.complete -> Focus.Secondary
                        else -> Focus.Ghost
                    }
                )
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

private fun monthOf(dateKey: String): Pair<Int, Int> {
    val parts = dateKey.split('-')
    val year = parts.getOrNull(0)?.toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
    val month = (parts.getOrNull(1)?.toIntOrNull() ?: 1) - 1
    return year to month
}

private fun shiftMonth(anchor: Pair<Int, Int>, delta: Int): Pair<Int, Int> {
    val total = anchor.first * 12 + anchor.second + delta
    return Math.floorDiv(total, 12) to Math.floorMod(total, 12)
}

private fun prettyDate(dateKey: String): String {
    val parsed = runCatching {
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateKey)
    }.getOrNull() ?: return dateKey
    return SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(parsed).lowercase()
}

/** Weekday initials starting from the locale's own first day of the week. */
private fun weekdayInitials(firstDayOfWeek: Int): List<String> {
    val short = DateFormatSymbols.getInstance().shortWeekdays
    return (0 until 7).map { offset ->
        val dow = ((firstDayOfWeek - 1 + offset) % 7) + 1
        short.getOrNull(dow)?.take(1)?.lowercase() ?: ""
    }
}
