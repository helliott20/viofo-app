package io.github.helliott20.dashlink.api

import io.github.helliott20.dashlink.util.Xml
import io.github.helliott20.dashlink.util.XmlNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Client for the Novatek-style HTTP command API used by VIOFO cameras:
 * `GET http://192.168.1.254/?custom=1&cmd=<n>[&par=<p>][&str=<s>]` returning XML.
 *
 * The camera firmware falls over when it gets concurrent commands, so every command goes
 * through a single [Mutex]. Commands are retried on transient I/O errors, which smooths
 * over the brief Wi-Fi hiccups these cameras are prone to.
 */
class NovatekClient(
    private val http: OkHttpClient,
    val baseUrl: HttpUrl = "http://$DEFAULT_HOST/".toHttpUrl(),
) {
    private val lock = Mutex()

    /** Raw command. Returns the parsed `<Function>` (or root) node. */
    suspend fun command(cmd: Int, par: Int? = null, str: String? = null, retries: Int = 2): XmlNode =
        lock.withLock { withContext(Dispatchers.IO) { send(cmd, par, str, retries) } }

    private suspend fun send(cmd: Int, par: Int?, str: String?, retries: Int): XmlNode {
        val url = baseUrl.newBuilder()
            .addQueryParameter("custom", "1")
            .addQueryParameter("cmd", cmd.toString())
            .apply { if (par != null) addQueryParameter("par", par.toString()) }
            .apply { if (str != null) addQueryParameter("str", str) }
            .build()
        var last: IOException? = null
        repeat(retries + 1) { attempt ->
            try {
                http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    // A truncated/empty reply means the link hiccuped; retry like an I/O error.
                    if (body.isBlank()) throw IOException("Empty reply from camera")
                    // Firmware that doesn't know a command answers with an HTML 404 page.
                    if (body.trimStart().startsWith("<html", ignoreCase = true)) throw CameraException("Command not supported by this camera", -256)
                    val root = Xml.parse(body)
                    return root.find("Function") ?: root.find("LIST") ?: root
                }
            } catch (e: IOException) {
                last = e
                if (attempt < retries) delay(300L * (attempt + 1))
            }
        }
        throw last ?: IOException("Command $cmd failed")
    }

    /** Sends a command and throws if the camera reports a negative status. */
    suspend fun exec(cmd: Int, par: Int? = null, str: String? = null): XmlNode {
        val r = command(cmd, par, str)
        val status = r.childText("Status")?.trim()?.toIntOrNull()
        if (status != null && status < 0) throw CameraException(statusMessage(status), status)
        return r
    }

    // ---- High level operations ----

    suspend fun heartbeat(): Boolean = runCatching { command(Cmd.HEARTBEAT, retries = 0); true }.getOrDefault(false)

    suspend fun setMode(mode: Int) { exec(Cmd.MODE, mode) }

    suspend fun setRecording(on: Boolean) { exec(Cmd.RECORD, if (on) 1 else 0) }

    /** Saves a still from the live stream to the SD card. */
    suspend fun takePhoto() { exec(Cmd.SNAPSHOT) }

    /** Kicks off any previously connected phone so we become the active client. */
    suspend fun claim() { runCatching { command(Cmd.REMOVE_LAST_USER, retries = 0) } }

    suspend fun startLiveView() { runCatching { exec(Cmd.LIVE_VIEW, 1) } }
    suspend fun stopLiveView() { runCatching { exec(Cmd.LIVE_VIEW, 0) } }

    /** RTSP URL for live view; falls back to the well-known default. */
    suspend fun liveUrl(): String =
        runCatching { command(Cmd.LIVE_URL).childText("MovieLiveViewLink")?.trim() }.getOrNull()
            ?.takeIf { it.startsWith("rtsp://") || it.startsWith("http://") } ?: DEFAULT_LIVE_URL

    suspend fun cardStatus(): Int? = command(Cmd.CARD_STATUS).childText("Value")?.trim()?.toIntOrNull()

    suspend fun setMic(on: Boolean) { exec(Cmd.AUDIO, if (on) 1 else 0) }

    /** Switch the live view source. [par] null cycles to the next camera on models that support it. */
    suspend fun switchCamera(cmd: Int, par: Int?) { exec(cmd, par) }

    suspend fun restart(cmd: Int) { runCatching { command(cmd, retries = 0) } }

    /** Reads a text setting such as car number; the element name varies by model. */
    suspend fun readText(cmd: Int): String? {
        val r = command(cmd)
        return r.children.firstOrNull { c -> listOf("CarNO", "CARNUMBER", "CustomStamp", "CUSTOMTEXT", "String").any { c.name.startsWith(it, true) } }?.text
    }

    suspend fun firmware(): String? = command(Cmd.FIRMWARE).let { it.childText("String") ?: it.childText("Value") }?.trim()

    suspend fun freeSpace(): Long? = command(Cmd.FREE_SPACE).childText("Value")?.trim()?.toLongOrNull()

    /** Current value of every setting, keyed by command number. */
    suspend fun currentState(): Map<Int, String> = parseState(command(Cmd.STATE))

    suspend fun files(): List<CamFile> = parseFiles(command(Cmd.FILE_LIST, retries = 3))

    suspend fun setSetting(cmd: Int, value: Int) { exec(cmd, value) }

    suspend fun setText(cmd: Int, value: String) { exec(cmd, str = value) }

    suspend fun syncClock(now: LocalDateTime = LocalDateTime.now()) {
        exec(Cmd.SET_DATE, str = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
        exec(Cmd.SET_TIME, str = now.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
    }

    suspend fun delete(file: CamFile) { exec(Cmd.DELETE_ONE, str = file.fpath) }

    suspend fun formatCard() { exec(Cmd.FORMAT, 1) }

    suspend fun resetSettings() { exec(Cmd.RESET) }

    suspend fun wifiCredentials(): Pair<String?, String?> {
        val r = command(Cmd.GET_WIFI)
        return r.childText("SSID")?.trim() to r.childText("PASSPHRASE")?.trim()
    }

    /** Changes the camera's access point. The camera drops the link afterwards. */
    suspend fun setWifi(ssid: String?, password: String?, blank: Char, restartCmd: Int) {
        if (!ssid.isNullOrBlank()) exec(Cmd.SET_SSID, str = ssid.replace(' ', blank))
        if (!password.isNullOrBlank()) exec(Cmd.SET_PASSWORD, str = password)
        if (!ssid.isNullOrBlank()) restart(restartCmd)
        else runCatching { command(Cmd.RECONNECT_WIFI, retries = 0) }
    }

    fun fileUrl(file: CamFile): String = baseUrl.newBuilder().addPathSegments(file.webPath).build().toString()

    fun previewUrl(file: CamFile): String =
        baseUrl.newBuilder().addPathSegments(file.webPath)
            .addQueryParameter("custom", "1").addQueryParameter("cmd", Cmd.SCREEN.toString()).build().toString()

    fun thumbnailUrl(file: CamFile): String =
        baseUrl.newBuilder().addPathSegments(file.webPath)
            .addQueryParameter("custom", "1").addQueryParameter("cmd", Cmd.THUMB.toString()).build().toString()

    companion object {
        const val DEFAULT_HOST = "192.168.1.254"
        const val DEFAULT_LIVE_URL = "rtsp://192.168.1.254/xxx.mov"
        const val NOTIFY_PORT = 3333
        const val DEFAULT_PASSWORD = "12345678"

        fun statusMessage(status: Int): String = when (status) {
            -1 -> "File not found"
            -3 -> "Camera is out of memory, try again"
            -4 -> "File is locked (event/protected clip)"
            -5 -> "File error"
            -6 -> "Delete failed"
            -7, -11 -> "Storage is full"
            -8 -> "SD card write error"
            -9 -> "SD card is too slow"
            -10 -> "Battery low"
            -12 -> "Folder is full"
            -13 -> "Camera refused the command"
            -21 -> "Invalid value for this camera"
            -256 -> "Not supported by this camera"
            else -> "Camera error ($status)"
        }

        fun parseState(node: XmlNode): Map<Int, String> {
            // <Function><Cmd>2002</Cmd><Status>1</Status><Cmd>2003</Cmd><Status>2</Status>…
            val out = LinkedHashMap<Int, String>()
            var pending: Int? = null
            for (c in node.children) {
                when {
                    c.name.equals("Cmd", true) -> pending = c.text.trim().toIntOrNull()
                    c.name.equals("Status", true) -> pending?.let { out[it] = c.text.trim() }
                    // Multi-lens values (resolution, EV) arrive as a <String> after the Status.
                    c.name.equals("String", true) -> pending?.let { out[it] = c.text.trim() }
                }
            }
            return out
        }

        private val nameTime = Regex("""(\d{4})_?(\d{2})(\d{2})_?(\d{2})(\d{2})(\d{2})""")
        private val listTime = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

        fun parseFiles(node: XmlNode): List<CamFile> = node.findAll("File").mapNotNull { f ->
            val name = f.childText("NAME")?.trim() ?: return@mapNotNull null
            val fpath = f.childText("FPATH")?.trim() ?: name
            val attr = f.childText("ATTR")?.trim()?.toIntOrNull() ?: 0
            val listed = f.childText("TIME")?.trim()?.let { runCatching { LocalDateTime.parse(it, listTime) }.getOrNull() }
            // Cameras with an unset clock report year 2000; the file name is more trustworthy.
            val time = listed?.takeIf { it.year > 2000 }
                ?: nameTime.find(name)?.destructured?.let { (y, mo, d, h, mi, s) ->
                    runCatching { LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt()) }.getOrNull()
                } ?: listed
            CamFile(
                name = name,
                fpath = fpath,
                size = f.childText("SIZE")?.trim()?.toLongOrNull() ?: 0,
                time = time,
                lens = lensOf(name),
                category = categoryOf(fpath, name),
                locked = categoryOf(fpath, name) == Category.Event,
            )
        }.sortedByDescending { it.time }

        fun lensOf(name: String): Lens {
            val stem = name.substringBeforeLast('.').uppercase()
            return when {
                stem.endsWith("F") || stem.endsWith("T") -> Lens.Front
                stem.endsWith("R") -> Lens.Rear
                stem.endsWith("I") -> Lens.Interior
                else -> Lens.Unknown
            }
        }

        fun categoryOf(fpath: String, name: String): Category {
            val p = fpath.uppercase().replace('/', '\\')
            // Mirrors CameraFileModel in the official app.
            return when {
                name.endsWith(".JPG", true) || name.endsWith(".PNG", true) || "\\PHOTO" in p -> Category.Photo
                "\\RO\\" in p || p.endsWith("\\RO") || "EVT_REC" in p || "MANUAL_REC" in p || "\\EVENT" in p -> Category.Event
                "PARKING" in p -> Category.Parking
                else -> Category.Normal
            }
        }
    }
}

