package com.focus.launcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * One item on the daily agenda.
 *
 * A recurring task appears on every day from [createdDate] onward. A one-off
 * task appears only on [createdDate]. Both carry the creation date so that
 * yesterday's agenda can be reconstructed honestly — a task added today must
 * never count retroactively against a day that had already ended.
 */
data class TodoTask(
    val id: String,
    val text: String,
    val recurring: Boolean,
    val createdDate: String
)

/** Today's progress, for the home screen header. */
data class AgendaProgress(val done: Int, val total: Int) {
    val complete get() = total > 0 && done == total
    val empty get() = total == 0
}

class TodoStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("focus_todo", Context.MODE_PRIVATE)

    // ---- Tasks -----------------------------------------------------------

    fun tasks(): List<TodoTask> {
        val raw = prefs.getString(KEY_TASKS, "[]") ?: "[]"
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            TodoTask(
                id = o.getString("id"),
                text = o.getString("text"),
                recurring = o.optBoolean("recurring", false),
                createdDate = o.getString("created")
            )
        }
    }

    private fun saveTasks(tasks: List<TodoTask>) {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("text", t.text)
                put("recurring", t.recurring)
                put("created", t.createdDate)
            })
        }
        prefs.edit().putString(KEY_TASKS, arr.toString()).apply()
    }

    fun add(text: String, recurring: Boolean, onDate: String = todayKey()): TodoTask {
        val task = TodoTask(
            id = java.util.UUID.randomUUID().toString().take(8),
            text = text.trim(),
            recurring = recurring,
            createdDate = onDate
        )
        saveTasks(tasks() + task)
        return task
    }

    fun remove(id: String) {
        saveTasks(tasks().filterNot { it.id == id })
    }

    fun rename(id: String, text: String) {
        saveTasks(tasks().map { if (it.id == id) it.copy(text = text.trim()) else it })
    }

    /**
     * What is due on [date]: every recurring task that existed by then, plus
     * the one-off tasks belonging to that exact day.
     */
    fun agenda(date: String): List<TodoTask> =
        tasks().filter {
            if (it.recurring) it.createdDate <= date else it.createdDate == date
        }

    // ---- Completion ------------------------------------------------------

    private fun completionMap(): JSONObject =
        JSONObject(prefs.getString(KEY_DONE, "{}") ?: "{}")

    fun completed(date: String): Set<String> {
        val arr = completionMap().optJSONArray(date) ?: return emptySet()
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun isDone(date: String, id: String) = id in completed(date)

    fun toggle(date: String, id: String) {
        val map = completionMap()
        val current = completed(date)
        val next = if (id in current) current - id else current + id
        if (next.isEmpty()) map.remove(date) else map.put(date, JSONArray(next.toList()))
        prefs.edit().putString(KEY_DONE, prune(map).toString()).apply()
    }

    /**
     * Completion records are only ever read for today and yesterday, so
     * anything older is dropped rather than growing without bound.
     */
    private fun prune(map: JSONObject): JSONObject {
        val cutoff = dayKey(-KEEP_DAYS)
        val out = JSONObject()
        map.keys().forEach { key ->
            if (key >= cutoff) out.put(key, map.get(key))
        }
        return out
    }

    fun progress(date: String): AgendaProgress {
        val due = agenda(date)
        val done = completed(date)
        return AgendaProgress(due.count { it.id in done }, due.size)
    }

    fun allDone(date: String): Boolean = progress(date).complete

    /**
     * Progress for every day in a month, computed from a single read.
     *
     * The calendar draws up to 31 cells, and going back to the store for each
     * one would re-parse the whole task list 31 times per frame.
     */
    fun monthSummary(year: Int, monthZeroBased: Int): Map<String, AgendaProgress> {
        val all = tasks()
        val recurring = all.filter { it.recurring }
        val oneOff = all.filterNot { it.recurring }.groupBy { it.createdDate }
        val doneMap = completionMap()

        val cal = Calendar.getInstance().apply {
            clear()
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, monthZeroBased)
            set(Calendar.DAY_OF_MONTH, 1)
        }
        val days = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        return (1..days).associate { day ->
            val key = String.format(Locale.US, "%04d-%02d-%02d", year, monthZeroBased + 1, day)
            val due = recurring.filter { it.createdDate <= key } + (oneOff[key] ?: emptyList())
            val doneArr = doneMap.optJSONArray(key)
            val done = if (doneArr == null) emptySet()
            else (0 until doneArr.length()).map { doneArr.getString(it) }.toSet()
            key to AgendaProgress(due.count { it.id in done }, due.size)
        }
    }

    // ---- The consequence -------------------------------------------------

    /**
     * True when yesterday had an agenda that was left unfinished. Derived
     * rather than stored, so it corrects itself: it cannot be left stuck on by
     * a missed midnight job, and a day with no tasks never triggers it.
     */
    fun socialLockedToday(): Boolean {
        val yesterday = dayKey(-1)
        val due = agenda(yesterday)
        if (due.isEmpty()) return false
        val done = completed(yesterday)
        return due.any { it.id !in done }
    }

    /** What was left undone yesterday, for explaining the lockout. */
    fun missedYesterday(): List<TodoTask> {
        val yesterday = dayKey(-1)
        val done = completed(yesterday)
        return agenda(yesterday).filter { it.id !in done }
    }

    companion object {
        private const val KEY_TASKS = "tasks"
        private const val KEY_DONE = "completed"
        private const val KEEP_DAYS = 45

        fun todayKey(): String = dayKey(0)

        fun dayKey(offsetDays: Int): String {
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offsetDays) }
            return format(cal.time)
        }

        fun format(date: Date): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
    }
}
