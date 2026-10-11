package io.github.helliott20.dashlink

import io.github.helliott20.dashlink.gps.GpsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GpsParserTest {
    private fun be(v: Int) = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()

    private fun gpsBlock(sec: Int, lat: Float, lon: Float, knots: Float, ns: Char = 'N', ew: Char = 'W', status: Char = 'A', padded: Boolean = false): ByteArray {
        val b = ByteBuffer.allocate(if (padded) 120 else 88).order(ByteOrder.LITTLE_ENDIAN)
        b.order(ByteOrder.BIG_ENDIAN).putInt(b.capacity()).put("free".toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        b.put("GPS ".toByteArray()).putInt(0)
        if (padded) b.put(ByteArray(32))
        b.putInt(12).putInt(30).putInt(sec).putInt(26).putInt(10).putInt(11)
        b.put(status.code.toByte()).put(ns.code.toByte()).put(ew.code.toByte()).put(0)
        b.putFloat(lat).putFloat(lon).putFloat(knots).putFloat(90f)
        return b.array()
    }

    @Test fun parsesNovatekTrack() {
        val blocks = listOf(
            gpsBlock(0, 5130.0f, 7.5f, 10f),             // 51.5 N, 0.125 W
            gpsBlock(1, 5130.06f, 7.56f, 20f, padded = true),
            gpsBlock(2, 0f, 0f, 0f, status = 'V'),         // no fix, skipped
        )
        val out = ByteArrayOutputStream()
        out.write(be(16)); out.write("ftyp".toByteArray()); out.write("isom0000".toByteArray())
        val offsets = mutableListOf<Int>()
        blocks.forEach { offsets += out.size(); out.write(it) }
        val gpsPayload = ByteArrayOutputStream().apply {
            write(be(0)); write(be(offsets.size)); offsets.zip(blocks).forEach { (o, b) -> write(be(o)); write(be(b.size)) }
        }.toByteArray()
        val gpsAtom = be(8 + gpsPayload.size) + "gps ".toByteArray() + gpsPayload
        val mvhd = be(16) + "mvhd".toByteArray() + ByteArray(8)
        val moov = be(8 + mvhd.size + gpsAtom.size) + "moov".toByteArray() + mvhd + gpsAtom
        out.write(moov)

        val f = File.createTempFile("gps", ".mp4"); f.writeBytes(out.toByteArray())
        try {
            val pts = RandomAccessFile(f, "r").channel.use { GpsParser.parse(it) }
            assertEquals(2, pts.size)
            assertEquals(51.5, pts[0].lat, 1e-4)
            assertEquals(-0.125, pts[0].lon, 1e-4)
            assertEquals(18.52, pts[0].speedKmh, 0.01)
            assertEquals("2026-10-11 12:30:00", pts[0].utc)
            assertTrue(pts[1].lat > 51.5)
            assertEquals("2026-10-11 12:30:01", pts[1].utc)
        } finally { f.delete() }
    }

    @Test fun nonGpsFileGivesEmptyTrack() {
        val f = File.createTempFile("nogps", ".mp4"); f.writeBytes(be(16) + "ftyp".toByteArray() + ByteArray(8))
        try { assertEquals(0, RandomAccessFile(f, "r").channel.use { GpsParser.parse(it) }.size) } finally { f.delete() }
    }
}
