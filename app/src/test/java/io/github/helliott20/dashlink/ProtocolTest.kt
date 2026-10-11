package io.github.helliott20.dashlink

import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.api.CameraException
import io.github.helliott20.dashlink.api.Category
import io.github.helliott20.dashlink.api.Lens
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.net.NotifySocket
import io.github.helliott20.dashlink.util.Xml
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class ProtocolTest {
    private val fileList = """<?xml version="1.0" encoding="UTF-8" ?>
<LIST><ALLFile>
<File><NAME>2026_1011_120000_0001F.MP4</NAME><FPATH>A:\DCIM\Movie\2026_1011_120000_0001F.MP4</FPATH><SIZE>314572800</SIZE><TIMECODE>1</TIMECODE><TIME>2026/10/11 12:00:00</TIME><ATTR>32</ATTR></File>
<File><NAME>2026_1011_120000_0001R.MP4</NAME><FPATH>A:\DCIM\Movie\2026_1011_120000_0001R.MP4</FPATH><SIZE>104857600</SIZE><TIMECODE>1</TIMECODE><TIME>2026/10/11 12:00:00</TIME><ATTR>32</ATTR></File>
<File><NAME>2026_1011_130500_0002F.MP4</NAME><FPATH>A:\DCIM\Movie\RO\2026_1011_130500_0002F.MP4</FPATH><SIZE>1000</SIZE><TIMECODE>2</TIMECODE><TIME>2000/01/01 00:00:10</TIME><ATTR>33</ATTR></File>
<File><NAME>2026_1010_220000_0003PF.MP4</NAME><FPATH>A:\DCIM\Movie\Parking\2026_1010_220000_0003PF.MP4</FPATH><SIZE>5</SIZE><TIMECODE>0</TIMECODE><TIME>2026/10/10 22:00:00</TIME><ATTR>32</ATTR></File>
<File><NAME>2026_1011_140000_0004I.JPG</NAME><FPATH>A:\DCIM\Photo\2026_1011_140000_0004I.JPG</FPATH><SIZE>5</SIZE><TIMECODE>0</TIMECODE><TIME>2026/10/11 14:00:00</TIME><ATTR>32</ATTR></File>
</ALLFile></LIST>"""

    @Test fun parsesFileList() {
        val files = NovatekClient.parseFiles(Xml.parse(fileList))
        assertEquals(5, files.size)
        val photo = files.first()
        assertEquals(Category.Photo, photo.category)
        assertEquals(Lens.Interior, photo.lens)
        val event = files.single { it.name.contains("0002F") }
        assertEquals(Category.Event, event.category)
        assertTrue(event.locked)
        // Year-2000 camera clock falls back to the filename timestamp.
        assertEquals(LocalDateTime.of(2026, 10, 11, 13, 5, 0), event.time)
        val parking = files.single { it.name.contains("PF") }
        assertEquals(Category.Parking, parking.category)
        assertEquals(Lens.Front, parking.lens)
        val front = files.single { it.name.endsWith("0001F.MP4") }
        val rear = files.single { it.name.endsWith("0001R.MP4") }
        assertEquals(front.groupKey, rear.groupKey)
        assertEquals(Lens.Rear, rear.lens)
        assertEquals("DCIM/Movie/2026_1011_120000_0001F.MP4", front.webPath)
        assertEquals(314572800L, front.size)
    }

    @Test fun parsesStateWithStringOverrides() {
        val xml = """<?xml version="1.0" encoding="UTF-8" ?>
<Function><Cmd>2016</Cmd><Status>1</Status><Cmd>8222</Cmd><Status>0</Status><String>2,1</String>
<Cmd>2003</Cmd><Status>3</Status><Cmd>8220</Cmd><Status>0</Status><String>6,6,5</String></Function>"""
        val m = NovatekClient.parseState(Xml.parse(xml).find("Function")!!)
        assertEquals("1", m[2016])
        assertEquals("2,1", m[8222])
        assertEquals("3", m[2003])
        assertEquals("6,6,5", m[8220])
    }

    @Test fun xmlToleratesJunk() {
        val r = Xml.parse("garbage <Function><Cmd>3012</Cmd><Status>0</Status><String>VIOFO_A139_V1.9 &amp; co</String></Function> trailing")
        assertEquals("VIOFO_A139_V1.9 & co", r.find("String")!!.text)
        val broken = Xml.parse("<Function><Cmd>1</Cmd><Status>0")
        assertEquals("1", broken.find("Cmd")!!.text)
    }

    @Test fun notifySocketSplitsAndJoinsMessages() {
        val b = StringBuilder()
        b.append("<?xml version=\"1.0\"?><Function><Cmd>3020</Cmd><Status>1</Status></Fun")
        assertEquals(emptyList<Int>(), NotifySocket.drain(b))
        b.append("ction><?xml version=\"1.0\"?><Function><Status>-11</Status></Function><Function><Status>4")
        assertEquals(listOf(1, -11), NotifySocket.drain(b))
        b.append("</Status></Function>")
        assertEquals(listOf(4), NotifySocket.drain(b))
        assertEquals(0, b.length)
    }

    @Test fun clientBuildsUrlsAndHandlesErrors() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<?xml version=\"1.0\"?><Function><Cmd>3012</Cmd><Status>0</Status><String>VIOFO_A139P_V1.2</String></Function>"))
        server.enqueue(MockResponse().setBody("<html><body>404 The requested URL was not found</body></html>"))
        server.enqueue(MockResponse().setBody("<Function><Cmd>4003</Cmd><Status>-4</Status></Function>"))
        // Transient failure then success: command should retry.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setBody("<Function><Cmd>3017</Cmd><Status>0</Status><Value>123456789</Value></Function>"))
        server.start()
        try {
            val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
            val client = NovatekClient(http, server.url("/"))
            assertEquals("VIOFO_A139P_V1.2", client.firmware())
            assertEquals("/?custom=1&cmd=3012", server.takeRequest().path)

            try { client.exec(9999); fail() } catch (e: CameraException) { assertEquals(-256, e.status) }
            server.takeRequest()

            val f = CamFile("x.MP4", "A:\\DCIM\\Movie\\RO\\x.MP4", 1, null, Lens.Front, Category.Event, true)
            try { client.delete(f); fail() } catch (e: CameraException) { assertEquals(-4, e.status); assertTrue(e.message!!.contains("locked")) }
            val del = server.takeRequest().requestUrl!!
            assertEquals("4003", del.queryParameter("cmd"))
            assertEquals("A:\\DCIM\\Movie\\RO\\x.MP4", del.queryParameter("str"))

            assertEquals(123456789L, client.freeSpace())
            assertTrue(client.fileUrl(f).endsWith("/DCIM/Movie/RO/x.MP4"))
            assertTrue(client.thumbnailUrl(f).endsWith("/DCIM/Movie/RO/x.MP4?custom=1&cmd=4001"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun liveUrlFallsBack() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("<Function><Cmd>2019</Cmd><Status>0</Status><MovieLiveViewLink>rtsp://192.168.1.254/xxx.mov</MovieLiveViewLink></Function>"))
        server.enqueue(MockResponse().setBody("<Function><Cmd>2019</Cmd><Status>0</Status></Function>"))
        server.start()
        try {
            val client = NovatekClient(OkHttpClient(), server.url("/"))
            assertEquals("rtsp://192.168.1.254/xxx.mov", client.liveUrl())
            assertEquals(NovatekClient.DEFAULT_LIVE_URL, client.liveUrl())
        } finally { server.shutdown() }
    }

    @Test fun heartbeatFailsFastWhenCameraGone() = runBlocking {
        val server = MockWebServer(); server.start(); val url = server.url("/"); server.shutdown()
        val client = NovatekClient(OkHttpClient.Builder().connectTimeout(500, TimeUnit.MILLISECONDS).build(), url)
        assertFalse(client.heartbeat())
        assertNull(runCatching { client.freeSpace() }.getOrNull())
    }
}
