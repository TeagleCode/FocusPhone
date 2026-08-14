package com.focus.launcher.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LaunchableApp(val label: String, val packageName: String) {
    /** Precomputed once, because the search field filters on every keystroke. */
    val lowerLabel: String = label.lowercase()
}

/**
 * The installed-app list, loaded once and kept warm for the process.
 *
 * Resolving launcher activities and calling loadLabel on each result costs
 * tens of milliseconds per app; doing that inside composition, on the main
 * thread, on every home press is what made the launcher feel slow. Callers
 * now get the cached list instantly and a refresh happens behind them.
 */
object AppCatalog {

    /** Long enough that repeated home presses are free, short enough to notice a new install. */
    private const val TTL_MS = 60_000L

    @Volatile
    private var cached: List<LaunchableApp>? = null

    @Volatile
    private var labels: Map<String, String> = emptyMap()

    @Volatile
    private var loadedAtMs = 0L

    private val loadMutex = Mutex()

    /** Whatever is already known, without touching the package manager. */
    fun snapshot(): List<LaunchableApp>? = cached

    /** A label lookup that never hits the package manager on the main thread. */
    fun labelFor(pkg: String): String = labels[pkg] ?: pkg

    suspend fun load(context: Context, force: Boolean = false): List<LaunchableApp> {
        if (!force && isFresh()) cached?.let { return it }
        return loadMutex.withLock {
            if (!force && isFresh()) cached?.let { return it }
            val loaded = withContext(Dispatchers.IO) { query(context) }
            cached = loaded
            labels = loaded.associate { it.packageName to it.label }
            loadedAtMs = System.currentTimeMillis()
            loaded
        }
    }

    private fun isFresh() = System.currentTimeMillis() - loadedAtMs < TTL_MS

    private fun query(context: Context): List<LaunchableApp> {
        val pm = context.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0)
            .asSequence()
            .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.lowerLabel }
            .toList()
    }
}
