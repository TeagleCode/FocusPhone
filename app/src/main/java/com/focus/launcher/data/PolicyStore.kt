package com.focus.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** How a package is restricted. */
enum class RestrictionType { NONE, TIME_LIMIT, FULL_BLOCK }

/** What a pending unlock is trying to relax. */
enum class UnlockKind { APP, DOMAIN, SOCIAL }

/** A section inside an app that should be blocked (e.g. Instagram Reels). */
data class BlockedSection(
    val packageName: String,
    val label: String,
    /** View-id substrings that identify the section on screen. */
    val viewIdHints: List<String>,
    /** Visible text substrings that identify the section. */
    val textHints: List<String>
)

data class AppRule(
    val packageName: String,
    val type: RestrictionType,
    /** Daily allowance in minutes. Only meaningful for TIME_LIMIT. */
    val dailyLimitMinutes: Int = 0
) {
    /**
     * True when [other] would leave the user with less access than this rule.
     * Tightening applies immediately; anything else has to wait.
     */
    fun isTighterThan(other: AppRule?): Boolean {
        if (other == null) return type != RestrictionType.NONE
        if (severity(this) > severity(other)) return true
        if (severity(this) < severity(other)) return false
        return type == RestrictionType.TIME_LIMIT &&
            dailyLimitMinutes < other.dailyLimitMinutes
    }

    private fun severity(rule: AppRule) = when (rule.type) {
        RestrictionType.NONE -> 0
        RestrictionType.TIME_LIMIT -> 1
        RestrictionType.FULL_BLOCK -> 2
    }
}

/**
 * A user request to relax a restriction. Requests never take effect when made:
 * they mature after [UNLOCK_DELAY_MS] and then still require an explicit
 * confirmation tap. Confirming applies the change and clears the request, so
 * the "one pending at a time" rule can never deadlock.
 */
data class PendingUnlock(
    val kind: UnlockKind,
    /** Package name for [UnlockKind.APP], domain for [UnlockKind.DOMAIN]. */
    val target: String,
    val requestedAtMs: Long,
    /** What the rule becomes once applied. Unused for domain removals. */
    val newType: RestrictionType = RestrictionType.NONE,
    val newLimitMinutes: Int = 0
) {
    fun readyAtMs() = requestedAtMs + UNLOCK_DELAY_MS
    fun isReady(now: Long = System.currentTimeMillis()) = now >= readyAtMs()

    companion object {
        const val UNLOCK_DELAY_MS = 24L * 60 * 60 * 1000
    }
}

/** Why something was just closed. Shown on the launcher after an interception. */
enum class BlockReason { FULL_BLOCK, LIMIT_REACHED, READING_PENALTY, TASK_PENALTY }

data class BlockNotice(val packageName: String, val reason: BlockReason, val atMs: Long)

/**
 * Parsed state is cached process-wide because every screen and the guard
 * service read the same rules many times per second. SharedPreferences is
 * already an in-memory map; what cost real time was re-parsing the JSON on
 * every single lookup, which is what made the launcher stutter.
 */
private object ParseCache {
    @Volatile var rules: List<AppRule>? = null
    @Volatile var sections: List<BlockedSection>? = null

    fun invalidate() {
        rules = null
        sections = null
    }
}

class PolicyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("focus_policy", Context.MODE_PRIVATE)

    private fun commit(edit: SharedPreferences.Editor) {
        edit.apply()
        ParseCache.invalidate()
    }

    // ---- App rules -------------------------------------------------------

    fun rules(): List<AppRule> {
        ParseCache.rules?.let { return it }
        val raw = prefs.getString(KEY_RULES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        val parsed = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppRule(
                packageName = o.getString("pkg"),
                type = RestrictionType.valueOf(o.getString("type")),
                dailyLimitMinutes = o.optInt("limit", 0)
            )
        }
        ParseCache.rules = parsed
        return parsed
    }

    /** Indexed once for callers that look up many packages in a row. */
    fun rulesByPackage(): Map<String, AppRule> = rules().associateBy { it.packageName }

    fun ruleFor(pkg: String): AppRule? = rules().firstOrNull { it.packageName == pkg }

    fun saveRules(rules: List<AppRule>) {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(JSONObject().apply {
                put("pkg", r.packageName)
                put("type", r.type.name)
                put("limit", r.dailyLimitMinutes)
            })
        }
        commit(prefs.edit().putString(KEY_RULES, arr.toString()))
    }

    fun upsertRule(rule: AppRule) {
        val next = rules().filterNot { it.packageName == rule.packageName } + rule
        saveRules(next)
    }

    /**
     * Everything the guard service is allowed to observe: apps under a rule,
     * apps flagged as social, and apps with a blocked section. Nothing else is
     * ever in scope, so events from banking or messaging apps do not reach
     * this process at all.
     */
    fun watchedPackages(): Set<String> =
        rules().filter { it.type != RestrictionType.NONE }.map { it.packageName }.toSet() +
            socialPackages() +
            blockedSections().map { it.packageName }.toSet()

    // ---- Social apps -----------------------------------------------------

    /**
     * Apps that count as social media for the unfinished-tasks penalty. Opt-in
     * per package, so the penalty can never reach the phone or messages.
     */
    fun socialPackages(): Set<String> = prefs.getStringSet(KEY_SOCIAL, null) ?: emptySet()

    fun saveSocialPackages(pkgs: Set<String>) {
        commit(prefs.edit().putStringSet(KEY_SOCIAL, LinkedHashSet(pkgs)))
    }

    fun isSocial(pkg: String) = pkg in socialPackages()

    fun toggleSocial(pkg: String) {
        val current = socialPackages()
        saveSocialPackages(if (pkg in current) current - pkg else current + pkg)
    }

    /** Flags the obvious candidates once, so the feature is not empty on day one. */
    fun seedSocialIfUnset(installed: Set<String>) {
        if (prefs.contains(KEY_SOCIAL)) return
        saveSocialPackages(DEFAULT_SOCIAL.filter { it in installed }.toSet())
    }

    // ---- In-app sections -------------------------------------------------

    fun blockedSections(): List<BlockedSection> {
        ParseCache.sections?.let { return it }
        val raw = prefs.getString(KEY_SECTIONS, null)
        if (raw == null) {
            ParseCache.sections = emptyList()
            return emptyList()
        }
        val arr = JSONArray(raw)
        val parsed = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BlockedSection(
                packageName = o.getString("pkg"),
                label = o.getString("label"),
                viewIdHints = o.getJSONArray("ids").toStringList(),
                textHints = o.getJSONArray("texts").toStringList()
            )
        }
        ParseCache.sections = parsed
        return parsed
    }

    fun saveSections(sections: List<BlockedSection>) {
        val arr = JSONArray()
        sections.forEach { s ->
            arr.put(JSONObject().apply {
                put("pkg", s.packageName)
                put("label", s.label)
                put("ids", JSONArray(s.viewIdHints))
                put("texts", JSONArray(s.textHints))
            })
        }
        commit(prefs.edit().putString(KEY_SECTIONS, arr.toString()))
    }

    fun upsertSection(section: BlockedSection) {
        val next = blockedSections().filterNot { it.packageName == section.packageName } + section
        saveSections(next)
    }

    // ---- Blocked websites ------------------------------------------------

    fun blockedDomains(): Set<String> =
        prefs.getStringSet(KEY_DOMAINS, null) ?: emptySet()

    fun saveBlockedDomains(domains: Set<String>) {
        // A defensive copy: SharedPreferences must not be handed a set that
        // the caller may later mutate.
        commit(prefs.edit().putStringSet(KEY_DOMAINS, LinkedHashSet(domains)))
    }

    fun addBlockedDomain(domain: String) {
        saveBlockedDomains(blockedDomains() + domain)
    }

    /** Seeds the starter list once, so a fresh install blocks something useful. */
    fun seedDomainsIfUnset() {
        if (prefs.contains(KEY_DOMAINS)) return
        saveBlockedDomains(STARTER_DOMAINS)
    }

    // ---- Pending unlocks -------------------------------------------------

    /** Only one unlock may be pending at a time, by design. */
    fun pendingUnlock(): PendingUnlock? {
        val raw = prefs.getString(KEY_PENDING, null) ?: return null
        val o = JSONObject(raw)
        return PendingUnlock(
            kind = UnlockKind.valueOf(o.optString("kind", UnlockKind.APP.name)),
            target = o.getString("target"),
            requestedAtMs = o.getLong("at"),
            newType = RestrictionType.valueOf(
                o.optString("newType", RestrictionType.NONE.name)
            ),
            newLimitMinutes = o.optInt("newLimit", 0)
        )
    }

    fun setPendingUnlock(p: PendingUnlock?) {
        if (p == null) {
            commit(prefs.edit().remove(KEY_PENDING))
            return
        }
        val o = JSONObject().apply {
            put("kind", p.kind.name)
            put("target", p.target)
            put("at", p.requestedAtMs)
            put("newType", p.newType.name)
            put("newLimit", p.newLimitMinutes)
        }
        commit(prefs.edit().putString(KEY_PENDING, o.toString()))
    }

    // ---- Suspension bookkeeping -----------------------------------------

    /**
     * Everything Focus has suspended, whether or not a rule still covers it.
     * Without this, deleting a rule would strand its package in a suspended
     * state with nothing left to release it.
     */
    fun suspendedPackages(): Set<String> =
        prefs.getStringSet(KEY_SUSPENDED, null) ?: emptySet()

    fun saveSuspendedPackages(pkgs: Set<String>) {
        prefs.edit().putStringSet(KEY_SUSPENDED, LinkedHashSet(pkgs)).apply()
    }

    // ---- Interception notice ---------------------------------------------

    /**
     * The guard service cannot start an activity from the background on
     * modern Android, so instead it sends the user home and leaves a note
     * here. The launcher reads it and explains what just happened.
     */
    fun recordBlock(pkg: String, reason: BlockReason) {
        prefs.edit()
            .putString(KEY_LAST_BLOCK, "$pkg|${reason.name}|${System.currentTimeMillis()}")
            .apply()
    }

    fun lastBlock(): BlockNotice? {
        val raw = prefs.getString(KEY_LAST_BLOCK, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 3) return null
        val reason = runCatching { BlockReason.valueOf(parts[1]) }.getOrNull() ?: return null
        val at = parts[2].toLongOrNull() ?: return null
        return BlockNotice(parts[0], reason, at)
    }

    fun clearBlockNotice() {
        prefs.edit().remove(KEY_LAST_BLOCK).apply()
    }

    // ---- Reading penalty state ------------------------------------------

    /** Date string (yyyy-MM-dd) on which the reading quiz was last passed. */
    fun lastQuizPassDate(): String? = prefs.getString(KEY_QUIZ_DATE, null)

    fun markQuizPassed(date: String) {
        prefs.edit().putString(KEY_QUIZ_DATE, date).apply()
    }

    /** Date string on which a penalty is active. */
    fun penaltyDate(): String? = prefs.getString(KEY_PENALTY_DATE, null)

    fun setPenaltyDate(date: String?) {
        prefs.edit().putString(KEY_PENALTY_DATE, date).apply()
    }

    // ---- Setup -----------------------------------------------------------

    fun isSetupComplete(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun markSetupComplete() {
        prefs.edit().putBoolean(KEY_SETUP_DONE, true).apply()
    }

    // ---- API key ---------------------------------------------------------

    /** Anthropic key for quiz generation. Supplied by the user, never bundled. */
    fun apiKey(): String? = prefs.getString(KEY_API, null)

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
    }

    /** Enough to confirm a key is stored without showing it back. */
    fun apiKeyFingerprint(): String? =
        apiKey()?.takeIf { it.isNotBlank() }?.let { "saved · ends ${it.takeLast(4)}" }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_RULES = "rules"
        private const val KEY_SECTIONS = "sections"
        private const val KEY_DOMAINS = "domains"
        private const val KEY_SOCIAL = "social_packages"
        private const val KEY_PENDING = "pending_unlock"
        private const val KEY_QUIZ_DATE = "quiz_date"
        private const val KEY_PENALTY_DATE = "penalty_date"
        private const val KEY_SUSPENDED = "suspended"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_LAST_BLOCK = "last_block"

        /** A starting point, not a complete list. The user extends it. */
        val STARTER_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xhamster.com", "xnxx.com",
            "redtube.com", "youporn.com", "spankbang.com", "onlyfans.com",
            "chaturbate.com", "stripchat.com", "rule34.xxx"
        )

        /**
         * Seeded on first run, filtered to what is actually installed.
         *
         * Deliberately only feed-shaped apps. Messengers are left out even
         * when they have a social side, because the penalty this list feeds
         * must never be able to cut off a way of contacting someone. The user
         * can still flag one by hand if they want to.
         */
        val DEFAULT_SOCIAL = setOf(
            "com.instagram.android",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.google.android.youtube",
            "com.snapchat.android",
            "com.twitter.android",
            "com.facebook.katana",
            "com.facebook.lite",
            "com.reddit.frontpage",
            "com.pinterest",
            "com.linkedin.android",
            "tv.twitch.android.app"
        )
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }
