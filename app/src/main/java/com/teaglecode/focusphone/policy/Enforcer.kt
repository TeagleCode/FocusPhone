package com.teaglecode.focusphone.policy

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.os.Process
import com.teaglecode.focusphone.data.AppRule
import com.teaglecode.focusphone.data.BlockReason
import com.teaglecode.focusphone.data.PolicyStore
import com.teaglecode.focusphone.data.RestrictionType
import com.teaglecode.focusphone.data.TodoStore
import com.teaglecode.focusphone.data.UnlockKind
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Which enforcement layers are currently available.
 *
 * There are two, and they are independent. The accessibility guard works on
 * any phone and closes a restricted app the moment it opens. Device Owner is
 * stronger — the system itself refuses to launch a suspended package — but it
 * can only be provisioned by ADB on a device with no accounts, so most
 * installs will never have it. Enforcement is real as long as either is on.
 */
data class EnforcementStatus(
    val deviceOwner: Boolean,
    val guardEnabled: Boolean,
    val usageAccess: Boolean
) {
    val canBlock get() = deviceOwner || guardEnabled
}

/** What the launcher should display next to an app. */
sealed interface AppState {
    data object Unrestricted : AppState
    data class Blocked(val reason: BlockReason) : AppState
    data class Remaining(val minutes: Int) : AppState
}

/**
 * Everything needed to decide the status of any package, read once.
 *
 * Screens list many apps at a time and the guard service checks a package on
 * every window change, so the inputs are gathered in a single pass instead of
 * being re-read per row.
 */
data class PolicySnapshot(
    val rules: Map<String, AppRule>,
    val usageMs: Map<String, Long>,
    val social: Set<String>,
    val readingPenalty: Boolean,
    val taskPenalty: Boolean
)

/**
 * Decides what is restricted right now, and applies that decision through
 * DevicePolicyManager when it is available.
 *
 * Suspension (as opposed to hiding) is deliberate: the app stays visible and
 * Android shows a system dialog explaining it is unavailable, which is a
 * clearer signal than an app that silently vanished.
 */
class Enforcer(private val context: Context) {

    private val app = context.applicationContext
    private val store = PolicyStore(app)
    private val todos = TodoStore(app)
    private val ledger = UsageLedger(app)
    private val dpm =
        app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(app, FocusDeviceAdminReceiver::class.java)
    private val usage =
        app.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    // Read on the guard's thread and from the launcher's IO dispatcher.
    @Volatile private var systemUsageCache: Map<String, Long> = emptyMap()
    @Volatile private var systemUsageAtMs = 0L

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(app.packageName)

