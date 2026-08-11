package com.focus.launcher.policy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * Re-evaluates restrictions on a schedule so a limit takes effect while the
 * app is open, rather than waiting for a return to the launcher.
 *
 * WorkManager's floor for periodic work is 15 minutes, so an overrun of up to
 * that long is possible. Tightening it further would mean a foreground
 * service, which costs battery — the opposite of the point of this phone.
 */
class PolicyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Enforcer(applicationContext).apply()
        return Result.success()
    }

    companion object {
        private const val NAME = "focus_policy_check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PolicyWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

/** Restores enforcement after a restart; suspension does not survive reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Enforcer(context).apply()
        PolicyWorker.schedule(context)
    }
}
