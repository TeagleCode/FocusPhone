package com.focus.launcher

import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.AppCatalog
import com.focus.launcher.data.AppRule
import com.focus.launcher.data.LaunchableApp
import com.focus.launcher.data.PendingUnlock
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.RestrictionType
import com.focus.launcher.data.UnlockKind
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.policy.FocusGuardService
import com.focus.launcher.ui.Focus
import kotlinx.coroutines.launch

/**
 * Choose which installed apps carry a rule, and which count as social media.
 * Reached only from settings, which is itself behind the challenge gate, so no
 * additional gate is needed here.
 *
 * Adding or tightening a restriction applies immediately. Loosening one never
 * does: it becomes a request that matures after 24 hours and then still needs
 * confirming, which is the whole point of the tool.
 */
class AppPickerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppPickerScreen() }
    }
}

@Composable
private fun AppPickerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PolicyStore(context) }

    var query by remember { mutableStateOf("") }
    var rules by remember { mutableStateOf(store.rulesByPackage()) }
    var social by remember { mutableStateOf(store.socialPackages()) }
    var editing by remember { mutableStateOf<LaunchableApp?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Only one relaxation may be outstanding, so an unnoticed leftover request
    // silently refuses every later one. It has to be visible.
    var pending by remember { mutableStateOf(store.pendingUnlock()) }

    // Loaded off the main thread; the cache means this is usually instant.
    var installed by remember { mutableStateOf(AppCatalog.snapshot().orEmpty()) }
    LaunchedEffect(Unit) {
        installed = AppCatalog.load(context)
    }

    val shown = remember(query, installed) {
        if (query.isBlank()) installed
        else {
            val needle = query.lowercase()
            installed.filter { it.lowerLabel.contains(needle) }
        }
    }

    editing?.let { app ->
        RuleEditor(
            app = app,
            existing = rules[app.packageName],
            isSocial = app.packageName in social,
            pending = pending,
            onSocialToggle = {
                notice = toggleSocial(store, context, app.packageName)
                social = store.socialPackages()
                pending = store.pendingUnlock()
                FocusGuardService.refreshScope()
            },
            onDismiss = { editing = null },
            onSave = { rule ->
                notice = applyRuleChange(store, context, rule)
                rules = store.rulesByPackage()
                pending = store.pendingUnlock()
                editing = null
                scope.launch { Enforcer(context).apply() }
            }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
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
        Spacer(Modifier.height(8.dp))
        Text(
            "Tightening applies at once. Loosening waits 24 hours and then needs confirming.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        notice?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = Focus.Secondary, fontSize = Focus.MetaSize, lineHeight = 20.sp)
        }

        pending?.let { p ->
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.SurfacePressed)
                    .clickable {
                        store.setPendingUnlock(null)
                        pending = null
                        notice = "Request cancelled. You can make a new one now."
                    }
                    .padding(horizontal = 16.dp, vertical = 13.dp)
            ) {
                Text(
                    "one request is already pending",
                    color = Focus.Primary,
                    fontSize = 13.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "\"${labelForPending(context, p)}\" — until it is confirmed or " +
                        "cancelled, no other restriction can be loosened. Tap to cancel it.",
                    color = Focus.Tertiary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        Spacer(Modifier.height(18.dp))

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
                    if (query.isEmpty()) {
                        Text("filter", color = Focus.Tertiary, fontSize = Focus.SearchSize)
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn {
            items(shown, key = { it.packageName }) { app ->
                val rule = rules[app.packageName]
                val restricted = rule != null && rule.type != RestrictionType.NONE
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(Focus.RadiusRow))
                        .background(if (restricted) Focus.Surface else Color.Transparent)
                        .clickable { notice = null; editing = app }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        app.lowerLabel,
                        color = Focus.Primary,
                        fontSize = Focus.AppSize,
                        modifier = Modifier.weight(1f)
                    )
                    if (app.packageName in social) {
                        Text("social", color = Focus.Ghost, fontSize = 11.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.width(10.dp))
                    }
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

/**
 * The one place a rule change is interpreted. Tightening is written straight
 * through; anything else is recorded as a pending unlock and has no effect
 * until it matures and is confirmed.
 *
 * Returns the message to show the user.
 */
private fun applyRuleChange(
    store: PolicyStore,
    context: Context,
    next: AppRule
): String {
    val current = store.ruleFor(next.packageName)

    if (next == current) return "No change."

    if (next.isTighterThan(current)) {
        store.upsertRule(next)
        Enforcer(context).apply()
        FocusGuardService.refreshScope()
        return "Applied."
    }

    alreadyPending(store, context)?.let { return it }

    store.setPendingUnlock(
        PendingUnlock(
            kind = UnlockKind.APP,
            target = next.packageName,
            requestedAtMs = System.currentTimeMillis(),
            newType = next.type,
            newLimitMinutes = next.dailyLimitMinutes
        )
    )
    return "Requested. It takes effect in 24 hours, and then only once you " +
        "confirm it in settings. Nothing has changed yet."
}

/**
 * Flagging an app as social takes effect at once. Unflagging does not — it is
 * a relaxation, and an instant one would be a trivial way out of the very
 * lockout the daily list is supposed to impose.
 */
private fun toggleSocial(store: PolicyStore, context: Context, pkg: String): String {
    if (pkg !in store.socialPackages()) {
        store.saveSocialPackages(store.socialPackages() + pkg)
        return "Flagged as social."
    }

    alreadyPending(store, context)?.let { return it }

    store.setPendingUnlock(
        PendingUnlock(
            kind = UnlockKind.SOCIAL,
            target = pkg,
            requestedAtMs = System.currentTimeMillis()
        )
    )
    return "Requested. Removing the social flag takes 24 hours and then needs " +
        "confirming. It is still flagged for now."
}

/** What saving the current selection would actually do. */
private data class Outcome(val headline: String, val detail: String)

private fun labelForPending(context: Context, p: PendingUnlock): String = when (p.kind) {
    UnlockKind.DOMAIN -> p.target
    else -> Enforcer(context).labelOf(p.target)
}

/** Only one relaxation may be outstanding at a time; a second is refused out loud. */
private fun alreadyPending(store: PolicyStore, context: Context): String? {
    val existing = store.pendingUnlock() ?: return null
    val what = when (existing.kind) {
        UnlockKind.DOMAIN -> existing.target
        else -> Enforcer(context).labelOf(existing.target)
    }
    return "A request for \"$what\" is already pending. Only one at a time — " +
        "cancel it in settings first."
}

@Composable
private fun RuleEditor(
    app: LaunchableApp,
    existing: AppRule?,
    isSocial: Boolean,
    pending: PendingUnlock?,
    onSocialToggle: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (AppRule) -> Unit
) {
    var type by remember { mutableStateOf(existing?.type ?: RestrictionType.TIME_LIMIT) }
    var minutes by remember { mutableStateOf((existing?.dailyLimitMinutes ?: 30).toString()) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(app.label, color = Focus.Primary, fontSize = 24.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(4.dp))
        Text(app.packageName, color = Focus.Ghost, fontSize = 12.sp)

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
                onValueChange = { minutes = it.filter(Char::isDigit).take(4) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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

        Spacer(Modifier.height(24.dp))

        // Independent of the rule: this decides what the unfinished-tasks
        // penalty is allowed to reach, and nothing else.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(if (isSocial) Focus.SurfacePressed else Focus.Surface)
                .clickable(onClick = onSocialToggle)
                .padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "counts as social media",
                    color = if (isSocial) Focus.Primary else Focus.Secondary,
                    fontSize = 16.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "locked for a day when the agenda is left unfinished",
                    color = Focus.Ghost,
                    fontSize = 12.sp
                )
            }
            Text(
                if (isSocial) "yes" else "no",
                color = if (isSocial) Focus.Primary else Focus.Ghost,
                fontSize = 13.sp
            )
        }

        val proposed = AppRule(
            packageName = app.packageName,
            type = type,
            dailyLimitMinutes = minutes.toIntOrNull() ?: 30
        )

        // Raising a time limit is a relaxation like any other, so it waits.
        // That is the design, but the old screen only whispered it, which
        // read as "the app will not let me set a bigger number".
        val outcome = when {
            proposed == existing -> Outcome("no change", "Nothing to save.")
            proposed.isTighterThan(existing) ->
                Outcome("applies now", "Making a restriction stricter is instant.")
            pending != null -> Outcome(
                "cannot request yet",
                "Another request is already pending, and only one is allowed at a " +
                    "time. Cancel it on the previous screen first."
            )
            else -> Outcome(
                "waits 24 hours",
                "This gives you more access, so it does not take effect now. It " +
                    "becomes available to confirm in 24 hours; until then the " +
                    "current rule stands."
            )
        }

        Spacer(Modifier.height(22.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.Surface)
                .padding(16.dp)
        ) {
            Text(outcome.headline, color = Focus.Primary, fontSize = 14.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            Text(outcome.detail, color = Focus.Tertiary, fontSize = 12.sp, lineHeight = 18.sp)
        }

        Spacer(Modifier.height(20.dp))

        Row {
            Text(
                "save",
                color = Focus.Primary,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(Focus.RadiusRow))
                    .background(Focus.Surface)
                    .clickable { onSave(proposed) }
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
