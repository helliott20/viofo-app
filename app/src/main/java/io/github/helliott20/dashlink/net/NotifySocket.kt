package io.github.helliott20.dashlink.net

import android.util.Log
import io.github.helliott20.dashlink.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress

/**
 * The camera pushes status events (recording started/stopped, mic, SD card removed,
 * powering off…) as small XML documents over TCP port 3333. Unlike the stock app we
 * buffer across reads (a message can be split or several can arrive together) and
 * reconnect automatically.
 */
class NotifySocket(
    private val host: String,
    private val port: Int,
    private val scope: CoroutineScope,
    private val sockets: javax.net.SocketFactory,
    private val onStatus: (Int) -> Unit,
) {
    private var job: Job? = null
    @Volatile private var socket: java.net.Socket? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            var backoff = 1_000L
            while (isActive) {
                try {
                    sockets.createSocket().use { s ->
                        socket = s
                        s.connect(InetSocketAddress(host, port), 5_000)
                        s.soTimeout = 0
                        backoff = 1_000L
                        val buf = ByteArray(1024)
                        val pending = StringBuilder()
                        val input = s.getInputStream()
                        while (isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            pending.append(String(buf, 0, n, Charsets.UTF_8))
                            for (code in drain(pending)) launch(Dispatchers.Main) { onStatus(code) }
                        }
                    }
                } catch (e: IOException) {
                    Log.d(TAG, "Notify socket: ${e.message}")
                } finally {
                    socket = null
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
    }

    companion object {
        private const val TAG = "NotifySocket"

        /** Removes every complete message from [buf] and returns their status codes. */
        fun drain(buf: StringBuilder): List<Int> {
            val out = mutableListOf<Int>()
            while (true) {
                val end = buf.indexOf("</Function>", ignoreCase = true)
                if (end < 0) {
                    // Guard against garbage accumulating forever.
                    if (buf.length > 8192) buf.setLength(0)
                    return out
                }
                val msg = buf.substring(0, end + "</Function>".length)
                buf.delete(0, end + "</Function>".length)
                Xml.parse(msg).find("Status")?.text?.trim()?.toIntOrNull()?.let(out::add)
            }
        }

        private fun StringBuilder.indexOf(s: String, ignoreCase: Boolean): Int = toString().indexOf(s, ignoreCase = ignoreCase)
    }
}

/** Codes pushed on the notification socket. */
object Notify {
    const val RECORD_STARTED = 1
    const val RECORD_STOPPED = 2
    const val DISCONNECT = 3
    const val MIC_ON = 4
    const val MIC_OFF = 5
    const val POWER_OFF = 6
    const val REMOVED_BY_USER = 7
    const val SENSOR_CHANGED = 8
    const val CARD_INSERTED = 9
    const val CARD_REMOVED = 10
    const val REAR_INSERTED = 16
    const val REAR_REMOVED = 17
    const val STORAGE_FULL = -11
    const val CARD_SLOW = -9
    const val CARD_WRITE_ERROR = -8
}
