package io.github.helliott20.dashlink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.data.Downloads
import io.github.helliott20.dashlink.data.Prefs
import io.github.helliott20.dashlink.net.CameraLink
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.session.CameraSession
import io.github.helliott20.dashlink.session.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Drives a [CameraSession] against a fake camera that implements the parts of the
 * Novatek protocol we use, and checks the command sequences the real camera would see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionFlowTest {
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private lateinit var session: CameraSession
    private lateinit var prefs: Prefs
    private val log = Collections.synchronizedList(mutableListOf<String>())

    /** Minimal stateful camera. */
    private inner class FakeCamera : Dispatcher() {
        var mode = 1
        var recording = true
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.requestUrl!!
            val cmd = url.queryParameter("cmd")?.toInt() ?: return MockResponse().setResponseCode(404)
            val par = url.queryParameter("par")
            val str = url.queryParameter("str")
            log += listOfNotNull("$cmd", par?.let { "par=$it" }, str?.let { "str=$it" }).joinToString(" ")
            fun ok(extra: String = "") = MockResponse().setBody("""<?xml version="1.0" encoding="UTF-8" ?><Function><Cmd>$cmd</Cmd><Status>0</Status>$extra</Function>""")
            return when (cmd) {
                3016, 3023, 3005, 3006, 2015 -> ok()
                3012 -> ok("<String>VIOFO_A139P_V1.2</String>")
                3024 -> ok("<Value>1</Value>")
                3017 -> ok("<Value>40000000000</Value>")
                3029 -> MockResponse().setBody("<Function><Cmd>3029</Cmd><SSID>VIOFO-A139P-TEST</SSID><PASSPHRASE>12345678</PASSPHRASE></Function>")
                8228 -> ok("<CarNO>AB12*CDE</CarNO>")
                8229 -> ok("<String></String>")
                2019 -> ok("<MovieLiveViewLink>rtsp://192.168.1.254/xxx.mov</MovieLiveViewLink>")
                3014 -> MockResponse().setBody("""<Function><Cmd>2016</Cmd><Status>${if (recording) 1 else 0}</Status><Cmd>2003</Cmd><Status>2</Status><Cmd>8222</Cmd><Status>0</Status><String>2,3</String></Function>""")
                3001 -> { mode = par!!.toInt(); if (mode == 2) recording = false; ok() }
                2001 -> {
                    if (mode == 2) MockResponse().setBody("<Function><Cmd>2001</Cmd><Status>-13</Status></Function>")
                    else { recording = par == "1"; ok() }
                }
                3015 -> if (mode != 2) MockResponse().setBody("<Function><Cmd>3015</Cmd><Status>-13</Status></Function>")
                else MockResponse().setBody("""<LIST><ALLFile><File><NAME>2026_1011_120000_0001F.MP4</NAME><FPATH>A:\DCIM\Movie\2026_1011_120000_0001F.MP4</FPATH><SIZE>100</SIZE><TIME>2026/10/11 12:00:00</TIME><ATTR>32</ATTR></File></ALLFile></LIST>""")
                2003 -> ok()
                else -> MockResponse().setBody("<html>404</html>")
            }
        }
    }

    private val camera = FakeCamera()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val f = listOf("src/main/assets/option_tables.json", "app/src/main/assets/option_tables.json").map(::File).first { it.exists() }
        Settings.setTables(Settings.parseTables(f.readText()))
        server = MockWebServer().apply { dispatcher = camera; start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val http = OkHttpClient.Builder().readTimeout(3, TimeUnit.SECONDS).build()
        prefs = Prefs(ctx)
        session = CameraSession(
            CameraLink(ctx, scope), NovatekClient(http, server.url("/")), prefs,
            Downloads(ctx, http, scope), scope,
        )
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun waitFor(timeoutMs: Long = 8_000, what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            ShadowLooper.idleMainLooper()
            if (System.currentTimeMillis() > end) throw AssertionError("Timed out waiting for $what. Commands: $log")
            Thread.sleep(50)
        }
    }

    @Test fun handshakeThenBrowseThenAutoResume() = runBlocking {
        // Handshake
        session.startHandshake("VIOFO-A139P-TEST")
        waitFor(what = "ready") { session.state.value.phase == Phase.Ready }
        val st = session.state.value
        assertEquals("A139 Pro", st.model?.name)
        assertEquals(true, st.recording)
        waitFor(what = "texts") { session.state.value.carNumber != null }
        assertEquals("AB12 CDE", session.state.value.carNumber)
        assertEquals(listOf("3016", "3023", "3012", "3024"), log.take(4))
        assertTrue("clock synced", log.any { it.startsWith("3005 str=") } && log.any { it.startsWith("3006 str=") })
        assertEquals("VIOFO-A139P-TEST", prefs.devices.first().first().ssid)

        // Safety net puts the camera into live mode on its own.
        waitFor(what = "live") { session.state.value.mode == CameraMode.Live }
        // Live mode finishes with a status refresh; let it land before the next phase.
        waitFor(what = "live settled") { synchronized(log) { log.indexOf("2019").let { i -> i >= 0 && log.drop(i).contains("3014") } } }
        assertTrue(log.contains("2015 par=1") && log.contains("2019"))

        // Browsing: stop recording, then playback mode, then list.
        log.clear()
        session.acquireBrowsing()
        session.loadFiles()
        waitFor(what = "files") { session.state.value.filesLoaded }
        assertEquals(1, session.state.value.files.size)
        assertEquals(listOf("2001 par=0", "3001 par=2", "3015"), log.toList())
        assertEquals(false, camera.recording)

        // Leaving the browser: back to movie mode and recording resumes automatically.
        log.clear()
        session.releaseBrowsing()
        waitFor(what = "recording resumed") { camera.recording && camera.mode == 1 }
        assertEquals("3001 par=1", log.first())
        assertTrue(log.contains("2001 par=1"))
        waitFor(what = "state recording") { session.state.value.recording == true && session.state.value.mode == CameraMode.Live }
    }

    @Test fun settingChangePausesAndResumesRecording() {
        session.startHandshake("VIOFO-A139P-TEST")
        waitFor(what = "live") { session.state.value.mode == CameraMode.Live }
        waitFor(what = "live settled") { synchronized(log) { log.indexOf("2019").let { i -> i >= 0 && log.drop(i).contains("3014") } } }
        log.clear()
        val loop = session.state.value.settings.single { it.def.key == "cyclic" }
        session.applySetting(loop, 4)
        waitFor(what = "setting applied") { log.contains("2001 par=1") }
        val i0 = log.indexOf("2001 par=0"); val i1 = log.indexOf("2003 par=4"); val i2 = log.indexOf("2001 par=1")
        assertTrue("order was $log", i0 in 0 until i1 && i1 < i2)
        assertTrue(camera.recording)
    }
}
