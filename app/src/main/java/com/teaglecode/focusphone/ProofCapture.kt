package com.teaglecode.focusphone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.teaglecode.focusphone.data.TodoStore

/**
 * Recording a task's proof, by handing off to the camera the phone already has.
 *
 * Deliberately not CameraX: an in-app recorder would mean the CAMERA and
 * RECORD_AUDIO permissions, a preview surface, an encoder and about a megabyte
 * of dependency, to end up worse than the camera app the user already trusts.
 * ACTION_VIDEO_CAPTURE costs no permission at all — the clip is written
 * straight into app-private storage through a FileProvider grant that expires
 * with the capture.
 */
object Proof {

    private fun authority(context: Context) = "${context.packageName}.proof"

    fun uriFor(context: Context, todos: TodoStore, date: String, taskId: String) =
        FileProvider.getUriForFile(
            context,
            authority(context),
            todos.proofFile(date, taskId).apply { parentFile?.mkdirs() }
        )

    /** Plays a clip back in whatever the phone uses for video. */
    fun view(context: Context, todos: TodoStore, date: String, taskId: String): Boolean {
        if (!todos.hasProof(date, taskId)) return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uriFor(context, todos, date, taskId), "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

/**
 * Returns a function that films proof for one task and, if the camera comes
 * back with a clip, ticks it off.
 *
 * [onResult] reports what happened so a screen can refresh and explain itself:
 * a cancelled capture is not a failure, but a phone with no camera app is
 * something the user needs told rather than a button that does nothing.
 */
@Composable
fun rememberProofRecorder(
    todos: TodoStore,
    onResult: (ProofResult) -> Unit
): (String, String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val (date, taskId) = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.resultCode == Activity.RESULT_OK && todos.hasProof(date, taskId)) {
            todos.completeWithProof(date, taskId)
            onResult(ProofResult.Recorded)
        } else {
            // A cancelled capture still leaves the empty file the camera was
            // pointed at, and an empty file would read as proof next time.
            todos.proofFile(date, taskId).delete()
            onResult(ProofResult.Cancelled)
        }
    }

    return { date, taskId ->
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            .putExtra(MediaStore.EXTRA_OUTPUT, Proof.uriFor(context, todos, date, taskId))
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, TodoStore.CLIP_SECONDS)
            // Low quality is a hint many camera apps ignore, but where it is
            // honoured it is the difference between 3MB and 30MB a clip.
            .putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0)
            .addFlags(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        pending = date to taskId
        if (runCatching { launcher.launch(intent) }.isFailure) {
            pending = null
            todos.proofFile(date, taskId).delete()
            onResult(ProofResult.NoCamera)
        }
    }
}

enum class ProofResult { Recorded, Cancelled, NoCamera }
