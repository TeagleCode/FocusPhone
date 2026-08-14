package com.focus.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.focus.launcher.data.AppRule
import com.focus.launcher.data.PendingUnlock
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.RestrictionType
import com.focus.launcher.data.UnlockKind
import com.focus.launcher.gate.ChallengeGenerator
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.ui.Focus
import java.util.concurrent.TimeUnit

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SettingsRoot() }
    }
}

@Composable
private fun SettingsRoot() {
    // Deliberately not saved across process death: coming back to a killed
    // settings screen should mean solving the gate again.
    var unlocked by remember { mutableStateOf(false) }
    if (unlocked) SettingsScreen() else GateScreen { unlocked = true }
}

/**
 * Every entry into settings requires a fresh challenge, and a wrong answer
 * replaces the question with a different one rather than letting the same
 * question be retried.
 */
@Composable
private fun GateScreen(onPass: () -> Unit) {
    val context = LocalContext.current
    val required = remember { PolicyStore(context).gateProblemCount() }

    // Every prompt seen this sitting, so neither a wrong answer nor a later
    // question in the same run can hand back one already shown.
    val seen = remember { mutableStateListOf<String>() }
    var solved by remember { mutableStateOf(0) }
    var challenge by remember { mutableStateOf(ChallengeGenerator.next()) }
    var input by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var showHint by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(88.dp))

        Text(
            if (required == 1) "solve to continue"
            else "solve ${solved + 1} of $required",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            letterSpacing = 1.2.sp
        )

        Spacer(Modifier.height(28.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Focus.RadiusField))
                .background(Focus.Surface)
                .padding(22.dp)
        ) {
            Text(
                challenge.prompt,
                color = Focus.Primary,
                fontSize = 18.sp,
                lineHeight = 28.sp
            )
        }

        Spacer(Modifier.height(20.dp))

        BasicTextField(
            value = input,
            onValueChange = { input = it; wrong = false },
            singleLine = true,
            textStyle = TextStyle(
                color = Focus.Primary,
                fontSize = Focus.SearchSize,
                letterSpacing = Focus.Tracking
            ),
            cursorBrush = SolidColor(Focus.Secondary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Focus.RadiusField))
                        .background(Focus.Surface)
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    if (input.isEmpty()) {
                        Text("answer", color = Focus.Tertiary, fontSize = Focus.SearchSize)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (wrong) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Not correct. Here is a different question.",
                color = Focus.Secondary,
                fontSize = Focus.MetaSize
            )
        }

        Spacer(Modifier.height(24.dp))

        PressableLabel(
            text = "check",
            emphasis = true,
            onClick = {
                val correct = challenge.accepts(input)
                seen.add(challenge.prompt)
                input = ""
                if (correct) {
                    solved++
                    wrong = false
                    showHint = false
                    if (solved >= required) return@PressableLabel onPass()
                } else {
                    // A failed question is spent: it is added to the seen set
                    // like any other, so the gate can never be brute-forced by
                    // guessing at the same question twice.
                    wrong = true
                    showHint = true
                }
                challenge = ChallengeGenerator.next(avoid = seen.toSet())
            }
        )

        if (showHint) {
            challenge.hint?.let {
                Spacer(Modifier.height(22.dp))
                Text(it, color = Focus.Ghost, fontSize = Focus.MetaSize)
            }
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { PolicyStore(context) }
    val enforcer = remember { Enforcer(context) }

    // Rules and the pending request change on other screens, so re-read them
    // whenever this one comes back rather than holding a stale first snapshot.
    var rules by remember { mutableStateOf(store.rules()) }
    var pending by remember { mutableStateOf(store.pendingUnlock()) }
    var notice by remember { mutableStateOf<String?>(null) }

    ObserveResume {
        rules = store.rules()
        pending = store.pendingUnlock()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))

        Text(
            "restrictions",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )

        pending?.let { p ->
            Spacer(Modifier.height(24.dp))
            PendingUnlockCard(
                pending = p,
                label = when (p.kind) {
                    UnlockKind.DOMAIN -> p.target
                    else -> enforcer.labelOf(p.target)
                },
                emergencyArmed = store.emergencyReady(),
                onEmergency = { code ->
                    if (!store.checkEmergencyCode(code)) {
                        notice = "That code is not right."
                        false
                    } else {
                        enforcer.confirmUnlock(force = true)
                        notice = "Applied with your emergency code."
                        pending = store.pendingUnlock()
                        rules = store.rules()
                        true
                    }
                },
                onConfirm = {
                    if (enforcer.confirmUnlock()) {
                        notice = null
                        pending = store.pendingUnlock()
                        rules = store.rules()
                    } else {
                        notice = "That request has not matured yet."
                    }
                },
                onCancel = {
                    store.setPendingUnlock(null)
                    pending = null
                    notice = null
                }
            )
        }

        notice?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = Focus.Secondary, fontSize = Focus.MetaSize, lineHeight = 20.sp)
        }

        Spacer(Modifier.height(24.dp))

        NavLink("choose apps") {
            context.startActivity(Intent(context, AppPickerActivity::class.java))
        }
        NavLink("sites and in-app sections") {
            context.startActivity(Intent(context, BlocklistActivity::class.java))
        }
        NavLink("daily agenda") {
            context.startActivity(Intent(context, TodoActivity::class.java))
        }
        NavLink("daily reading") {
            context.startActivity(Intent(context, ReadingActivity::class.java))
        }
        NavLink("your line") {
            context.startActivity(Intent(context, AppearanceActivity::class.java))
        }
        NavLink("setup and permissions") {
            context.startActivity(Intent(context, SetupActivity::class.java))
        }

        Spacer(Modifier.height(28.dp))

        GateLengthControl(store)

        Spacer(Modifier.height(28.dp))

        EmergencyCodeSection(store)

        Spacer(Modifier.height(28.dp))

        ApiKeyField(store)

        Spacer(Modifier.height(28.dp))

        val active = rules.filter { it.type != RestrictionType.NONE }
        if (active.isEmpty()) {
            Text(
                "No apps are restricted yet. Add one to begin.",
                color = Focus.Tertiary,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        } else {
            Text("current rules", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(10.dp))
            active.forEach { rule ->
                RuleRow(rule, enforcer.labelOf(rule.packageName)) {
                    context.startActivity(Intent(context, AppPickerActivity::class.java))
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PendingUnlockCard(
    pending: PendingUnlock,
    label: String,
    emergencyArmed: Boolean,
    onEmergency: (String) -> Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val remainingMs = (pending.readyAtMs() - System.currentTimeMillis()).coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60
    val ready = remainingMs <= 0L

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.Surface)
            .padding(20.dp)
    ) {
        Text("unlock requested", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Text(label, color = Focus.Primary, fontSize = 16.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            when (pending.kind) {
                UnlockKind.APP -> when (pending.newType) {
                    RestrictionType.NONE -> "would become unrestricted"
                    RestrictionType.TIME_LIMIT -> "would become ${pending.newLimitMinutes} minutes a day"
                    RestrictionType.FULL_BLOCK -> "would stay blocked"
                }
                UnlockKind.DOMAIN -> "would be removed from the blocklist"
                UnlockKind.SOCIAL -> "would stop counting as social media"
            },
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (ready) "The waiting period is over. Confirm to apply it."
            else "Takes effect in ${hours}h ${minutes}m. You will be asked to confirm before it applies.",
            color = Focus.Secondary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ready) {
                PressableLabel("confirm", emphasis = true, onClick = onConfirm)
                Spacer(Modifier.width(10.dp))
            }
            // Backing out of a relaxation is not itself a relaxation, so this
            // is always allowed and takes effect immediately.
            PressableLabel("cancel request", emphasis = false, onClick = onCancel)
        }

        // Only offered once the code has been armed for 24 hours, and only
        // while the request is still waiting — after that, confirm is enough.
        if (emergencyArmed && !ready) {
            var code by remember { mutableStateOf("") }
            var open by remember { mutableStateOf(false) }

            Spacer(Modifier.height(14.dp))
            if (!open) {
                PressableLabel("use emergency code", emphasis = false) { open = true }
            } else {
                Text(
                    "This applies the request now, skipping the remaining wait.",
                    color = Focus.Tertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(10.dp))
                CodeField(value = code, placeholder = "code", onValueChange = { code = it })
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PressableLabel("apply now", emphasis = true) {
                        if (onEmergency(code)) code = ""
                    }
                    Spacer(Modifier.width(8.dp))
                    PressableLabel("never mind", emphasis = false) { open = false; code = "" }
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: AppRule, label: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(Focus.Surface)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Text(label, color = Focus.Primary, fontSize = 16.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            when (rule.type) {
                RestrictionType.FULL_BLOCK -> "blocked entirely"
                RestrictionType.TIME_LIMIT -> "${rule.dailyLimitMinutes} minutes a day"
                RestrictionType.NONE -> "no restriction"
            },
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize
        )
    }
}

/** A full-width row that opens another screen. */
@Composable
private fun NavLink(label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(Focus.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(label, color = Focus.Primary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("›", color = Focus.Ghost, fontSize = 18.sp)
    }
}

/**
 * How many problems the gate asks for.
 *
 * Both directions take effect immediately, and that is defensible: you had to
 * pass the current, longer gate to reach this screen at all, so lowering it
 * has already cost you the price you set.
 */
@Composable
private fun GateLengthControl(store: PolicyStore) {
    var count by remember { mutableStateOf(store.gateProblemCount()) }

    fun set(next: Int) {
        val clamped = next.coerceIn(1, PolicyStore.MAX_GATE_PROBLEMS)
        count = clamped
        store.setGateProblemCount(clamped)
    }

    Column {
        Text("problems to enter settings", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "−",
                color = if (count > 1) Focus.Primary else Focus.Ghost,
                fontSize = 20.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable { set(count - 1) }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            )
            Text(
                count.toString(),
                color = Focus.Primary,
                fontSize = 22.sp,
                modifier = Modifier.padding(horizontal = 22.dp)
            )
            Text(
                "+",
                color = if (count < PolicyStore.MAX_GATE_PROBLEMS) Focus.Primary else Focus.Ghost,
                fontSize = 20.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable { set(count + 1) }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Drawn from more than ${"%,d".format(ChallengeGenerator.VERIFIED_MIN_VARIANTS)} " +
                "distinct problems across trigonometry, algebra, sequences, number, " +
                "geometry, statistics and logic. None repeats within a sitting.",
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
    }
}

/**
 * The optional way past a 24-hour wait.
 *
 * It arms on the same 24-hour delay, and that is the entire point: a code you
 * could set at the moment you wanted to bypass something would not be an
 * emergency key, it would be a cancel button on the whole app.
 */
@Composable
private fun EmergencyCodeSection(store: PolicyStore) {
    var isSet by remember { mutableStateOf(store.emergencyCodeSet()) }
    var readyAt by remember { mutableStateOf(store.emergencyReadyAtMs()) }
    var draft by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }

    val now = System.currentTimeMillis()
    val ready = isSet && now >= readyAt
    val waitMs = (readyAt - now).coerceAtLeast(0)

    Column {
        Text("emergency code", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            if (!isSet) {
                "Off. With a code set, you can apply a pending unlock immediately " +
                    "instead of waiting out the 24 hours. It arms 24 hours after you " +
                    "set it, so it is there for a real emergency but useless as a way " +
                    "around the wait you just started."
            } else if (!ready) {
                "Set, but not armed yet. Ready in ${TimeUnit.MILLISECONDS.toHours(waitMs)}h " +
                    "${TimeUnit.MILLISECONDS.toMinutes(waitMs) % 60}m."
            } else {
                "Armed. Enter it on a pending request to apply that request at once."
            },
            color = Focus.Tertiary,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        note?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Focus.Secondary, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(12.dp))

        CodeField(
            value = draft,
            placeholder = if (isSet) "new code" else "choose a code",
            onValueChange = { draft = it }
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            PressableLabel(if (isSet) "replace code" else "set code", emphasis = true) {
                if (draft.length < 4) {
                    note = "Use at least 4 characters."
                } else {
                    store.setEmergencyCode(draft)
                    draft = ""
                    isSet = true
                    readyAt = store.emergencyReadyAtMs()
                    note = "Saved. It arms in 24 hours."
                }
            }
            if (isSet) {
                Spacer(Modifier.width(8.dp))
                // Giving up the escape hatch is a tightening, so it is instant.
                PressableLabel("remove", emphasis = false) {
                    store.clearEmergencyCode()
                    isSet = false
                    readyAt = 0L
                    draft = ""
                    note = "Removed."
                }
            }
        }
    }
}

@Composable
private fun CodeField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = Focus.Primary, fontSize = 16.sp),
        cursorBrush = SolidColor(Focus.Secondary),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Focus.RadiusField))
                    .background(Focus.Surface)
                    .padding(horizontal = 18.dp, vertical = 15.dp)
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = Focus.Tertiary, fontSize = 16.sp)
                }
                inner()
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

/** The key is stored on-device only and is never shown back in full. */
@Composable
private fun ApiKeyField(store: PolicyStore) {
    var value by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(store.apiKeyFingerprint()) }

    Column {
        Text("anthropic api key", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        saved?.let {
            Text(it, color = Focus.Ghost, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = Focus.Primary, fontSize = 14.sp),
            cursorBrush = SolidColor(Focus.Secondary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Focus.RadiusField))
                        .background(Focus.Surface)
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                ) {
                    if (value.isEmpty()) {
                        Text("sk-ant-...", color = Focus.Tertiary, fontSize = 14.sp)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        PressableLabel("save key", emphasis = false) {
            if (value.isNotBlank()) {
                store.saveApiKey(value)
                saved = store.apiKeyFingerprint()
                value = ""
            }
        }
    }
}

/** A text button with a soft pressed surface instead of a ripple. */
@Composable
private fun PressableLabel(text: String, emphasis: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    Text(
        text = text,
        color = if (emphasis) Focus.Primary else Focus.Secondary,
        fontSize = 15.sp,
        letterSpacing = Focus.Tracking,
        modifier = Modifier
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(if (pressed) Focus.SurfacePressed else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
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
