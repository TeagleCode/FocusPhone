package com.teaglecode.focusphone

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.teaglecode.focusphone.data.PolicyStore
import com.teaglecode.focusphone.data.TodoStore
import com.teaglecode.focusphone.policy.Enforcer
import com.teaglecode.focusphone.policy.FocusGuardService
import com.teaglecode.focusphone.policy.SiteBlockerVpnService
import com.teaglecode.focusphone.ui.Focus

/**
 * First run, and reachable from settings afterwards.
 *
 * Every row reports live status rather than assuming a step succeeded, because
 * the two that matter most — device owner and usage access — cannot be granted
 * from inside the app and fail silently when missing.
 */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SetupScreen() }
    }
}

private data class Step(
    val title: String,
    val detail: String,
    val status: String,
    val done: Boolean,
    val optional: Boolean = false,
    val action: (() -> Unit)? = null,
    val actionLabel: String = "open"
)

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val store = remember { PolicyStore(context) }
    val enforcer = remember { Enforcer(context) }

    // Every step is granted in system settings, so the state is only ever
    // correct just after coming back from there.
    var probe by remember { mutableStateOf(0) }
    ObserveResume { probe++ }

    // Google requires an accessibility app to disclose what the service reads
    // before the user is sent to enable it, in the app itself and not only in
    // the privacy policy. Routing the step through this screen also means the
    // person switching it on has actually been told what it can see.
    var showDisclosure by remember { mutableStateOf(false) }

    val steps = remember(probe) {
        buildSteps(context, store, enforcer, onAccessibility = { showDisclosure = true })
    }

    if (showDisclosure) {
        AccessibilityDisclosure(
            onDecline = { showDisclosure = false },
            onAccept = {
                showDisclosure = false
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "setup",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Focus needs a few permissions before it can enforce anything. " +
                "The first two are the ones that matter.",
            color = Focus.Tertiary,
            fontSize = Focus.MetaSize,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(24.dp))

        steps.forEach { step ->
            StepRow(step)
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(28.dp))

        HonestConstraints()

        Spacer(Modifier.height(24.dp))

        Text(
            "done for now",
            color = Focus.Primary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.Surface)
                .clickable {
                    store.markSetupComplete()
                    (context as? ComponentActivity)?.finish()
                }
                .padding(horizontal = 24.dp, vertical = 14.dp)
        )

        Spacer(Modifier.height(48.dp))
    }
}

