package io.github.helliott20.dashlink.api

import java.time.LocalDateTime

enum class Lens(val label: String) { Front("Front"), Rear("Rear"), Interior("Interior"), Unknown("Camera") }

enum class Category(val label: String) { Normal("Loop"), Event("Event"), Parking("Parking"), Photo("Photo") }

/** One file on the camera's SD card. */
data class CamFile(
    val name: String,
    /** Path as reported by the camera, e.g. `A:\DCIM\Movie\2026_0101_120000_0001F.MP4`. */
    val fpath: String,
    val size: Long,
    val time: LocalDateTime?,
    val lens: Lens,
    val category: Category,
    val locked: Boolean,
) {
    val isVideo get() = !name.endsWith(".JPG", true) && !name.endsWith(".JPEG", true)

    /** Path relative to the camera's web root, with forward slashes. */
    val webPath: String get() = fpath.substringAfter(":").replace('\\', '/').trimStart('/')

    /** Clips from all lenses recorded at the same moment share this key. */
    val groupKey: String get() = name.substringBeforeLast('.').trimEnd('F', 'R', 'I', 'f', 'r', 'i', '_')
}

data class CamInfo(
    val firmware: String?,
    val model: String?,
    val freeBytes: Long?,
    val totalBytes: Long?,
)

class CameraException(message: String, val status: Int? = null) : Exception(message)
