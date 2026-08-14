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
            "solve to continue",
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
                if (challenge.accepts(input)) onPass()
                else {
                    wrong = true
                    input = ""
                    showHint = true
                    // Exclude the failed question so it can never come back.
                    challenge = ChallengeGenerator.next(avoid = challenge)
                }
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

        Spacer(Modifier.height(20.dp))

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
