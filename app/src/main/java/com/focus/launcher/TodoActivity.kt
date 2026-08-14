package com.focus.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.TodoStore
import com.focus.launcher.data.TodoTask
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.ui.Focus

/**
 * The daily agenda editor.
 *
 * Two kinds of item: a daily task that reappears every morning, and a one-off
 * for today only. Both are ordinary text — the point is that the list is
 * short enough to actually finish, because failing to finish it costs
 * tomorrow's social apps.
 */
class TodoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TodoScreen() }
    }
}

@Composable
private fun TodoScreen() {
    val context = LocalContext.current
    val store = remember { TodoStore(context) }
    val policy = remember { PolicyStore(context) }
    val enforcer = remember { Enforcer(context) }

    var version by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf(true) }

    val today = TodoStore.todayKey()
    val tasks = remember(version) { store.tasks() }
    val daily = tasks.filter { it.recurring }
    val oneOff = tasks.filter { !it.recurring && it.createdDate == today }
    val locked = remember(version) { store.socialLockedToday() }
    val missed = remember(version) { store.missedYesterday() }
    val socialLabels = remember(version) {
        policy.socialPackages().map { enforcer.labelOf(it) }.sorted()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "daily agenda",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Leave any of today's items unfinished and the apps flagged as social " +
                "are locked for the whole of tomorrow.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        if (locked) {
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Focus.RadiusField))
                    .background(Focus.SurfacePressed)
                    .padding(18.dp)
            ) {
                Text("social apps are locked today", color = Focus.Primary, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Left unfinished yesterday: " + missed.joinToString(", ") { it.text },
                    color = Focus.Tertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "It lifts by itself at midnight. Finishing yesterday's list now " +
                        "will not lift it — that is the point.",
                    color = Focus.Ghost,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // ---- Add ---------------------------------------------------------

        BasicTextField(
            value = draft,
            onValueChange = { draft = it },
            textStyle = TextStyle(color = Focus.Primary, fontSize = Focus.SearchSize),
            cursorBrush = SolidColor(Focus.Secondary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Focus.RadiusField))
                        .background(Focus.Surface)
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    if (draft.isEmpty()) {
                        Text("new task", color = Focus.Tertiary, fontSize = Focus.SearchSize)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Toggle("every day", recurring) { recurring = true }
            Spacer(Modifier.width(8.dp))
            Toggle("today only", !recurring) { recurring = false }
            Spacer(Modifier.weight(1f))
            Text(
                "add",
                color = if (draft.isBlank()) Focus.Ghost else Focus.Primary,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable {
                        if (draft.isNotBlank()) {
                            store.add(draft, recurring)
                            draft = ""
                            version++
                        }
                    }
                    .padding(horizontal = 22.dp, vertical = 11.dp)
            )
        }

        Spacer(Modifier.height(28.dp))

        // ---- Lists -------------------------------------------------------

        TaskGroup(
            title = "every day",
            empty = "no daily tasks",
            tasks = daily,
            onRemove = { store.remove(it); version++ }
        )

        Spacer(Modifier.height(20.dp))

        TaskGroup(
            title = "today only",
            empty = "nothing extra today",
            tasks = oneOff,
            onRemove = { store.remove(it); version++ }
        )

        Spacer(Modifier.height(28.dp))

        Text("what counts as social", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (socialLabels.isEmpty()) {
                "No apps are flagged yet, so the penalty has nothing to lock. " +
                    "Flag them in choose apps."
            } else {
                socialLabels.joinToString(", ")
            },
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Only flagged apps are ever affected. Phone, messages, maps and banking " +
                "are never touched, whatever the list says.",
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 18.sp
        )

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun TaskGroup(
    title: String,
    empty: String,
    tasks: List<TodoTask>,
    onRemove: (String) -> Unit
) {
    Text(title, color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(10.dp))
    if (tasks.isEmpty()) {
        Text(empty, color = Focus.Ghost, fontSize = 14.sp)
        return
    }
    tasks.forEach { task ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.Surface)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(task.text, color = Focus.Primary, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                "remove",
                color = Focus.Ghost,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable { onRemove(task.id) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun Toggle(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (selected) Focus.Primary else Focus.Secondary,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(if (selected) Focus.SurfacePressed else Focus.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
