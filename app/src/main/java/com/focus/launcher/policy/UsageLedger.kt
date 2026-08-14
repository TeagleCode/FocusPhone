package com.focus.launcher.policy

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Focus's own record of foreground time, written by the guard service.
 *
 * UsageStatsManager is the better source when it is available, but it needs a
 * special-access permission the user may not have granted, and several OEM
 * builds only flush totalTimeInForeground when an app leaves the foreground —
 * which is far too late to enforce a limit against. The ledger is accurate to
 * the second while an app is open, so limits bite on time rather than on the
 * next fifteen-minute sweep.
 *
 * The two sources are merged by taking the larger value per package, so
 * whichever noticed more time wins and neither can undercount the other.
 */
class UsageLedger(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("focus_usage", Context.MODE_PRIVATE)

    /** Foreground milliseconds recorded today, per package. */
    @Synchronized
    fun today(): Map<String, Long> {
        rollOverIfNeeded()
        val raw = prefs.getString(KEY_MS, "{}") ?: "{}"
        val o = JSONObject(raw)
        return o.keys().asSequence().associateWith { o.optLong(it, 0L) }
    }

    @Synchronized
    fun add(pkg: String, deltaMs: Long) {
        if (deltaMs <= 0) return
        rollOverIfNeeded()
        val o = JSONObject(prefs.getString(KEY_MS, "{}") ?: "{}")
        o.put(pkg, o.optLong(pkg, 0L) + deltaMs)
        prefs.edit().putString(KEY_MS, o.toString()).apply()
    }

    @Synchronized
    fun msFor(pkg: String): Long = today()[pkg] ?: 0L

    /**
     * Local midnight clears the ledger. Checked lazily on every read and write
     * rather than by a scheduled job, so a phone that was off at midnight
     * still starts the day at zero.
     */
    private fun rollOverIfNeeded() {
        val today = dateKey()
        if (prefs.getString(KEY_DAY, null) == today) return
        prefs.edit().putString(KEY_DAY, today).putString(KEY_MS, "{}").apply()
    }

    companion object {
        private const val KEY_DAY = "day"
        private const val KEY_MS = "ms"

        fun dateKey(date: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)

        fun startOfDayMs(): Long = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
