package com.teaglecode.focusphone.policy

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.teaglecode.focusphone.data.BlockReason
import com.teaglecode.focusphone.data.BlockedSection
import com.teaglecode.focusphone.data.PolicyStore

/**
 * The layer that actually stops an app from being used.
 *
 * Device Owner suspension is stronger, but it can only be provisioned over ADB
 * on a phone with no accounts on it, so on an ordinary install nothing was
 * being enforced at all. This service needs only the accessibility toggle: it
 * sees a restricted app come to the foreground and sends the user straight
 * back home, which is how every app-blocker on the Play Store works.
 *
 * It also does the two other jobs that need to watch the foreground:
 * accumulating time against daily limits second by second, and closing
 * short-form video feeds inside apps that are otherwise allowed.
 *
 * Scoping is a hard requirement and is enforced by the system, not by this
 * code: AccessibilityServiceInfo.packageNames is set to exactly the packages
 * the user opted in to, so events from banking, messaging or anything
 * unlisted never reach this process.
 */
class FocusGuardService : AccessibilityService() {

    private val store by lazy { PolicyStore(this) }
    private val enforcer by lazy { Enforcer(this) }
    private val ledger by lazy { UsageLedger(this) }
    private val handler = Handler(Looper.getMainLooper())

    /** The watched package currently in the foreground, if any. */
    private var currentPkg: String? = null
    private var sinceMs = 0L

    private var lastSectionActionMs = 0L
    private var lastBlockedPkg: String? = null
    private var lastBlockedMs = 0L

    private val ticker = Runnable { tick() }

    /**
     * The screen going off is the one transition that produces no
     * accessibility event but definitely ends the session. Without this, a
     * phone left overnight on an open app would burn the whole allowance.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> stopTracking()
                // Unlocking straight back into the same app does not always
                // produce a window event, so the foreground is re-derived
                // rather than assumed.
                Intent.ACTION_SCREEN_ON -> handler.postDelayed(::resumeTracking, RESUME_DELAY_MS)
            }
        }
    }

    private var receiverRegistered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        applyScope()
        if (!receiverRegistered) {
            registerReceiver(
                screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                Context.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopTracking()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        instance = null
        return super.onUnbind(intent)
    }

    /** Picks tracking back up on whatever is in front, after a screen-off gap. */
    private fun resumeTracking() {
        if (currentPkg != null) return
        val pkg = actualForeground()?.first ?: return
        if (pkg !in store.watchedPackages()) return
        currentPkg = pkg
        sinceMs = System.currentTimeMillis()
        if (!guard(pkg)) scheduleTick(pkg)
    }

    // ---- Scope -----------------------------------------------------------

