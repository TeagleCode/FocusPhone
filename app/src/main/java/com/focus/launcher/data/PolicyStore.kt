package com.focus.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** How a package is restricted. */
enum class RestrictionType { NONE, TIME_LIMIT, FULL_BLOCK }

/** What a pending unlock is trying to relax. */
enum class UnlockKind { APP, DOMAIN }

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

class PolicyStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("focus_policy", Context.MODE_PRIVATE)

    // ---- App rules -------------------------------------------------------

    fun rules(): List<AppRule> {
        val raw = prefs.getString(KEY_RULES, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            AppRule(
                packageName = o.getString("pkg"),
                type = RestrictionType.valueOf(o.getString("type")),
                dailyLimitMinutes = o.optInt("limit", 0)
            )
        }
    }

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
        prefs.edit().putString(KEY_RULES, arr.toString()).apply()
    }

    fun upsertRule(rule: AppRule) {
        val next = rules().filterNot { it.packageName == rule.packageName } + rule
        saveRules(next)
    }

    /** Packages the accessibility service is allowed to observe. */
    fun watchedPackages(): Set<String> =
        blockedSections().map { it.packageName }.toSet()

    // ---- In-app sections -------------------------------------------------

    fun blockedSections(): List<BlockedSection> {
        val raw = prefs.getString(KEY_SECTIONS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BlockedSection(
                packageName = o.getString("pkg"),
                label = o.getString("label"),
                viewIdHints = o.getJSONArray("ids").toStringList(),
                textHints = o.getJSONArray("texts").toStringList()
            )
        }
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
        prefs.edit().putString(KEY_SECTIONS, arr.toString()).apply()
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
        prefs.edit().putStringSet(KEY_DOMAINS, LinkedHashSet(domains)).apply()
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
            prefs.edit().remove(KEY_PENDING).apply()
            return
        }
        val o = JSONObject().apply {
            put("kind", p.kind.name)
            put("target", p.target)
            put("at", p.requestedAtMs)
            put("newType", p.newType.name)
            put("newLimit", p.newLimitMinutes)
        }
        prefs.edit().putString(KEY_PENDING, o.toString()).apply()
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
        private const val KEY_PENDING = "pending_unlock"
        private const val KEY_QUIZ_DATE = "quiz_date"
        private const val KEY_PENALTY_DATE = "penalty_date"
        private const val KEY_SUSPENDED = "suspended"
        private const val KEY_SETUP_DONE = "setup_done"

        /** A starting point, not a complete list. The user extends it. */
        val STARTER_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xhamster.com", "xnxx.com",
            "redtube.com", "youporn.com", "spankbang.com", "onlyfans.com",
            "chaturbate.com", "stripchat.com", "rule34.xxx"
        )
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }
