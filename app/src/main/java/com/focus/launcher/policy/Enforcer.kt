package com.focus.launcher.policy

import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import com.focus.launcher.data.AppRule
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.data.RestrictionType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    /** Foreground milliseconds used by [pkg] since local midnight. */
    fun usedTodayMs(pkg: String): Long {
        val start = startOfDayMs()
        val stats = usage.queryAndAggregateUsageStats(start, System.currentTimeMillis())
        return stats[pkg]?.totalTimeInForeground ?: 0L
    }

    /**
     * Full evaluation: returns the set of packages that must be suspended
     * right now, given rules, time used, pending unlocks and reading penalty.
     */
    fun packagesToSuspend(): Set<String> {
        val now = System.currentTimeMillis()
        val today = dateKey(Date(now))
        val penaltyActive = store.penaltyDate() == today

        // A pending unlock only exempts a package once it has matured AND
        // been confirmed by the user.
        val exempt = store.pendingUnlock()
            ?.takeIf { it.confirmed && it.isReady(now) }
            ?.packageName

        return store.rules()
            .filter { it.packageName != exempt }
            .filter { shouldSuspend(it, penaltyActive) }
            .map { it.packageName }
            .toSet()
    }

    private fun shouldSuspend(rule: AppRule, penaltyActive: Boolean): Boolean =
        when (rule.type) {
            RestrictionType.NONE -> false
            RestrictionType.FULL_BLOCK -> true
            RestrictionType.TIME_LIMIT -> {
                // Under penalty, restricted apps get no allowance at all.
                if (penaltyActive) true
                else usedTodayMs(rule.packageName) >= rule.dailyLimitMinutes * 60_000L
            }
        }

    /**
     * Applies the current decision. Packages that should no longer be
     * suspended are released, so a new day restores access automatically.
     */
    fun apply(): Result<Unit> = runCatching {
        if (!isDeviceOwner()) error("Focus is not the device owner")

        val target = packagesToSuspend()
        val managed = store.rules().map { it.packageName }.toSet()
        val release = managed - target

        if (target.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, target.toTypedArray(), true)
        }
        if (release.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, release.toTypedArray(), false)
        }
    }

    /** Remaining allowance in minutes, or null if the app is not time-limited. */
    fun remainingMinutes(pkg: String): Int? {
        val rule = store.ruleFor(pkg) ?: return null
        if (rule.type != RestrictionType.TIME_LIMIT) return null
        val usedMin = (usedTodayMs(pkg) / 60_000L).toInt()
        return (rule.dailyLimitMinutes - usedMin).coerceAtLeast(0)
    }

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