    /** Re-reads the watch list and narrows the service to those packages. */
    fun applyScope() {
        val watched = store.watchedPackages()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            // An empty array means "every package", which would be a serious
            // overreach, so an empty watch list scopes to a sentinel instead.
            packageNames = watched.ifEmpty { setOf(NO_MATCH) }.toTypedArray()
        }
    }

    // ---- Events ----------------------------------------------------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg != currentPkg) {
                flush()
                currentPkg = pkg
                sinceMs = System.currentTimeMillis()
            }
            if (guard(pkg)) return
            scheduleTick(pkg)
        }

        checkSections(pkg)
    }

    override fun onInterrupt() = Unit

    // ---- Blocking --------------------------------------------------------

    /** Closes [pkg] if it may not be used right now. Returns true if it did. */
    private fun guard(pkg: String): Boolean {
        val reason = enforcer.reasonFor(pkg, enforcer.fastSnapshot()) ?: return false
        block(pkg, reason)
        return true
    }

    /**
     * Sends the user home and leaves a note for the launcher to explain.
     *
     * Starting an activity would be the more explicit gesture, but background
     * activity starts are blocked on modern Android, so the reliable route is
     * the global home action — which lands on this launcher, where the notice
     * is waiting.
     */
    private fun block(pkg: String, reason: BlockReason) {
        val now = System.currentTimeMillis()
        // Several window events arrive as an app opens. The debounce only
        // suppresses the repeated write, never the home action itself: a
        // skipped close would leave a blocked app sitting open on screen.
        val repeat = pkg == lastBlockedPkg && now - lastBlockedMs < BLOCK_DEBOUNCE_MS
        lastBlockedPkg = pkg
        lastBlockedMs = now

        stopTracking()
        if (!repeat) store.recordBlock(pkg, reason)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    // ---- Time accounting -------------------------------------------------

    /**
     * Wakes up either when the allowance runs out or in [MAX_TICK_MS],
     * whichever is sooner, so a limit bites on time rather than waiting for
     * the next fifteen-minute sweep.
     */
    private fun scheduleTick(pkg: String) {
        handler.removeCallbacks(ticker)
        val remaining = enforcer.remainingMs(pkg, enforcer.fastSnapshot()) ?: return
        handler.postDelayed(ticker, remaining.coerceIn(MIN_TICK_MS, MAX_TICK_MS))
    }

    private fun tick() {
        val tracked = currentPkg ?: return

        // The service only receives events from watched packages, so leaving
        // for an unwatched app is invisible here. The system's event log is
        // the only way to notice, and it needs usage access.
        val foreground = actualForeground()
        if (foreground != null && foreground.first != tracked) {
            flush(untilMs = foreground.second)
            val next = foreground.first
            if (next in store.watchedPackages()) {
                currentPkg = next
                sinceMs = foreground.second
                if (guard(next)) return
            } else {
                currentPkg = null
                return
            }
        }

        flush()
        val pkg = currentPkg ?: return
        if (guard(pkg)) return
        scheduleTick(pkg)
    }

    /** Credits elapsed time to the tracked package and resets the mark. */
    private fun flush(untilMs: Long = System.currentTimeMillis()) {
        val pkg = currentPkg ?: return
        // Never move the mark backwards: a usage event can be older than the
        // mark, and rewinding would credit the same stretch of time twice.
        if (untilMs <= sinceMs) return
        val delta = untilMs - sinceMs
        sinceMs = untilMs
        // A delta longer than this means the mark was stale — a missed screen
        // off, or a clock change. Crediting it would be a lie either way.
        if (delta <= MAX_CREDIT_MS) ledger.add(pkg, delta)
    }

    private fun stopTracking() {
        flush()
        currentPkg = null
        handler.removeCallbacks(ticker)
    }

    /** The package the system says is in front, with the time it came forward. */
    private fun actualForeground(): Pair<String, Long>? = runCatching {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - LOOKBACK_MS, now)
        val out = UsageEvents.Event()
        var latest: Pair<String, Long>? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(out)
            if (out.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = out.packageName to out.timeStamp
            }
        }
        latest
    }.getOrNull()

    // ---- In-app sections -------------------------------------------------

    private fun checkSections(pkg: String) {
        val sections = store.blockedSections().filter { it.packageName == pkg }
        if (sections.isEmpty()) return

        // Content-changed fires very frequently inside a scrolling feed.
        val now = System.currentTimeMillis()
        if (now - lastSectionActionMs < SECTION_DEBOUNCE_MS) return

        val root = rootInActiveWindow ?: return
        if (sections.any { matches(root, it) }) {
            lastSectionActionMs = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Whether a blocked section is actually open right now.
     *
     * Getting this wrong is worse than not blocking at all. The first version
     * asked findAccessibilityNodeInfosByViewId whether "clips_tab" existed
     * anywhere — but that is the Reels *button in the bottom navigation bar*,
     * which is present on every screen in Instagram, including the inbox. The
     * text hint "Reels" was no better: it is the content description of that
     * same button. So opening Instagram at all closed it immediately, and
     * there was no way to reach messages.
     *
     * Presence is not the signal. Instagram pre-inflates the whole Reels
     * fragment while you sit on the home feed, so the viewer's own ids exist
     * either way. What actually distinguishes the feed being *open* is that it
     * is visible and fills the screen — the tab button is a thumbnail, and the
     * "suggested reels" tray embedded in the home feed is a short strip.
     */
    private fun matches(root: AccessibilityNodeInfo, section: BlockedSection): Boolean {
        if (section.viewIdHints.isEmpty() && section.textHints.isEmpty()) return false
        val screen = screenBounds() ?: return false
        return scan(root, section, screen, depth = 0, budget = intArrayOf(MAX_NODES))
    }

    private fun scan(
        node: AccessibilityNodeInfo,
        section: BlockedSection,
        screen: Rect,
        depth: Int,
        budget: IntArray
    ): Boolean {
        if (depth > MAX_DEPTH || budget[0] <= 0) return false
        budget[0]--

        if (nodeMatches(node, section, screen)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scan(child, section, screen, depth + 1, budget)) return true
        }
        return false
    }

    private fun nodeMatches(
        node: AccessibilityNodeInfo,
        section: BlockedSection,
        screen: Rect
    ): Boolean {
        if (!node.isVisibleToUser) return false

        // View-id hints are substrings, so a vendor renaming
        // clips_viewer_view_pager to clips_viewer_pager does not break them.
        val id = node.viewIdResourceName
        if (id != null && section.viewIdHints.any { it.isNotBlank() && id.contains(it, true) }) {
            if (fillsScreen(node, screen)) return true
        }

        // Text hints cannot demand a full-screen node, because a label never
        // is one. They are correspondingly loose and are off by default.
        if (section.textHints.isNotEmpty()) {
            val haystack = buildString {
                node.text?.let { append(it).append(' ') }
                node.contentDescription?.let { append(it) }
            }
            if (haystack.isNotBlank() &&
                section.textHints.any { it.isNotBlank() && haystack.contains(it, true) }
            ) {
                return true
            }
        }
        return false
    }

    /** A feed being viewed occupies the screen; a tab button or tray does not. */
    private fun fillsScreen(node: AccessibilityNodeInfo, screen: Rect): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return bounds.width() >= screen.width() * WIDTH_FRACTION &&
            bounds.height() >= screen.height() * HEIGHT_FRACTION
    }

    private fun screenBounds(): Rect? = runCatching {
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
    }.getOrNull()

    companion object {
        private const val SECTION_DEBOUNCE_MS = 600L
        private const val BLOCK_DEBOUNCE_MS = 800L
        private const val MIN_TICK_MS = 1_000L
        private const val MAX_TICK_MS = 30_000L
        private const val MAX_CREDIT_MS = 15 * 60_000L
        private const val LOOKBACK_MS = 5 * 60_000L
        private const val RESUME_DELAY_MS = 1_200L

        /** Bounds on the tree walk, so a deep feed cannot stall the service. */
        private const val MAX_DEPTH = 24
        private const val MAX_NODES = 900

        /** What "the feed is open" means, as a share of the screen. */
        private const val WIDTH_FRACTION = 0.7f
        private const val HEIGHT_FRACTION = 0.5f
        private const val NO_MATCH = "com.teaglecode.focusphone.nomatch"

        @Volatile
        private var instance: FocusGuardService? = null

        /**
         * Called whenever the watch list changes. Without this the scope would
         * only be recalculated when the service reconnects, so a newly
         * restricted app would go unwatched — and a released one would keep
         * being watched.
         */
        fun refreshScope() {
            // Callers include a WorkManager worker and the settings screens,
            // so this is bounced onto the service's own thread rather than
            // touching serviceInfo from whichever one happens to call.
            val service = instance ?: return
            service.handler.post { service.applyScope() }
        }

        /**
         * The launcher coming forward always ends the session of whatever was
         * open, and is the fallback for noticing a switch when usage access
         * has not been granted.
         */
        fun notifyLauncherForeground() {
            val service = instance ?: return
            service.handler.post { service.stopTracking() }
        }

        fun isRunning(): Boolean = instance != null

        /** Whether the user has switched the service on in system settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, FocusGuardService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any {
                ComponentName.unflattenFromString(it) == expected
            }
        }

        /**
         * Starting hints for common short-form feeds. Best-effort: vendors
         * change their view ids regularly, so treat them as defaults the user
         * can edit, not guarantees.
         *
         * These name the *viewer* rather than the tab that opens it. Matching
         * the tab is what made Instagram unusable — the button lives in the
         * navigation bar on every screen, so the whole app closed on launch
         * instead of just the feed. Text hints are empty for the same reason:
         * "Reels" is that button's content description.
         */
        val DEFAULT_SECTIONS = listOf(
            BlockedSection(
                packageName = "com.instagram.android",
                label = "Instagram Reels",
                viewIdHints = listOf("clips_viewer_view_pager", "clips_viewer_video_layout"),
                textHints = emptyList()
            ),
            BlockedSection(
                packageName = "com.google.android.youtube",
                label = "YouTube Shorts",
                viewIdHints = listOf("reel_recycler", "reel_player_page_container"),
                textHints = emptyList()
            ),
            BlockedSection(
                packageName = "com.zhiliaoapp.musically",
                label = "TikTok For You feed",
                // TikTok obfuscates its view ids and changes them per build, so
                // there is no default that survives an update. Left empty on
                // purpose: an honest blank the user can fill beats a hint that
                // silently matches the wrong thing. TikTok is also almost
                // entirely feed, so blocking the app outright is usually right.
                viewIdHints = emptyList(),
                textHints = emptyList()
            )
        )
    }
}