/** Command numbers. */
object Cmd {
    const val PHOTO = 1001
    const val RECORD = 2001
    const val REC_SIZE = 2002
    const val CYCLIC = 2003
    const val WDR = 2004
    const val EV = 2005
    const val MOTION = 2006
    const val AUDIO = 2007
    const val DATE_STAMP = 2008
    const val GSENSOR = 2011
    const val LIVE_VIEW = 2015
    const val RECORDING_TIME = 2016
    const val SNAPSHOT = 2017
    const val LIVE_URL = 2019
    const val MODE = 3001
    const val SET_SSID = 3003
    const val SET_PASSWORD = 3004
    const val SET_DATE = 3005
    const val SET_TIME = 3006
    const val FORMAT = 3010
    const val RESET = 3011
    const val FIRMWARE = 3012
    const val STATE = 3014
    const val FILE_LIST = 3015
    const val HEARTBEAT = 3016
    const val FREE_SPACE = 3017
    const val RECONNECT_WIFI = 3018
    const val CARD_STATUS = 3024
    const val PIP = 3028
    const val GET_WIFI = 3029
    const val REMOVE_LAST_USER = 3023
    const val THUMB = 4001
    const val SCREEN = 4002
    const val DELETE_ONE = 4003
    const val DELETE_ALL = 4004

    const val MODE_PHOTO = 0
    const val MODE_MOVIE = 1
    const val MODE_PLAYBACK = 2
}
