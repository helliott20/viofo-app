package io.github.helliott20.dashlink

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.session.SessionState
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.screens.CameraScreen
import io.github.helliott20.dashlink.ui.screens.HomeScreen
import io.github.helliott20.dashlink.ui.theme.DashLinkTheme
import io.github.helliott20.dashlink.util.Xml
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders key screens with fake camera state so the UI can be reviewed as PNGs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-night-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<App>()

    private fun fakeState(tab: String) = SessionState(
        phase = Phase.Ready, ssid = "VIOFO-A139P-9F2A1C", firmware = "VIOFO_A139P_V1.2",
        model = Settings.detect("VIOFO_A139P_V1.2"), freeBytes = 41_800_000_000, recording = true, micOn = true,
        mode = if (tab == "files") CameraMode.Playback else CameraMode.Unknown,
        values = mapOf(2016 to "1", 8222 to "2,3", 2003 to "2", 2004 to "2", 8220 to "6,6", 8200 to "2", 2007 to "1",
            2011 to "1", 8205 to "1", 8204 to "1", 8208 to "1", 8210 to "1", 8209 to "0", 8212 to "1", 8214 to "3", 8201 to "2", 2008 to "1"),
        files = NovatekClient.parseFiles(Xml.parse(buildString {
            append("<LIST>")
            for (i in 0 until 8) {
                val hh = 12 + i / 2; val lens = if (i % 2 == 0) "F" else "R"
                val folder = if (i == 2 || i == 3) "Movie\\RO" else "Movie"
                append("<File><NAME>2026_1011_${hh}0000_000${i}$lens.MP4</NAME><FPATH>A:\\DCIM\\$folder\\2026_1011_${hh}0000_000${i}$lens.MP4</FPATH><SIZE>${300_000_000 - i * 1000}</SIZE><TIME>2026/10/11 $hh:00:00</TIME><ATTR>32</ATTR></File>")
            }
            append("<File><NAME>2026_1010_230000_0100PF.MP4</NAME><FPATH>A:\\DCIM\\Movie\\Parking\\2026_1010_230000_0100PF.MP4</FPATH><SIZE>90000000</SIZE><TIME>2026/10/10 23:00:00</TIME><ATTR>32</ATTR></File>")
            append("</LIST>")
        })),
        filesLoaded = true, carNumber = "AB12 CDE", customStamp = "",
        wifiSsid = "VIOFO-A139P-9F2A1C", wifiPassword = "12345678",
    )

    private fun snap(name: String) {
        rule.mainClock.advanceTimeBy(1_500)
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun home() {
        Settings.load(app)
        app.container.session.debugSetState(SessionState())
        rule.setContent { DashLinkTheme { CompositionLocalProvider(LocalContainer provides app.container) { HomeScreen({}, {}, {}, {}) } } }
        snap("home_disconnected")
    }

    @Test fun homeConnected() {
        Settings.load(app)
        app.container.session.debugSetState(fakeState("live"))
        rule.setContent { DashLinkTheme { CompositionLocalProvider(LocalContainer provides app.container) { HomeScreen({}, {}, {}, {}) } } }
        snap("home_connected")
    }

    @Test fun cameraTabs() {
        Settings.load(app)
        for ((i, tab) in listOf("live", "files", "settings").withIndex()) {
            app.container.session.debugSetState(fakeState(tab))
            if (i == 0) rule.setContent { DashLinkTheme { CompositionLocalProvider(LocalContainer provides app.container) { CameraScreen({}, {}, {}) } } }
            if (i > 0) rule.onNode(androidx.compose.ui.test.hasText(if (tab == "files") "Files" else "Settings")).performClick()
            snap("camera_$tab")
        }
    }
}