private fun buildSteps(
    context: Context,
    store: PolicyStore,
    enforcer: Enforcer,
    onAccessibility: () -> Unit
): List<Step> {
    val isHome = isDefaultLauncher(context)
    val isOwner = enforcer.isDeviceOwner()
    val hasUsage = enforcer.hasUsageAccess()
    val guardOn = FocusGuardService.isEnabled(context)
    val vpnReady = BuildConfig.SITE_FILTER && VpnService.prepare(context) == null
    val key = store.apiKeyFingerprint()

    return listOfNotNull(
        Step(
            title = "set as home screen",
            detail = "Press home and choose Focus, then Always. This also matters for " +
                "blocking: closing a restricted app means sending you home, and home " +
                "is where the explanation appears.",
            status = if (isHome) "active" else "not the home screen",
            done = isHome,
            action = { context.startActivity(homeSettingsIntent(context)) }
        ),
        Step(
            title = "accessibility service",
            detail = "This is what actually blocks. It notices a restricted app coming " +
                "to the front and closes it immediately, counts the minutes you spend " +
                "in time-limited apps, and shuts short-form feeds like Reels and Shorts.\n\n" +
                "It watches only the apps you have restricted or flagged, and nothing " +
                "else — the system enforces that list, not the app.\n\n" +
                "If the toggle is greyed out, Android has restricted it because Focus " +
                "was installed from a file. Open app info for Focus, tap the three-dot " +
                "menu, and choose Allow restricted settings.",
            status = if (guardOn) "enabled" else "off — nothing is being blocked",
            done = guardOn,
            action = onAccessibility
        ),
        Step(
            title = "usage access",
            detail = "Focus keeps its own record of time spent, so limits work without " +
                "this. Granting it makes them more accurate: it lets Focus see when you " +
                "switch to an app it does not watch, and recover time counted while the " +
                "accessibility service was off.",
            status = if (hasUsage) "granted" else "not granted",
            done = hasUsage,
            action = {
                context.startActivity(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),
        Step(
            title = "device owner",
            detail = "Optional, and much stronger: the system itself refuses to open a " +
                "suspended app, with no window where it flashes up first.\n\n" +
                "It cannot be granted from inside the app. On a device with no accounts " +
                "configured, with USB debugging on, run:\n\n" +
                "adb shell dpm set-device-owner " +
                "com.teaglecode.focusphone/.policy.FocusDeviceAdminReceiver\n\n" +
                "If it fails saying accounts already exist, every account must be " +
                "removed first — in practice that means a factory reset. Accounts can " +
                "be added again afterwards.",
            status = if (isOwner) "active" else "not provisioned",
            done = isOwner,
            optional = true
        ),
        // Site blocking is absent from the Play build, so the step that asks
        // for VPN consent has to go with it — a checklist entry for a feature
        // that is not there is worse than no entry at all.
        if (!BuildConfig.SITE_FILTER) null else Step(
            title = "vpn consent",
            detail = "Only needed for site blocking. Android allows one VPN at a " +
                "time, so this cannot run alongside a commercial VPN.",
            status = when {
                SiteBlockerVpnService.isRunning() -> "running"
                vpnReady -> "allowed"
                else -> "not allowed yet"
            },
            done = vpnReady,
            optional = true,
            action = {
                VpnService.prepare(context)?.let { context.startActivity(it) }
            },
            actionLabel = "allow"
        ),
        Step(
            title = "anthropic api key",
            detail = "Only needed for reading quizzes. Stored on this device and " +
                "never shown back in full.",
            status = key ?: "not set",
            done = key != null,
            optional = true,
            action = {
                context.startActivity(Intent(context, SettingsActivity::class.java))
            },
            actionLabel = "settings"
        ),
        Step(
            title = "choose apps to restrict",
            detail = "Apps with no rule are never touched.",
            status = "${store.rules().count { it.type != com.teaglecode.focusphone.data.RestrictionType.NONE }} with a rule",
            done = store.rules().any { it.type != com.teaglecode.focusphone.data.RestrictionType.NONE },
            action = {
                context.startActivity(Intent(context, AppPickerActivity::class.java))
            },
            actionLabel = "choose"
        ),
        Step(
            title = "daily agenda",
            detail = "Tasks appear on the home screen. Leave any of a day's tasks " +
                "unfinished and the apps flagged as social are locked for the whole " +
                "of the next day.",
            status = "${TodoStore(context).agenda(TodoStore.todayKey()).size} today",
            done = TodoStore(context).agenda(TodoStore.todayKey()).isNotEmpty(),
            optional = true,
            action = { context.startActivity(Intent(context, TodoActivity::class.java)) },
            actionLabel = "edit"
        )
    )
}

@Composable
private fun StepRow(step: Step) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Focus.RadiusRow))
            .background(Focus.Surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                step.title,
                color = Focus.Primary,
                fontSize = 16.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                if (step.done) "ok" else if (step.optional) "optional" else "needed",
                color = if (step.done) Focus.Secondary else Focus.Primary,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(step.status, color = Focus.Tertiary, fontSize = Focus.MetaSize)

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Text(
                step.detail,
                color = Focus.Secondary,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
            step.action?.let { act ->
                Spacer(Modifier.height(14.dp))
                Text(
                    step.actionLabel,
                    color = Focus.Primary,
                    fontSize = 15.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Focus.RadiusRow))
                        .background(Focus.SurfacePressed)
                        .clickable(onClick = act)
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }
}

