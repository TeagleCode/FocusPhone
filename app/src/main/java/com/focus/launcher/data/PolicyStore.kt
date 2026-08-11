package com.focus.launcher.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** How a package is restricted. */
enum class RestrictionType { NONE, TIME_LIMIT, FULL_BLOCK }

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
)

/**
 * A user request to relax a rule. Requests do not take effect immediately:
 * they unlock only after [UNLOCK_DELAY_MS] and require a final confirmation.
 */
data class PendingUnlock(
    val packageName: String,
    val requestedAtMs: Long,
    val confirmed: Boolean
) {
    fun readyAtMs() = requestedAtMs + UNLOCK_DELAY_MS
    fun isReady(now: Long) = now >= readyAtMs()

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

    // ---- Blocked websites ------------------------------------------------

    fun blockedDomains(): Set<String> =
        prefs.getStringSet(KEY_DOMAINS, emptySet()) ?: emptySet()

    fun saveBlockedDomains(domains: Set<String>) {
        prefs.edit().putStringSet(KEY_DOMAINS, domains).apply()
    }

    // ---- Pending unlocks -------------------------------------------------

    /** Only one unlock may be pending at a time, by design. */
    fun pendingUnlock(): PendingUnlock? {
        val raw = prefs.getString(KEY_PENDING, null) ?: return null
        val o = JSONObject(raw)
        return PendingUnlock(
            packageName = o.getString("pkg"),
            requestedAtMs = o.getLong("at"),
            confirmed = o.getBoolean("confirmed")
        )
    }

    fun setPendingUnlock(p: PendingUnlock?) {
        if (p == null) {
            prefs.edit().remove(KEY_PENDING).apply()
            return
        }
        val o = JSONObject().apply {
            put("pkg", p.packageName)
            put("at", p.requestedAtMs)
            put("confirmed", p.confirmed)
        }
        prefs.edit().putString(KEY_PENDING, o.toString()).apply()
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

    // ---- API key ---------------------------------------------------------

    /** Anthropic key for quiz generation. Supplied by the user, never bundled. */
    fun apiKey(): String? = prefs.getString(KEY_API, null)

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API, key.trim()).apply()
    }

    companion object {
        private const val KEY_API = "api_key"
        private const val KEY_RULES = "rules"
        private const val KEY_SECTIONS = "sections"
        private const val KEY_DOMAINS = "domains"
        private const val KEY_PENDING = "pending_unlock"
        private const val KEY_QUIZ_DATE = "quiz_date"
        private const val KEY_PENALTY_DATE = "penalty_date"
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).map { getString(it) }
