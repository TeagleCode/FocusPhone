package com.focus.launcher.policy

import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import com.focus.launcher.data.AppRule
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.RestrictionType
import com.focus.launcher.data.UnlockKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Why enforcement is not currently possible, or null when it is. */
enum class EnforcementBlocker { NOT_DEVICE_OWNER, NO_USAGE_ACCESS }

/** What the launcher should display next to an app. */
sealed interface AppState {
    data object Unrestricted : AppState
    data object Blocked : AppState
    data class Remaining(val minutes: Int) : AppState
}

/**
 * Decides which packages should currently be suspended, and applies that
 * decision through DevicePolicyManager.
 *
 * Suspension (as opposed to hiding) is deliberate: the app stays visible and
 * Android shows a system dialog explaining it is unavailable, which is a
 * clearer signal than an app that silently vanished.
 */
class Enforcer(private val context: Context) {

    private val store = PolicyStore(context)
    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, FocusDeviceAdminReceiver::class.java)
    private val usage =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /**
     * Usage access is a special-access permission that cannot be requested
     * from inside the app, and querying without it silently returns nothing —
     * which would look exactly like "you used no apps today".
     */
    fun hasUsageAccess(): Boolean {
        val start = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        return usage.queryAndAggregateUsageStats(start, System.currentTimeMillis()).isNotEmpty()
    }

    fun blocker(): EnforcementBlocker? = when {
        !isDeviceOwner() -> EnforcementBlocker.NOT_DEVICE_OWNER
        !hasUsageAccess() -> EnforcementBlocker.NO_USAGE_ACCESS
        else -> null
    }

    /** Foreground milliseconds used by [pkg] since local midnight. */
    fun usedTodayMs(pkg: String): Long = usageTodayByPackage()[pkg] ?: 0L

    /**
     * One aggregate query for the whole day, so a screen listing many apps
     * does not re-query per row.
     */
    fun usageTodayByPackage(): Map<String, Long> {
        val stats = usage.queryAndAggregateUsageStats(startOfDayMs(), System.currentTimeMillis())
        return stats.mapValues { it.value.totalTimeInForeground }
    }

    /**
     * Full evaluation: returns the set of packages that must be suspended
     * right now, given rules, time used and the reading penalty.
     */
    fun packagesToSuspend(usageMs: Map<String, Long> = usageTodayByPackage()): Set<String> {
        val today = dateKey(Date())
        val penaltyActive = store.penaltyDate() == today

        return store.rules()
            .filter { shouldSuspend(it, penaltyActive, usageMs[it.packageName] ?: 0L) }
            .map { it.packageName }
            .toSet()
    }

    private fun shouldSuspend(rule: AppRule, penaltyActive: Boolean, usedMs: Long): Boolean =
        when (rule.type) {
            RestrictionType.NONE -> false
            RestrictionType.FULL_BLOCK -> true
            RestrictionType.TIME_LIMIT ->
                // Under penalty, restricted apps get no allowance at all.
                penaltyActive || usedMs >= rule.dailyLimitMinutes * 60_000L
        }

    /**
     * Applies the current decision.
     *
     * Release is driven by what was previously suspended rather than by the
     * current rule list, so deleting a rule outright still frees its package.
     * Relying on the rule list would strand it suspended with nothing left to
     * release it.
     */
    fun apply(): Result<Unit> = runCatching {
        if (!isDeviceOwner()) error("Focus is not the device owner")

        val target = packagesToSuspend()
        val release = store.suspendedPackages() - target

        if (target.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, target.toTypedArray(), true)
        }
        if (release.isNotEmpty()) {
            // A package uninstalled while suspended would throw here and abort
            // the whole pass, so release one at a time.
            release.forEach { pkg ->
                runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
            }
        }
        store.saveSuspendedPackages(target)
    }

    /**
     * Applies a matured, user-confirmed unlock and clears it. Returns false if
     * the request is not yet ready, so the caller can refuse loudly.
     */
    fun confirmUnlock(): Boolean {
        val pending = store.pendingUnlock() ?: return false
        if (!pending.isReady()) return false

        when (pending.kind) {
            UnlockKind.APP ->
                store.upsertRule(
                    AppRule(
                        packageName = pending.target,
                        type = pending.newType,
                        dailyLimitMinutes = pending.newLimitMinutes
                    )
                )
            UnlockKind.DOMAIN ->
                store.saveBlockedDomains(store.blockedDomains() - pending.target)
        }
        store.setPendingUnlock(null)
        apply()
        return true
    }

    /** What the home screen shows beside an app. */
    fun stateOf(pkg: String, usageMs: Map<String, Long>): AppState {
        val rule = store.ruleFor(pkg) ?: return AppState.Unrestricted
        val penaltyActive = store.penaltyDate() == dateKey(Date())
        return when (rule.type) {
            RestrictionType.NONE -> AppState.Unrestricted
            RestrictionType.FULL_BLOCK -> AppState.Blocked
            RestrictionType.TIME_LIMIT -> {
                if (penaltyActive) return AppState.Blocked
                val usedMin = ((usageMs[pkg] ?: 0L) / 60_000L).toInt()
                val left = (rule.dailyLimitMinutes - usedMin).coerceAtLeast(0)
                if (left == 0) AppState.Blocked else AppState.Remaining(left)
            }
        }
    }

    /** Human-readable label for a package, falling back to the package name. */
    fun labelOf(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        fun dateKey(d: Date): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

        fun startOfDayMs(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
