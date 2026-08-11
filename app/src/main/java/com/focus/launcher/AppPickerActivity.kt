package com.focus.launcher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import com.focus.launcher.data.AppRule
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.RestrictionType
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.ui.Focus

/**
 * Choose which installed apps carry a rule. Reached only from settings, which
 * is itself behind the challenge gate, so no additional gate is needed here.
 *
 * Adding or tightening a restriction applies immediately. Loosening one does
 * not: that goes through the delayed-unlock flow in settings.
 */
class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppPickerScreen() }
    }
}

private data class InstalledApp(val label: String, val pkg: String)

@Composable
private fun AppPickerScreen() {
    val context = LocalContext.current
    val store = remember { PolicyStore(context) }
    var query by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(store.rules()) }
    var editing by remember { mutableStateOf<InstalledApp?>(null) }

    val installed = remember {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(main, 0)
            .map { InstalledApp(it.loadLabel(context.packageManager).toString(), it.activityInfo.packageName) }
            .filter { it.pkg != context.packageName }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    val shown = remember(query, installed) {
        if (query.isBlank()) installed
        else installed.filter { it.label.contains(query, ignoreCase = true) }
    }

    editing?.let { app ->
        RuleEditor(
            app = app,
            existing = rules.firstOrNull { it.packageName == app.pkg },
            onDismiss = { editing = null },
            onSave = { rule ->
                store.upsertRule(rule)
                rules = store.rules()
                Enforcer(context).apply()
                editing = null
            }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "choose apps",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(20.dp))

        BasicTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            textStyle = TextStyle(color = Focus.Primary, fontSize = Focus.SearchSize),
            cursorBrush = SolidColor(Focus.Secondary),
            decorationBox = { inner ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Focus.RadiusField))
                        .background(Focus.Surface)
                        .padding(horizontal = 18.dp, vertical = 15.dp)
                ) {
                    if (query.isEmpty()) Text("filter", color = Focus.Tertiary, fontSize = Focus.SearchSize)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn {
            items(shown) { app ->
                val rule = rules.firstOrNull { it.packageName == app.pkg }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(Focus.RadiusRow))
                        .background(if (rule != null) Focus.Surface else Color.Transparent)
                        .clickable { editing = app }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        app.label.lowercase(),
                        color = Focus.Primary,
                        fontSize = Focus.AppSize,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when (rule?.type) {
                            RestrictionType.FULL_BLOCK -> "blocked"
                            RestrictionType.TIME_LIMIT -> "${rule.dailyLimitMinutes}m"
                            else -> ""
                        },
                        color = Focus.Tertiary,
                        fontSize = Focus.MetaSize
                    )
                }
            }
        }
    }
}

@Composable
private fun RuleEditor(
    app: InstalledApp,
    existing: AppRule?,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit
) {
    var type by remember { mutableStateOf(existing?.type ?: RestrictionType.TIME_LIMIT) }
    var minutes by remember { mutableStateOf((existing?.dailyLimitMinutes ?: 30).toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(app.label, color = Focus.Primary, fontSize = 24.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(4.dp))
        Text(app.pkg, color = Focus.Ghost, fontSize = 12.sp)

        Spacer(Modifier.height(32.dp))

        listOf(
            RestrictionType.NONE to "no restriction",
            RestrictionType.TIME_LIMIT to "daily time limit",
            RestrictionType.FULL_BLOCK to "blocked entirely"
        ).forEach { (value, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(if (type == value) Focus.SurfacePressed else Focus.Surface)
                    .clickable { type = value }
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                Text(
                    label,
                    color = if (type == value) Focus.Primary else Focus.Secondary,
                    fontSize = 16.sp
                )
            }
        }

        if (type == RestrictionType.TIME_LIMIT) {
            Spacer(Modifier.height(20.dp))
            Text("minutes per day", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = minutes,
                onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                singleLine = true,
                textStyle = TextStyle(color = Focus.Primary, fontSize = 22.sp),
                cursorBrush = SolidColor(Focus.Secondary),
                decorationBox = { inner ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Focus.RadiusField))
                            .background(Focus.Surface)
                            .padding(horizontal = 18.dp, vertical = 15.dp)
                    ) { inner() }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.height(28.dp))

        Row {
            Text(
                "save",
                color = Focus.Primary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable {
                        onSave(
                            AppRule(
                                packageName = app.pkg,
                                type = type,
                                dailyLimitMinutes = minutes.toIntOrNull() ?: 30
                            )
                        )
                    }
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "back",
                color = Focus.Secondary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}
