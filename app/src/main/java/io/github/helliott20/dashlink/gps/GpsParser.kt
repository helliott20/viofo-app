package io.github.helliott20.dashlink.gps

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.floor

data class GpsPoint(
    /** Seconds since the first point of the clip. */
    val t: Int,
    val lat: Double,
    val lon: Double,
    val speedKmh: Double,
    val bearing: Float,
    val utc: String,
)

/**
 * Extracts the GPS track VIOFO (Novatek) cameras embed in their MP4 files.
 *
 * Layout (from the official app's pure-Java parser):
 *  - `moov/gps ` atom: 4 bytes version, BE int32 count, then count × (BE int32 file offset, BE int32 size)
 *  - each offset points at a `free` atom: size(4) 'free'(4) 'GPS '(4) unknown(4), then
 *    LE int32 hour, min, sec, year, month, day; chars status('A' = fix), N/S, E/W, reserved;
 *    LE float32 lat (NMEA ddmm.mmmm), lon (dddmm.mmmm), speed (knots), bearing (deg).
 *  - Some firmware inserts 32 extra bytes (starting with zeros) before the time fields.
 */
object GpsParser {
    fun parse(channel: FileChannel): List<GpsPoint> {
        val size = channel.size()
        val index = findGpsIndex(channel, 0, size, depth = 0) ?: return emptyList()
        val out = ArrayList<GpsPoint>(index.size)
        val block = ByteBuffer.allocate(128)
        for ((i, offset) in index.withIndex()) {
            if (offset <= 0 || offset + 64 > size) continue
            block.clear()
            channel.read(block, offset)
            block.flip()
            decodeBlock(block, i)?.let(out::add)
        }
        return normaliseTimes(out)
    }

    /** Walks atoms to find `gps ` (usually inside `moov`) and returns block offsets. */
    private fun findGpsIndex(ch: FileChannel, start: Long, end: Long, depth: Int): List<Long>? {
        var pos = start
        val hdr = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        while (pos + 8 <= end) {
            hdr.clear(); hdr.limit(16)
            ch.read(hdr, pos); hdr.flip()
            if (hdr.remaining() < 8) return null
            var atomSize = hdr.int.toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { hdr.get(it) }.toString(Charsets.ISO_8859_1)
            var header = 8L
            if (atomSize == 1L && hdr.remaining() >= 8) { atomSize = hdr.long; header = 16 }
            else if (atomSize == 0L) atomSize = end - pos
            if (atomSize < header) return null
            when (type) {
                "gps " -> return readIndex(ch, pos + header, atomSize - header)
                "moov" -> if (depth < 2) findGpsIndex(ch, pos + header, pos + atomSize, depth + 1)?.let { return it }
            }
            pos += atomSize
        }
        return null
    }

    private fun readIndex(ch: FileChannel, pos: Long, len: Long): List<Long> {
        if (len < 8) return emptyList()
        val buf = ByteBuffer.allocate(len.coerceAtMost(4L * 1024 * 1024).toInt()).order(ByteOrder.BIG_ENDIAN)
        ch.read(buf, pos); buf.flip()
        buf.int // version
        val count = buf.int
        val out = ArrayList<Long>(count.coerceIn(0, 100_000))
        repeat(count) {
            if (buf.remaining() < 8) return out
            out += buf.int.toLong() and 0xFFFFFFFFL
            buf.int // size
        }
        return out
    }

    fun decodeBlock(src: ByteBuffer, seq: Int): GpsPoint? {
        val b = src.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 16 + 24 + 4 + 16) return null
        b.position(b.position() + 16) // size, 'free', 'GPS ', unknown
        var f = IntArray(6) { b.int }
        if (f[0] == 0 && f[1] == 0 && f[2] == 0 && f[3] == 0 && f[4] == 0) {
            // Newer layout: 24 zero bytes + 8 more, then the time fields.
            if (b.remaining() < 8 + 24 + 20) return null
            b.position(b.position() + 8)
            f = IntArray(6) { b.int }
        }
        val status = b.get().toInt().toChar()
        val ns = b.get().toInt().toChar()
        val ew = b.get().toInt().toChar()
        b.get()
        val latRaw = b.float
        val lonRaw = b.float
        val speedKnots = b.float
        val bearing = b.float
        if (status != 'A') return null
        var lat = nmeaToDegrees(latRaw.toDouble())
        var lon = nmeaToDegrees(lonRaw.toDouble())
        if (ns == 'S') lat = -lat
        if (ew == 'W') lon = -lon
        if (lat == 0.0 && lon == 0.0 || abs(lat) > 90 || abs(lon) > 180) return null
        val (h, mi, s, y, mo, d) = f.toList().let { Six(it[0], it[1], it[2], it[3], it[4], it[5]) }
        val year = if (y in 0..99) 2000 + y else y
        return GpsPoint(
            t = seq,
            lat = lat, lon = lon,
            speedKmh = (speedKnots * 1.852).coerceAtLeast(0.0),
            bearing = if (bearing.isFinite()) bearing else 0f,
            utc = "%04d-%02d-%02d %02d:%02d:%02d".format(year, mo, d, h, mi, s),
        )
    }

    private data class Six(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int, val f: Int)

    /** NMEA ddmm.mmmm -> decimal degrees. */
    fun nmeaToDegrees(v: Double): Double {
        val deg = floor(v / 100)
        return deg + (v - deg * 100) / 60.0
    }

    /** Blocks are one per second; use the index as the clip offset. */
    private fun normaliseTimes(points: List<GpsPoint>): List<GpsPoint> = points
}
