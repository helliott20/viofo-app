package io.github.helliott20.dashlink.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LocalClip(val uri: Uri, val name: String, val size: Long, val dateTaken: Long, val durationMs: Long, val isVideo: Boolean)

/** Clips this app has saved to the phone's gallery. */
object LocalMedia {
    suspend fun list(context: Context): List<LocalClip> = withContext(Dispatchers.IO) {
        val out = mutableListOf<LocalClip>()
        query(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, out)
        query(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, out)
        out.sortedByDescending { it.name }
    }

    private fun query(context: Context, collection: Uri, video: Boolean, out: MutableList<LocalClip>) {
        val cols = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            if (video) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()
        val (sel, args) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" to arrayOf("%/${Downloads.ALBUM}/%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.MediaColumns.DATA} LIKE ?" to arrayOf("%/${Downloads.ALBUM}/%")
        }
        runCatching {
            context.contentResolver.query(collection, cols, sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    out += LocalClip(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        name = c.getString(1) ?: "",
                        size = c.getLong(2),
                        dateTaken = c.getLong(3) * 1000,
                        durationMs = if (video) c.getLong(4) else 0,
                        isVideo = video,
                    )
                }
            }
        }
    }
}
