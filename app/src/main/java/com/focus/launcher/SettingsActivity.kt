package com.focus.launcher

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background as bg
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.AppRule
import com.focus.launcher.data.PendingUnlock
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.RestrictionType
import com.focus.launcher.gate.ChallengeGenerator
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
    var unlocked by remember { mutableStateOf(false) }
    if (unlocked) SettingsScreen() else GateScreen { unlocked = true }
}

/**
 * Every entry into settings requires a fresh challenge, and a wrong answer
 * replaces the question rather than letting it be retried.
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
                    challenge = ChallengeGenerator.next()
                }
            }
        )

        if (showHint && challenge.hint != null) {
            Spacer(Modifier.height(22.dp))
            Text(challenge.hint!!, color = Focus.Ghost, fontSize = Focus.MetaSize)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { PolicyStore(context) }
    val rules by remember { mutableStateOf(store.rules()) }
    var pending by remember { mutableStateOf(store.pendingUnlock()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Focus.Ink)
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
                onConfirm = {
                    store.setPendingUnlock(p.copy(confirmed = true))
                    pending = store.pendingUnlock()
                },
                onCancel = {
                    store.setPendingUnlock(null)
                    pending = null
                }
            )
        }

        Spacer(Modifier.height(24.dp))

        NavLink("choose apps") {
            context.startActivity(android.content.Intent(context, AppPickerActivity::class.java))
        }
        NavLink("sites and in-app sections") {
            context.startActivity(android.content.Intent(context, BlocklistActivity::class.java))
        }
        NavLink("daily reading") {
            context.startActivity(android.content.Intent(context, ReadingActivity::class.java))
        }

        Spacer(Modifier.height(20.dp))

        ApiKeyField(store)

        Spacer(Modifier.height(24.dp))

        if (rules.isEmpty()) {
            Text(
                "No apps are restricted yet. Add one to begin.",
                color = Focus.Tertiary,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }

        LazyColumn {
            items(rules) { rule ->
                RuleRow(rule) {
                    if (pending == null) {
                        val p = PendingUnlock(
                            packageName = rule.packageName,
                            requestedAtMs = System.currentTimeMillis(),
                            confirmed = false
                        )
                        store.setPendingUnlock(p)
                        pending = p
                    }
                }
            }
        }
    }
}

@Composable
private fun PendingUnlockCard(
    pending: PendingUnlock,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val remainingMs = (pending.readyAtMs() - System.currentTimeMillis()).coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(remainingMs)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs) % 60

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Focus.RadiusField))
            .background(Focus.Surface)
            .padding(20.dp)
    ) {
        Text("unlock requested", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Text(pending.packageName, color = Focus.Primary, fontSize = 16.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                remainingMs > 0 ->
                    "Takes effect in ${hours}h ${minutes}m. You will be asked to confirm before it applies."
                !pending.confirmed -> "The waiting period is over. Confirm to apply it."
                else -> "Active."
            },
            color = Focus.Secondary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (remainingMs <= 0L && !pending.confirmed) {
                PressableLabel("confirm", emphasis = true, onClick = onConfirm)
                Spacer(Modifier.width(10.dp))
            }
            PressableLabel("cancel request", emphasis = false, onClick = onCancel)
        }
    }
}

@Composable
private fun RuleRow(rule: AppRule, onRequestUnlock: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(Focus.Surface)
            .padding(18.dp)
    ) {
        Text(rule.packageName, color = Focus.Primary, fontSize = 16.sp)
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
        Spacer(Modifier.height(12.dp))
        PressableLabel("request unlock", emphasis = false, onClick = onRequestUnlock)
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
        Text("\u203a", color = Focus.Ghost, fontSize = 18.sp)
    }
}

/** The key is stored on-device only and is never shown back in full. */
@Composable
private fun ApiKeyField(store: PolicyStore) {
    var value by remember { mutableStateOf(store.apiKey().orEmpty()) }
    var saved by remember { mutableStateOf(false) }

    Column {
        Text("anthropic api key", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = value,
            onValueChange = { value = it; saved = false },
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
        PressableLabel(if (saved) "saved" else "save key", emphasis = false) {
            store.saveApiKey(value)
            saved = true
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
            .background(if (pressed) Focus.SurfacePressed else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
