package com.focus.launcher

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
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.policy.Enforcer
import com.focus.launcher.policy.SectionBlockerService
import com.focus.launcher.policy.SiteBlockerVpnService
import com.focus.launcher.ui.Focus

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

    val steps = remember(probe) {
        buildSteps(context, store, enforcer)
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
    enforcer: Enforcer
): List<Step> {
    val isHome = isDefaultLauncher(context)
    val isOwner = enforcer.isDeviceOwner()
    val hasUsage = enforcer.hasUsageAccess()
    val a11yOn = SectionBlockerService.isEnabled(context)
    val vpnReady = VpnService.prepare(context) == null
    val key = store.apiKeyFingerprint()

    return listOf(
        Step(
            title = "set as home screen",
            detail = "Press home and choose Focus, then Always.",
            status = if (isHome) "active" else "not the home screen",
            done = isHome,
            action = { context.startActivity(homeSettingsIntent(context)) }
        ),
        Step(
            title = "usage access",
            detail = "Without this, time limits cannot be measured and silently do nothing.",
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
            detail = "Cannot be granted from inside the app. On a device with no " +
                "accounts configured, with USB debugging on, run:\n\n" +
                "adb shell dpm set-device-owner " +
                "com.focus.launcher/.policy.FocusDeviceAdminReceiver\n\n" +
                "If it fails saying accounts already exist, remove every account " +
                "and retry. Accounts can be added again afterwards.",
            status = if (isOwner) "active" else "not provisioned — nothing can be blocked",
            done = isOwner
        ),
        Step(
            title = "accessibility service",
            detail = "Only needed to close in-app sections such as Reels or Shorts. " +
                "It watches only the apps you list, and nothing else.",
            status = if (a11yOn) "enabled" else "off",
            done = a11yOn,
            optional = true,
            action = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        ),
        Step(
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
            status = "${store.rules().count { it.type != com.focus.launcher.data.RestrictionType.NONE }} with a rule",
            done = store.rules().any { it.type != com.focus.launcher.data.RestrictionType.NONE },
            action = {
                context.startActivity(Intent(context, AppPickerActivity::class.java))
            },
            actionLabel = "choose"
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
        "Device owner can be removed by a factory reset from recovery. " +
            "This is friction, not a prison.",
        "Restrictions are re-checked every 15 minutes, so a time limit can " +
            "overrun by up to that long.",
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