    /**
     * Checked through AppOps rather than by running a query and seeing whether
     * anything comes back: the query costs real time, and an empty result is
     * ambiguous between "not permitted" and "nothing used yet today".
     */
    fun hasUsageAccess(): Boolean = runCatching {
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            app.packageName
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun status() = EnforcementStatus(
        deviceOwner = isDeviceOwner(),
        guardEnabled = FocusGuardService.isEnabled(app),
        usageAccess = hasUsageAccess()
    )

    // ---- Measurement -----------------------------------------------------

    /**
     * Foreground time today, per package, merging Focus's own ledger with the
     * system's figures by taking whichever is larger. The ledger is accurate
     * to the second while an app is open; the system figures survive periods
     * when the guard service was off.
     */
    fun usageTodayByPackage(): Map<String, Long> = merge(ledger.today(), systemUsage(0L))

    /**
     * The system's figures, re-read at most once per [maxAgeMs].
     *
     * The query walks every package's stats, which is too heavy to repeat on
     * every window event, and that is why the guard's hot path originally
     * skipped it altogether. Skipping it was wrong: the ledger only knows time
     * the guard itself watched, so an allowance already spent before the
     * service started — or before it was ever switched on — read as untouched,
     * and a blown limit did not bite. Caching gets both: cheap on the hot path,
     * and never more than [GUARD_USAGE_TTL_MS] behind the truth.
     */
    private fun systemUsage(maxAgeMs: Long): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - systemUsageAtMs <= maxAgeMs && systemUsageAtMs != 0L) return systemUsageCache
        systemUsageCache = runCatching {
            usage.queryAndAggregateUsageStats(startOfDayMs(), now)
                .mapValues { it.value.totalTimeInForeground }
        }.getOrDefault(emptyMap())
        systemUsageAtMs = now
        return systemUsageCache
    }

    /** Whichever source saw more time; the system's figures may be absent. */
    private fun merge(own: Map<String, Long>, system: Map<String, Long>): Map<String, Long> {
        if (system.isEmpty()) return own
        return (own.keys + system.keys).associateWith {
            maxOf(own[it] ?: 0L, system[it] ?: 0L)
        }
    }

    fun usedTodayMs(pkg: String): Long = usageTodayByPackage()[pkg] ?: 0L

    // ---- Decision --------------------------------------------------------

    fun snapshot(): PolicySnapshot = PolicySnapshot(
        rules = store.rulesByPackage(),
        usageMs = usageTodayByPackage(),
        social = store.socialPackages(),
        readingPenalty = store.penaltyDate() == dateKey(Date()),
        taskPenalty = todos.socialLockedToday()
    )

    /**
     * The guard service's hot path, called on every window change.
     *
     * Identical to [snapshot] except that the system's usage figures come from
     * a short-lived cache instead of a fresh query. It must not fall back to
     * the ledger alone: that is what let an app whose allowance was already
     * spent stay open, because the guard had not been running to watch it go.
     */
    fun fastSnapshot(): PolicySnapshot = PolicySnapshot(
        rules = store.rulesByPackage(),
        usageMs = merge(ledger.today(), systemUsage(GUARD_USAGE_TTL_MS)),
        social = store.socialPackages(),
        readingPenalty = store.penaltyDate() == dateKey(Date()),
        taskPenalty = todos.socialLockedToday()
    )

    /**
     * Why [pkg] may not be used right now, or null when it may.
     *
     * Note what is absent: there is no branch that can restrict a package with
     * no rule and no social flag. An app the user never opted in to is
     * untouchable by every penalty here, which is what keeps the phone usable
     * in an emergency.
     */
    fun reasonFor(pkg: String, snap: PolicySnapshot): BlockReason? {
        if (snap.taskPenalty && pkg in snap.social) return BlockReason.TASK_PENALTY

        val rule = snap.rules[pkg] ?: return null
        return when (rule.type) {
            RestrictionType.NONE -> null
            RestrictionType.FULL_BLOCK -> BlockReason.FULL_BLOCK
            RestrictionType.TIME_LIMIT -> when {
                snap.readingPenalty -> BlockReason.READING_PENALTY
                (snap.usageMs[pkg] ?: 0L) >= rule.dailyLimitMinutes * 60_000L ->
                    BlockReason.LIMIT_REACHED
                else -> null
            }
        }
    }

    /** What the home screen shows beside an app. */
    fun stateOf(pkg: String, snap: PolicySnapshot): AppState {
        reasonFor(pkg, snap)?.let { return AppState.Blocked(it) }
        val rule = snap.rules[pkg] ?: return AppState.Unrestricted
        if (rule.type != RestrictionType.TIME_LIMIT) return AppState.Unrestricted
        val usedMin = ((snap.usageMs[pkg] ?: 0L) / 60_000L).toInt()
        return AppState.Remaining((rule.dailyLimitMinutes - usedMin).coerceAtLeast(0))
    }

    /** Milliseconds of allowance left, or null when the app is not time-limited. */
    fun remainingMs(pkg: String, snap: PolicySnapshot): Long? {
        val rule = snap.rules[pkg] ?: return null
        if (rule.type != RestrictionType.TIME_LIMIT) return null
        return rule.dailyLimitMinutes * 60_000L - (snap.usageMs[pkg] ?: 0L)
    }

    /** Every package that must be unavailable right now. */
    fun packagesToSuspend(snap: PolicySnapshot = snapshot()): Set<String> =
        (snap.rules.keys + snap.social).filter { reasonFor(it, snap) != null }.toSet()

    // ---- Application -----------------------------------------------------

    /**
     * Pushes the current decision into DevicePolicyManager.
     *
     * A no-op without Device Owner, which is not a failure: the accessibility
     * guard is the layer that works on an unprovisioned phone, and treating
     * its absence as an error here would spam the log on every resume.
     *
     * Release is driven by what was previously suspended rather than by the
     * current rule list, so deleting a rule outright still frees its package.
     */
    fun apply(): Result<Unit> = runCatching {
        if (!isDeviceOwner()) return@runCatching

        val target = packagesToSuspend()
        val release = store.suspendedPackages() - target

        if (target.isNotEmpty()) {
            dpm.setPackagesSuspended(admin, target.toTypedArray(), true)
        }
        // A package uninstalled while suspended would throw and abort the
        // whole pass, so release one at a time.
        release.forEach { pkg ->
            runCatching { dpm.setPackagesSuspended(admin, arrayOf(pkg), false) }
        }
        store.saveSuspendedPackages(target)
    }

    /**
     * Applies a matured, user-confirmed unlock and clears it. Returns false if
     * the request is not yet ready, so the caller can refuse loudly.
     *
     * [force] skips the maturity check, and is only ever passed after a
     * correct emergency code — which itself had to be armed 24 hours earlier.
     */
    fun confirmUnlock(force: Boolean = false): Boolean {
        val pending = store.pendingUnlock() ?: return false
        if (!force && !pending.isReady()) return false

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
            UnlockKind.SOCIAL ->
                store.saveSocialPackages(store.socialPackages() - pending.target)
        }
        store.setPendingUnlock(null)
        apply()
        FocusGuardService.refreshScope()
        return true
    }

    /** Human-readable label for a package, falling back to the package name. */
    fun labelOf(pkg: String): String = runCatching {
        val pm = app.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        /**
         * How stale the system's usage figures may be on the guard's hot path.
         * Ten seconds costs at most one aggregate query per ten seconds of
         * active use, and bounds how long an app can outlive its allowance.
         */
        private const val GUARD_USAGE_TTL_MS = 10_000L

        fun dateKey(d: Date): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d)

        fun startOfDayMs(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        /** Wording shown to the user when something is closed or listed. */
        fun explain(reason: BlockReason): String = when (reason) {
            BlockReason.FULL_BLOCK -> "blocked"
            BlockReason.LIMIT_REACHED -> "limit reached"
            BlockReason.READING_PENALTY -> "reading quiz"
            BlockReason.TASK_PENALTY -> "tasks unfinished"
        }

        fun explainLong(reason: BlockReason): String = when (reason) {
            BlockReason.FULL_BLOCK ->
                "This app is blocked entirely."
            BlockReason.LIMIT_REACHED ->
                "You have used today's allowance for this app."
            BlockReason.READING_PENALTY ->
                "Yesterday's reading quiz was failed, so restricted apps get no allowance today."
            BlockReason.TASK_PENALTY ->
                "Yesterday's tasks were not all finished, so social apps are locked today."
        }
    }
}
