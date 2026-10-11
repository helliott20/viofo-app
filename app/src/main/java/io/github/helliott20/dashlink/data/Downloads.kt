package io.github.helliott20.dashlink.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

enum class DownloadState { Queued, Running, Paused, Done, Failed }

data class DownloadTask(
    val id: Long,
    val name: String,
    val url: String,
    val totalBytes: Long,
    val bytes: Long = 0,
    val state: DownloadState = DownloadState.Queued,
    val error: String? = null,
    val savedUri: Uri? = null,
    val bytesPerSecond: Long = 0,
) {
    val progress: Float get() = if (totalBytes > 0) (bytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    val isActive get() = state == DownloadState.Queued || state == DownloadState.Running
}

/**
 * Sequential, resumable download queue. Files land in Movies/DashLink (or Pictures/DashLink
 * for photos) so they show up in the phone gallery. Interrupted transfers resume with an
 * HTTP Range request instead of starting over, which matters on flaky camera Wi-Fi.
 */
class Downloads(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val ids = AtomicLong(1)
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()
    private var worker: Job? = null

    /** Partial files live here until complete, so a resume never touches the gallery copy. */
    private val partialDir get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "partial").apply { mkdirs() }

    fun enqueue(name: String, url: String, size: Long) {
        if (_tasks.value.any { it.url == url && (it.isActive || it.state == DownloadState.Done) }) return
        _tasks.update { it + DownloadTask(ids.getAndIncrement(), name, url, size) }
        ensureWorker()
    }

    fun retry(id: Long) {
        _tasks.update { l -> l.map { if (it.id == id && it.state in setOf(DownloadState.Failed, DownloadState.Paused)) it.copy(state = DownloadState.Queued, error = null) else it } }
        ensureWorker()
    }

    fun retryAllFailed() {
        _tasks.update { l -> l.map { if (it.state == DownloadState.Failed || it.state == DownloadState.Paused) it.copy(state = DownloadState.Queued, error = null) else it } }
        ensureWorker()
    }

    fun cancel(id: Long) {
        val t = _tasks.value.firstOrNull { it.id == id } ?: return
        _tasks.update { l -> l.filterNot { it.id == id } }
        if (t.state != DownloadState.Done) partialFile(t).delete()
    }

    fun clearFinished() = _tasks.update { l -> l.filter { it.state != DownloadState.Done } }

    /** Pauses everything, e.g. when the camera link drops. Paused items resume on [retryAllFailed]. */
    fun pauseAll() {
        worker?.cancel()
        worker = null
        _tasks.update { l -> l.map { if (it.isActive) it.copy(state = DownloadState.Paused, bytesPerSecond = 0) else it } }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        worker = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val next = _tasks.value.firstOrNull { it.state == DownloadState.Queued } ?: break
                runTask(next)
            }
        }
    }

    private fun set(id: Long, f: (DownloadTask) -> DownloadTask) = _tasks.update { l -> l.map { if (it.id == id) f(it) else it } }

    private fun partialFile(t: DownloadTask) = File(partialDir, t.name + ".part")

    private suspend fun runTask(task: DownloadTask) {
        set(task.id) { it.copy(state = DownloadState.Running, error = null) }
        var attempt = 0
        while (true) {
            try {
                transfer(task)
                val uri = publish(task)
                set(task.id) { it.copy(state = DownloadState.Done, bytes = it.totalBytes.coerceAtLeast(it.bytes), savedUri = uri, bytesPerSecond = 0) }
                return
            } catch (e: IOException) {
                attempt++
                Log.w(TAG, "Download ${task.name} attempt $attempt failed", e)
                if (attempt >= MAX_ATTEMPTS || !scope.isActive) {
                    set(task.id) { it.copy(state = DownloadState.Failed, error = e.message ?: "Network error", bytesPerSecond = 0) }
                    return
                }
                delay(1_000L * attempt * attempt)
            }
        }
    }

    private suspend fun transfer(task: DownloadTask) = withContext(Dispatchers.IO) {
        val part = partialFile(task)
        val have = if (part.exists()) part.length() else 0L
        if (task.totalBytes in 1..have) return@withContext
        val req = Request.Builder().url(task.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Camera returned HTTP ${resp.code}")
            val append = have > 0 && resp.code == 206
            val body = resp.body ?: throw IOException("Empty response")
            var done = if (append) have else 0L
            val buf = ByteArray(256 * 1024)
            var windowStart = System.nanoTime()
            var windowBytes = 0L
            java.io.FileOutputStream(part, append).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        if (!isActive) throw IOException("Cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        windowBytes += n
                        val now = System.nanoTime()
                        if (now - windowStart > 500_000_000L) {
                            val bps = windowBytes * 1_000_000_000L / (now - windowStart)
                            set(task.id) { it.copy(bytes = done, bytesPerSecond = bps) }
                            windowStart = now; windowBytes = 0
                        }
                    }
                }
            }
            if (task.totalBytes > 0 && done < task.totalBytes) throw IOException("Connection dropped at ${done * 100 / task.totalBytes}%")
        }
    }

    /** Moves the finished file into shared storage so it appears in the system gallery. */
    private suspend fun publish(task: DownloadTask): Uri? = withContext(Dispatchers.IO) {
        val part = partialFile(task)
        val isPhoto = task.name.endsWith(".jpg", true) || task.name.endsWith(".jpeg", true)
        val mime = when {
            isPhoto -> "image/jpeg"
            task.name.endsWith(".ts", true) -> "video/mp2t"
            task.name.endsWith(".mov", true) -> "video/quicktime"
            else -> "video/mp4"
        }
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isPhoto) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, task.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, (if (isPhoto) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES) + "/" + ALBUM)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create file in gallery")
            resolver.openOutputStream(uri)?.use { out -> copy(part, out) } ?: throw IOException("Couldn't write file")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(if (isPhoto) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_MOVIES), ALBUM).apply { mkdirs() }
            val dest = File(dir, task.name)
            dest.outputStream().use { copy(part, it) }
            android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            Uri.fromFile(dest)
        }
        part.delete()
        uri
    }

    private fun copy(src: File, out: OutputStream) = src.inputStream().use { it.copyTo(out, 1 shl 20) }

    companion object {
        const val ALBUM = "DashLink"
        private const val TAG = "Downloads"
        private const val MAX_ATTEMPTS = 6
    }
}