/** Spec §10: say plainly what this app cannot do. */
@Composable
private fun HonestConstraints() {
    Text("what this does not do", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(10.dp))
    listOf(
        "Accessibility blocking is friction, not a prison: turning the service off " +
            "in system settings disables it in two taps. That is deliberate — a tool " +
            "you cannot escape is a tool you cannot trust.",
        "A restricted app is closed a fraction of a second after it opens, so you " +
            "will see it flash up. Only device owner can stop it launching at all.",
        "Time is counted while the guard is running. Turn it off, and the minutes " +
            "spent meanwhile are only recovered if usage access is granted.",
        "Section blocking relies on hints that break when an app redesigns its feed.",
        "Some banking apps refuse to run on a device with a device owner. If that " +
            "happens, drop device owner and rely on accessibility blocking, which is softer."
    ).forEach {
        Text(
            "— $it",
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/**
 * Shown before the user is handed to system settings to switch the guard on.
 *
 * An accessibility service can read the screen, which is a serious thing to
 * hand an app, and Google requires the disclosure to be made in the app rather
 * than buried in a privacy policy. It is written to be read by the person
 * granting it: what it sees, what it cannot see, and where the data goes.
 */
@Composable
private fun AccessibilityDisclosure(onDecline: () -> Unit, onAccept: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))
        Text(
            "before you turn this on",
            color = Focus.Primary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = Focus.Tracking
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Focus blocks apps using Android's accessibility service. Accessibility " +
                "services can read screen content, so it matters exactly what this " +
                "one does with it.",
            color = Focus.Secondary,
            fontSize = 15.sp,
            lineHeight = 23.sp
        )

        Spacer(Modifier.height(26.dp))
        Text("it does three things", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        listOf(
            "Notices when an app you have restricted comes to the front, and sends " +
                "you back to the home screen.",
            "Counts the minutes you spend in apps you have given a time limit.",
            "Reads the identifiers of on-screen elements in apps where you blocked a " +
                "section, so it can tell a short-form video feed from the rest of the app."
        ).forEach {
            Text(
                "— $it",
                color = Focus.Secondary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                modifier = Modifier.padding(bottom = 9.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("what it cannot see", color = Focus.Tertiary, fontSize = 12.sp, letterSpacing = 1.2.sp)
        Spacer(Modifier.height(10.dp))
        Text(
            "It receives events only from the apps you have selected. That list is " +
                "enforced by Android, not by Focus, so every other app on this phone " +
                "— your banking, your messages, your email — is invisible to it.\n\n" +
                "Screen content is checked in memory to decide whether to block, then " +
                "discarded. It is never written to storage, never logged, and never " +
                "sent off this device.\n\n" +
                "Focus has no account, no analytics, no ads and no trackers. Your rules " +
                "and your times stay on the phone.",
            color = Focus.Secondary,
            fontSize = 13.sp,
            lineHeight = 20.sp
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "You can switch the service off again at any time in " +
                "Settings → Accessibility, and blocking stops immediately.",
            color = Focus.Ghost,
            fontSize = 12.sp,
            lineHeight = 19.sp
        )

        Spacer(Modifier.height(30.dp))
        Text(
            "i understand — open settings",
            color = Focus.Primary,
            fontSize = 16.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .background(Focus.Surface)
                .clickable(onClick = onAccept)
                .padding(horizontal = 24.dp, vertical = 14.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "not now",
            color = Focus.Secondary,
            fontSize = 15.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(Focus.RadiusRow))
                .clickable(onClick = onDecline)
                .padding(horizontal = 24.dp, vertical = 14.dp)
        )
        Spacer(Modifier.height(48.dp))
    }
}

/** Re-reads permission state whenever the screen comes back to the foreground. */
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

private fun isDefaultLauncher(context: Context): Boolean {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = context.packageManager.resolveActivity(intent, 0)
    return resolved?.activityInfo?.packageName == context.packageName
}

/**
 * The role chooser is the direct route on API 29+; the home-settings screen is
 * the fallback when the role is unavailable.
 */
private fun homeSettingsIntent(context: Context): Intent {
    val roleManager = context.getSystemService(RoleManager::class.java)
    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
