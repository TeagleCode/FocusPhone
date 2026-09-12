package com.teaglecode.focusphone.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import org.json.JSONArray

/**
 * The eight apps pinned to the home screen, in the order they appear.
 *
 * Deliberately separate from [PolicyStore]: nothing here is a restriction, so
 * none of it goes through the 24-hour delay. Rearranging your dock is not a
 * decision your future self needs protecting from.
 */
class DockStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("focus_dock", Context.MODE_PRIVATE)

    fun packages(): List<String> {
        val raw = prefs.getString(KEY_PACKAGES, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun save(packages: List<String>) {
        val arr = JSONArray()
        packages.distinct().take(SLOTS).forEach(arr::put)
        prefs.edit().putString(KEY_PACKAGES, arr.toString()).apply()
    }

    /** True once the dock has been written at all, even if it was emptied. */
    private fun isSeeded() = prefs.contains(KEY_PACKAGES)

    /**
     * Fills the dock on first run with the things a phone is actually for.
     *
     * Resolved through intent categories rather than hardcoded package names,
     * because the dialer on a Samsung is not the dialer on a Pixel. Anything
     * that does not resolve is simply skipped, so the dock is short rather
     * than wrong. [installed] is the launchable set, which keeps a resolver
     * stub or a disabled system component from taking a slot.
     */
    fun seedIfUnset(context: Context, installed: Set<String>) {
        if (isSeeded()) return
        val pm = context.packageManager
        val picked = DEFAULT_INTENTS
            .mapNotNull { resolve(pm, it, installed - context.packageName) }
            .distinct()
            .take(SLOTS)
        save(picked)
    }

    /**
     * The package that would open [intent], or null.
     *
     * resolveActivity answers "android" when several apps match and the user
     * has set no default — the system's disambiguation stub, not an app. That
     * is why music and camera came back empty on a phone that has both. When
     * the preferred answer is not a real launchable app, the candidate list is
     * used instead and the first genuine match wins.
     */
    private fun resolve(
        pm: PackageManager,
        intent: Intent,
        installed: Set<String>
    ): String? {
        val preferred = runCatching {
            pm.resolveActivity(intent, 0)?.activityInfo?.packageName
        }.getOrNull()
        if (preferred != null && preferred in installed) return preferred

        return runCatching { pm.queryIntentActivities(intent, 0) }
            .getOrDefault(emptyList())
            .asSequence()
            .map { it.activityInfo.packageName }
            .firstOrNull { it in installed }
    }

    companion object {
        const val SLOTS = 8

        private const val KEY_PACKAGES = "packages"

        private fun category(name: String) =
            Intent(Intent.ACTION_MAIN).addCategory(name)

        /** Ordered by how often a phone is picked up for each of them. */
        private val DEFAULT_INTENTS = listOf(
            Intent(Intent.ACTION_DIAL),
            category(Intent.CATEGORY_APP_MESSAGING),
            category(Intent.CATEGORY_APP_MAPS),
            category(Intent.CATEGORY_APP_MUSIC),
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            category(Intent.CATEGORY_APP_BROWSER),
            category(Intent.CATEGORY_APP_GALLERY),
            category(Intent.CATEGORY_APP_CONTACTS)
        )
    }
}
