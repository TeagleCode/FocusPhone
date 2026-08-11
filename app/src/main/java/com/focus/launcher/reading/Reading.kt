package com.focus.launcher.reading

import android.content.Context
import android.net.Uri
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.policy.Enforcer
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.Date
import java.util.zip.ZipInputStream

data class Chapter(val title: String, val text: String)

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int
)

/**
 * Reads chapters out of an EPUB. EPUBs are ZIP archives of XHTML, so this
 * pulls every XHTML entry in spine order-ish (filename order) and strips tags.
 */
object EpubReader {

    fun chapters(context: Context, uri: Uri): List<Chapter> {
        val out = mutableListOf<Chapter>()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory &&
                        (name.endsWith(".xhtml") || name.endsWith(".html") ||
                            name.endsWith(".htm"))
                    ) {
                        val raw = BufferedReader(InputStreamReader(zip)).readText()
                        val text = stripHtml(raw)
                        if (text.length > MIN_CHAPTER_CHARS) {
                            out += Chapter(
                                title = titleOf(raw) ?: name.substringAfterLast('/'),
                                text = text
                            )
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        return out.sortedBy { it.title }
    }

    private fun titleOf(html: String): String? =
        Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    private fun stripHtml(html: String): String = html
        .replace(Regex("<script.*?</script>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<style.*?</style>", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private const val MIN_CHAPTER_CHARS = 800
}

/**
 * Generates comprehension questions from a chapter using the Anthropic API.
 * The key is supplied by the user in settings; it is never bundled.
 */
class QuizGenerator(private val apiKey: String) {

    fun generate(chapter: Chapter, count: Int = 5): List<QuizQuestion> {
        val excerpt = chapter.text.take(MAX_CHARS)
        val prompt = """
            Read the following book chapter and write $count multiple-choice
            comprehension questions about it. The questions must be answerable
            only by someone who actually read it: ask about specific events,
            arguments, names and details, not general themes.

            Respond with JSON only, no prose and no markdown fences, as an
            array of objects with keys: question (string), options (array of
            exactly 4 strings), correctIndex (integer 0-3).

            CHAPTER:
            $excerpt
        """.trimIndent()

        val body = JSONObject().apply {
            put("model", "claude-sonnet-4-6")
            put("max_tokens", 2000)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }

        val response = post(body.toString())
        val text = JSONObject(response)
            .getJSONArray("content")
            .let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("\n") { it.optString("text") }
            }
            .replace("```json", "")
            .replace("```", "")
            .trim()

        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val opts = o.getJSONArray("options")
            QuizQuestion(
                question = o.getString("question"),
                options = (0 until opts.length()).map { opts.getString(it) },
                correctIndex = o.getInt("correctIndex")
            )
        }
    }

    private fun post(payload: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        conn.outputStream.use { it.write(payload.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val MAX_CHARS = 24_000
    }
}

/**
 * Applies the consequence of the daily reading quiz.
 *
 * Deliberately scoped: a failed quiz restricts the apps already under a rule
 * (social and entertainment) for the following day. Phone, messaging, maps,
 * banking and transport are never touched, so a bad night can never leave the
 * device unusable when it is actually needed.
 */
class ReadingPenalty(private val context: Context) {

    private val store = PolicyStore(context)

    fun recordResult(passed: Boolean, score: Int, total: Int) {
        val today = Enforcer.dateKey(Date())
        if (passed && score * 2 >= total) {
            store.markQuizPassed(today)
            store.setPenaltyDate(null)
        } else {
            store.setPenaltyDate(tomorrowKey())
        }
        Enforcer(context).apply()
    }

    fun penaltyActiveToday(): Boolean =
        store.penaltyDate() == Enforcer.dateKey(Date())

    private fun tomorrowKey(): String {
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        return Enforcer.dateKey(cal.time)
    }
}
