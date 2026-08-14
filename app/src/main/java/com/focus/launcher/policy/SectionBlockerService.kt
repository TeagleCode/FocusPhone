package com.focus.launcher.policy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.focus.launcher.data.BlockedSection
import com.focus.launcher.data.PolicyStore

/**
 * Watches ONLY the packages the user has explicitly listed for section
 * blocking. The package filter is applied by the system through
 * AccessibilityServiceInfo.packageNames, so events from unlisted apps —
 * banking, messaging, anything else — never reach this process at all.
 *
 * When a blocked section is detected, the service performs a global BACK,
 * which returns the user to the surrounding app rather than killing it.
 */
class SectionBlockerService : AccessibilityService() {

    private val store by lazy { PolicyStore(this) }
    private var lastActionMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyScope()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /** Re-reads the watch list and narrows the service to those packages. */
    fun applyScope() {
        val watched = store.watchedPackages()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 200
            // Empty list would mean "all packages", so fall back to a
            // sentinel that matches nothing when no sections are configured.
            packageNames = watched.ifEmpty { setOf(NO_MATCH) }.toTypedArray()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val sections = store.blockedSections().filter { it.packageName == pkg }
        if (sections.isEmpty()) return

        // Debounce: content-changed fires very frequently.
        val now = System.currentTimeMillis()
        if (now - lastActionMs < DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        if (sections.any { matches(root, it) }) {
            lastActionMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun matches(root: AccessibilityNodeInfo, section: BlockedSection): Boolean {
        section.viewIdHints.forEach { id ->
            if (root.findAccessibilityNodeInfosByViewId(id).isNotEmpty()) return true
        }
        section.textHints.forEach { text ->
            if (root.findAccessibilityNodeInfosByText(text).any { it.isVisibleToUser }) return true
        }
        return false
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val DEBOUNCE_MS = 600L
        private const val NO_MATCH = "com.focus.launcher.nomatch"

        @Volatile private var instance: SectionBlockerService? = null

        /**
         * Called whenever the watch list changes. Without this the scope would
         * only be recalculated when the service reconnects, so a newly added
         * app would go unwatched — and a removed one would keep being watched.
         */
        fun refreshScope() {
            instance?.applyScope()
        }

        /** Whether the user has switched the service on in system settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, SectionBlockerService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }

        /**
         * Starting hints for common short-form feeds. These are best-effort:
         * the vendors change their view ids regularly, so treat them as
         * defaults the user can edit, not guarantees.
         */
        val DEFAULT_SECTIONS = listOf(
            BlockedSection(
                packageName = "com.instagram.android",
                label = "Instagram Reels",
                viewIdHints = listOf("com.instagram.android:id/clips_tab"),
                textHints = listOf("Reels")
            ),
            BlockedSection(
                packageName = "com.google.android.youtube",
                label = "YouTube Shorts",
                viewIdHints = listOf("com.google.android.youtube:id/reel_recycler"),
                textHints = listOf("Shorts")
            ),
            BlockedSection(
                packageName = "com.zhiliaoapp.musically",
                label = "TikTok For You feed",
                viewIdHints = emptyList(),
                textHints = listOf("For You")
            )
        )
    }
}
