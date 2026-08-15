package com.teaglecode.focusphone.reading

import android.content.Context
import android.net.Uri
import com.teaglecode.focusphone.data.PolicyStore
import com.teaglecode.focusphone.policy.Enforcer
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

/** Raised when a book cannot be read, with a message fit to show the user. */
class ReadingSourceException(message: String) : Exception(message)

/**
 * Where a chapter comes from. EPUB is the only file format implemented, but the
 * quiz path talks to this interface rather than to the EPUB parser, so a PDF or
 * plain-text source can be added later without touching the quiz code.
 */
interface ChapterSource {
    val label: String
    fun chapters(): List<Chapter>
}

/**
 * Reads chapters out of an EPUB. EPUBs are ZIP archives of XHTML, so this pulls
 * every XHTML entry and orders them by entry name, which approximates spine
 * order. Ordering by title instead would scramble the book, because chapter
 * titles rarely sort the way the chapters actually run.
 */
class EpubSource(
    private val context: Context,
    private val uri: Uri
) : ChapterSource {

    override val label = "epub"

    override fun chapters(): List<Chapter> {
        val found = mutableListOf<Pair<String, Chapter>>()
        var encrypted = false

        val stream = context.contentResolver.openInputStream(uri)
            ?: throw ReadingSourceException("That file could not be opened.")

        stream.use { input ->
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name

                    // Commercial stores wrap content in an encryption layer and
                    // record it here. Detecting it lets us say what is wrong
                    // instead of showing an empty chapter list.
                    if (name.equals("META-INF/encryption.xml", ignoreCase = true)) {
                        encrypted = true
                    }

                    if (!entry.isDirectory && name.isMarkup()) {
                        val raw = BufferedReader(InputStreamReader(zip)).readText()
                        val text = stripHtml(raw)
                        if (text.length > MIN_CHAPTER_CHARS) {
                            found += name to Chapter(
                                title = titleOf(raw) ?: name.substringAfterLast('/'),
                                text = text
                            )
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }

        if (encrypted) {
            throw ReadingSourceException(
                "This book is DRM-protected, so its text cannot be read. " +
                    "Use a DRM-free EPUB, or paste the chapter text instead."
            )
        }
        if (found.isEmpty()) {
            throw ReadingSourceException(
                "No readable text was found in that file. It may not be a valid " +
                    "EPUB. Pasting the text instead will work."
            )
        }

        return found.sortedBy { it.first }.map { it.second }
    }

    private fun String.isMarkup() =
        endsWith(".xhtml", true) || endsWith(".html", true) || endsWith(".htm", true)

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
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("\\s+"), " ")
        .trim()

    companion object {
        private const val MIN_CHAPTER_CHARS = 800
    }
}

/**
 * Text the user pasted in. The escape hatch for books that only exist as PDFs
 * or as files this parser cannot read.
 */
class PastedTextSource(private val raw: String) : ChapterSource {

    override val label = "pasted text"

    override fun chapters(): List<Chapter> {
        val text = raw.replace(Regex("\\s+"), " ").trim()
        if (text.length < MIN_CHARS) {
            throw ReadingSourceException(
                "That is too short to quiz on. Paste at least a few paragraphs."
            )
        }
        return listOf(Chapter(title = "pasted text", text = text))
    }

    companion object {
        private const val MIN_CHARS = 500
    }
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
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            }))
        }

        val response = JSONObject(post(body.toString()))

        // A refusal or a truncated response both come back as HTTP 200, so the
        // status code alone does not tell us the request succeeded.
        when (response.optString("stop_reason")) {
            "refusal" -> throw ReadingSourceException(
                "The model declined to answer about this text."
            )
            "max_tokens" -> throw ReadingSourceException(
                "The reply was cut off before all questions were written. " +
                    "Try a shorter chapter."
            )
        }

        val text = response
            .getJSONArray("content")
            .let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .filter { it.optString("type") == "text" }
                    .joinToString("\n") { it.optString("text") }
            }
            .stripFences()

        val arr = runCatching { JSONArray(text) }.getOrElse {
            throw ReadingSourceException("The model's reply was not valid JSON.")
        }

        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val opts = o.optJSONArray("options") ?: return@mapNotNull null
            val options = (0 until opts.length()).map { opts.optString(it) }
            val correct = o.optInt("correctIndex", -1)
            // Drop anything malformed rather than crashing the whole quiz, and
            // reject an out-of-range answer index, which would make the
            // question unanswerable.
            if (options.size < 2 || correct !in options.indices) return@mapNotNull null
            QuizQuestion(
                question = o.optString("question").ifBlank { return@mapNotNull null },
                options = options,
                correctIndex = correct
            )
        }.ifEmpty {
            throw ReadingSourceException("No usable questions came back. Try again.")
        }
    }

    /**
     * The prompt asks for bare JSON, but models sometimes wrap it in a fenced
     * block anyway, so strip fences before parsing rather than trusting it.
     */
    private fun String.stripFences(): String {
        val trimmed = trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun post(payload: String): String {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
        }

        conn.outputStream.use { it.write(payload.toByteArray()) }

        val code = conn.responseCode
        if (code !in 200..299) {
            // The error body carries the actual reason — an invalid key, a
            // spent credit balance, a rate limit. Without reading it every
            // failure looks identical to the user.
            val detail = conn.errorStream?.bufferedReader()?.use { it.readText() }
            throw ReadingSourceException(describe(code, detail))
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun describe(code: Int, body: String?): String {
        val apiMessage = body
            ?.let { runCatching { JSONObject(it).getJSONObject("error").optString("message") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        return when (code) {
            401 -> "That API key was rejected. Check it in settings."
            403 -> "That API key is not allowed to use this model."
            429 -> "Rate limited by the API. Wait a moment and try again."
            in 500..599 -> "The API is having trouble. Try again shortly."
            else -> apiMessage ?: "The API returned an error ($code)."
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * Pinned by the project specification. Note this model does not support
         * structured outputs, which is why the reply is parsed defensively
         * above rather than constrained by a JSON schema.
         */
        private const val MODEL = "claude-sonnet-4-6"

        /** Five questions with four options each, with headroom to spare. */
        private const val MAX_TOKENS = 4096
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

    fun recordResult(score: Int, total: Int) {
        val today = Enforcer.dateKey(Date())
        if (passes(score, total)) {
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

    companion object {
        /** Passing is 50% or better. */
        fun passes(score: Int, total: Int) = total > 0 && score * 2 >= total
    }
}
