package com.focus.launcher

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focus.launcher.data.PolicyStore
import com.focus.launcher.reading.*
import com.focus.launcher.ui.Focus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Daily reading: open an EPUB, read a chapter, answer questions about it.
 *
 * Failing the quiz restricts the apps that already carry a rule for the
 * following day. Calls, messages, maps, banking and transport are never
 * touched by this, so a missed quiz can never leave the phone unusable.
 */
class ReadingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReadingScreen() }
    }
}

private enum class Stage { PICK, CHAPTERS, READ, QUIZ, RESULT }

@Composable
private fun ReadingScreen() {
    val context = LocalContext.current
    val store = remember { PolicyStore(context) }
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(Stage.PICK) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var chapter by remember { mutableStateOf<Chapter?>(null) }
    var questions by remember { mutableStateOf<List<QuizQuestion>>(emptyList()) }
    var answers by remember { mutableStateOf<Map<Int, Int>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var score by remember { mutableStateOf(0) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { EpubReader.chapters(context, uri) }
            }
            busy = false
            result.onSuccess {
                if (it.isEmpty()) error = "No readable text found. The file may be DRM-protected."
                else { chapters = it; stage = Stage.CHAPTERS }
            }.onFailure { error = "Could not open that file." }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Focus.Ink)
            .padding(horizontal = Focus.Gutter)
    ) {
        Spacer(Modifier.height(72.dp))

        when (stage) {
            Stage.PICK -> {
                Heading("today's reading")
                Spacer(Modifier.height(10.dp))
                Body("Open an EPUB, read a chapter, then answer questions on it.")
                Spacer(Modifier.height(26.dp))
                Action("open a book") { picker.launch(arrayOf("application/epub+zip", "*/*")) }
            }

            Stage.CHAPTERS -> {
                Heading("choose a chapter")
                Spacer(Modifier.height(18.dp))
                LazyColumn {
                    items(chapters) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(Focus.RadiusRow))
                                .background(Focus.Surface)
                                .clickable { chapter = c; stage = Stage.READ }
                                .padding(horizontal = 16.dp, vertical = 15.dp)
                        ) {
                            Column {
                                Text(c.title, color = Focus.Primary, fontSize = 16.sp)
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    "${c.text.split(" ").size} words",
                                    color = Focus.Ghost,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }

            Stage.READ -> {
                Heading(chapter?.title ?: "")
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        chapter?.text.orEmpty(),
                        color = Focus.Bone.copy(alpha = 0.82f),
                        fontSize = 17.sp,
                        lineHeight = 29.sp
                    )
                    Spacer(Modifier.height(32.dp))
                }
                Action(if (busy) "preparing questions…" else "take the quiz") {
                    if (busy) return@Action
                    val key = store.apiKey()
                    if (key.isNullOrBlank()) {
                        error = "Add an Anthropic API key in settings first."
                        return@Action
                    }
                    busy = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { QuizGenerator(key).generate(chapter!!) }
                        }
                        busy = false
                        result.onSuccess { questions = it; answers = emptyMap(); stage = Stage.QUIZ }
                            .onFailure { error = "Could not generate questions. Check the key and your connection." }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            Stage.QUIZ -> {
                Heading("questions")
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    questions.forEachIndexed { qi, q ->
                        Text(q.question, color = Focus.Primary, fontSize = 16.sp, lineHeight = 24.sp)
                        Spacer(Modifier.height(10.dp))
                        q.options.forEachIndexed { oi, option ->
                            val selected = answers[qi] == oi
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(Focus.RadiusRow))
                                    .background(if (selected) Focus.SurfacePressed else Focus.Surface)
                                    .clickable { answers = answers + (qi to oi) }
                                    .padding(horizontal = 16.dp, vertical = 13.dp)
                            ) {
                                Text(
                                    option,
                                    color = if (selected) Focus.Primary else Focus.Secondary,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(26.dp))
                    }
                }
                Action("submit") {
                    score = questions.indices.count { answers[it] == questions[it].correctIndex }
                    val passed = score * 2 >= questions.size
                    ReadingPenalty(context).recordResult(passed, score, questions.size)
                    stage = Stage.RESULT
                }
                Spacer(Modifier.height(24.dp))
            }

            Stage.RESULT -> {
                val passed = score * 2 >= questions.size
                Heading(if (passed) "passed" else "not passed")
                Spacer(Modifier.height(12.dp))
                Body("$score of ${questions.size} correct.")
                Spacer(Modifier.height(10.dp))
                Body(
                    if (passed) "Nothing changes. Same again tomorrow."
                    else "Your restricted apps stay closed tomorrow. Calls, messages, maps, " +
                        "banking and transport are unaffected."
                )
            }
        }

        error?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = Focus.Secondary, fontSize = Focus.MetaSize, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun Heading(text: String) = Text(
    text,
    color = Focus.Primary,
    fontSize = 26.sp,
    fontWeight = FontWeight.Light,
    letterSpacing = Focus.Tracking,
    lineHeight = 32.sp
)

@Composable
private fun Body(text: String) = Text(
    text,
    color = Focus.Tertiary,
    fontSize = 14.sp,
    lineHeight = 21.sp
)

@Composable
private fun Action(label: String, onClick: () -> Unit) = Text(
    label,
    color = Focus.Primary,
    fontSize = 16.sp,
    modifier = Modifier
        .clip(RoundedCornerShape(Focus.RadiusRow))
        .background(Focus.Surface)
        .clickable(onClick = onClick)
        .padding(horizontal = 24.dp, vertical = 14.dp)
)
