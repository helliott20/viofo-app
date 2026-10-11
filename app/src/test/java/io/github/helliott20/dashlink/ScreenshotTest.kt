package io.github.helliott20.dashlink

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.data.SavedDevice
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.session.SessionState
import io.github.helliott20.dashlink.ui.LayoutInfo
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.LocalLayoutInfo
import io.github.helliott20.dashlink.ui.WidthClass
import io.github.helliott20.dashlink.ui.screens.CameraScreen
import io.github.helliott20.dashlink.ui.screens.HomeScreen
import io.github.helliott20.dashlink.ui.screens.LocalLivePreview
import io.github.helliott20.dashlink.ui.theme.DashLinkTheme
import io.github.helliott20.dashlink.util.Xml
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders key screens with a simulated camera on the device classes we care about, so the
 * UI can be reviewed as PNGs in app/build/screenshots. Sizes are the Pixel 10 Pro Fold's
 * (density 2.625): outer 411×900 dp, inner 791×820 dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<App>()

    private fun fakeState(mode: CameraMode = CameraMode.Unknown) = SessionState(
        phase = Phase.Ready, ssid = "VIOFO-A139P-9F2A1C", firmware = "VIOFO_A139P_V1.2",
        model = Settings.detect("VIOFO_A139P_V1.2"), freeBytes = 41_800_000_000, recording = true, micOn = true,
        mode = mode,
        values = mapOf(2016 to "1", 8222 to "2,3", 2003 to "2", 2004 to "2", 8220 to "6,6", 8200 to "2", 2007 to "1",
            2011 to "1", 8205 to "1", 8204 to "1", 8208 to "1", 8210 to "1", 8209 to "0", 8212 to "1", 8214 to "3", 8201 to "2", 2008 to "1"),
        files = NovatekClient.parseFiles(Xml.parse(buildString {
            append("<LIST>")
            // A morning commute, an afternoon drive with an incident, and the car parked overnight.
            val times = listOf("08:02:10", "08:05:10", "08:08:10", "08:11:10", "13:40:00", "13:43:00", "13:46:00", "17:55:30", "17:58:30")
            times.forEachIndexed { i, t ->
                for (lens in listOf("F", "R")) {
                    val folder = if (i == 5) "Movie\\RO" else "Movie"
                    val n = "2026_1011_${t.replace(":", "")}_${"%04d".format(i)}$lens.MP4"
                    append("<File><NAME>$n</NAME><FPATH>A:\\DCIM\\$folder\\$n</FPATH><SIZE>${if (lens == "F") 314_572_800 else 104_857_600}</SIZE><TIME>2026/10/11 $t</TIME><ATTR>32</ATTR></File>")
                }
            }
            listOf("22:10:00", "23:40:00").forEachIndexed { i, t ->
                val n = "2026_1010_${t.replace(":", "")}_${"%04d".format(100 + i)}PF.MP4"
                append("<File><NAME>$n</NAME><FPATH>A:\\DCIM\\Movie\\Parking\\$n</FPATH><SIZE>90000000</SIZE><TIME>2026/10/10 $t</TIME><ATTR>32</ATTR></File>")
            }
            append("</LIST>")
        })),
        filesLoaded = true, carNumber = "AB12 CDE", customStamp = "",
        wifiSsid = "VIOFO-A139P-9F2A1C", wifiPassword = "12345678",
    )

    /** A stylised road at dusk, standing in for the camera feed. */
    private val road: @Composable (Modifier) -> Unit = { m ->
        Canvas(m) {
            val w = size.width; val h = size.height
            drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2A44), Color(0xFF5B4A5E), Color(0xFFC77B4A)), endY = h * 0.55f))
            drawRect(Color(0xFF22262C), topLeft = Offset(0f, h * 0.55f), size = androidx.compose.ui.geometry.Size(w, h * 0.45f))
            val road = Path().apply { moveTo(w * 0.46f, h * 0.55f); lineTo(w * 0.54f, h * 0.55f); lineTo(w * 0.95f, h); lineTo(w * 0.05f, h); close() }
            drawPath(road, Color(0xFF3A3F47))
            for (i in 0 until 6) {
                val t0 = 0.58f + i * 0.075f; val t1 = t0 + 0.04f
                drawLine(Color(0xFFE8E2C8), Offset(w * 0.5f, h * t0), Offset(w * 0.5f, h * t1), strokeWidth = 2f + i * 1.5f)
            }
            drawCircle(Color(0xFFFFE3A0), radius = h * 0.04f, center = Offset(w * 0.7f, h * 0.42f))
        }
    }

    private fun shoot(name: String, layout: LayoutInfo, state: SessionState = fakeState(), dark: Boolean = true, devices: Boolean = false, fontScale: Float = 1f, content: @Composable () -> Unit) {
        Settings.load(app)
        app.container.autoConnectDone = true
        app.container.session.debugSetState(state)
        if (devices) runBlocking {
            app.container.prefs.saveDevice(SavedDevice("VIOFO-A139P-9F2A1C", "12345678", "A139 Pro", System.currentTimeMillis() - 3_600_000))
            app.container.prefs.saveDevice(SavedDevice("VIOFO-A229-PLUS-77", "12345678", "A229 Plus", System.currentTimeMillis() - 86_400_000 * 9L))
        }
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            DashLinkTheme(dark = dark) {
                CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale),
                    LocalContainer provides app.container,
                    LocalLayoutInfo provides layout,
                    LocalLivePreview provides road,
                ) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private val phone = LayoutInfo(WidthClass.Compact)
    private val unfolded = LayoutInfo(WidthClass.Medium)

    // ---- Folded (outer screen) ----
    @Config(qualifiers = "w411dp-h900dp-night-420dpi")
    @Test fun folded_home() = shoot("folded_1_home", phone, state = SessionState(), devices = true) { HomeScreen({}, {}, {}, {}) }

    @Config(qualifiers = "w411dp-h900dp-night-420dpi")
    @Test fun folded_live() = shoot("folded_2_live", phone) { CameraScreen({}, {}, {}, initialTab = 0) }

    @Config(qualifiers = "w411dp-h900dp-night-420dpi")
    @Test fun folded_files() = shoot("folded_3_files", phone, fakeState(CameraMode.Playback)) { CameraScreen({}, {}, {}, initialTab = 1) }

    @Config(qualifiers = "w411dp-h900dp-night-420dpi")
    @Test fun folded_settings() = shoot("folded_4_settings", phone) { CameraScreen({}, {}, {}, initialTab = 2) }

    // ---- Unfolded (inner screen) ----
    @Config(qualifiers = "w791dp-h820dp-night-420dpi")
    @Test fun unfolded_home() = shoot("unfolded_1_home", unfolded, devices = true) { HomeScreen({}, {}, {}, {}) }

    @Config(qualifiers = "w791dp-h820dp-night-420dpi")
    @Test fun unfolded_live() = shoot("unfolded_2_live", unfolded) { CameraScreen({}, {}, {}, initialTab = 0) }

    @Config(qualifiers = "w791dp-h820dp-night-420dpi")
    @Test fun unfolded_files() = shoot("unfolded_3_files", unfolded, fakeState(CameraMode.Playback)) {
        CameraScreen({}, {}, {}, initialTab = 1, initialFile = "A:\\DCIM\\Movie\\RO\\2026_1011_134300_0005F.MP4")
    }

    @Config(qualifiers = "w791dp-h820dp-night-420dpi")
    @Test fun unfolded_settings() = shoot("unfolded_4_settings", unfolded) { CameraScreen({}, {}, {}, initialTab = 2) }

    @Config(qualifiers = "w820dp-h791dp-land-night-420dpi")
    @Test fun unfolded_landscape_files() = shoot("unfolded_5_landscape_files", unfolded, fakeState(CameraMode.Playback)) {
        CameraScreen({}, {}, {}, initialTab = 1, initialFile = "A:\\DCIM\\Movie\\2026_1011_080210_0000F.MP4")
    }

    // Half-folded on the dashboard: hinge across the middle of the inner screen.
    @Config(qualifiers = "w820dp-h791dp-land-night-420dpi")
    @Test fun tabletop_live() {
        val hingePx = (791 * 2.625 / 2).toInt()
        shoot("unfolded_6_tabletop_live", LayoutInfo(WidthClass.Medium, tabletop = true, hingeTopPx = hingePx, hingeBottomPx = hingePx)) {
            CameraScreen({}, {}, {}, initialTab = 0)
        }
    }

    // Tabletop with a clip open: it plays on the upright half, list and details on the flat half.
    @Config(qualifiers = "w820dp-h791dp-land-night-420dpi")
    @Test fun tabletop_files() {
        val hingePx = (791 * 2.625 / 2).toInt()
        shoot("unfolded_7_tabletop_files", LayoutInfo(WidthClass.Medium, tabletop = true, hingeTopPx = hingePx, hingeBottomPx = hingePx), fakeState(CameraMode.Playback)) {
            CameraScreen({}, {}, {}, initialTab = 1, initialFile = "A:\\DCIM\\Movie\\RO\\2026_1011_134300_0005F.MP4")
        }
    }

    // Half-open like a book: the list/detail split lands on the hinge.
    @Config(qualifiers = "w791dp-h820dp-night-420dpi")
    @Test fun book_files() {
        val hingePx = (791 * 2.625 / 2).toInt()
        shoot("unfolded_8_book_files", LayoutInfo(WidthClass.Medium, book = true, hingeLeftPx = hingePx, hingeRightPx = hingePx), fakeState(CameraMode.Playback)) {
            CameraScreen({}, {}, {}, initialTab = 1, initialFile = "A:\\DCIM\\Movie\\2026_1011_080210_0000F.MP4")
        }
    }

    // Split-screen next to Maps on the unfolded landscape screen: a short, wide window.
    @Config(qualifiers = "w820dp-h395dp-land-night-420dpi")
    @Test fun splitScreen_live() = shoot("unfolded_9_splitscreen_live", LayoutInfo(WidthClass.Medium, compactHeight = true)) {
        CameraScreen({}, {}, {}, initialTab = 0)
    }

    // ---- Quality checks ----
    @Config(qualifiers = "w411dp-h900dp-notnight-420dpi")
    @Test fun light_home() = shoot("check_light_home", phone, dark = false, devices = true) { HomeScreen({}, {}, {}, {}) }

    @Config(qualifiers = "w411dp-h900dp-night-420dpi")
    @Test fun largeFont_settings() = shoot("check_font130_settings", phone, fontScale = 1.3f) { CameraScreen({}, {}, {}, initialTab = 2) }

    @Config(qualifiers = "w411dp-h900dp-night-420dpi")
    @Test fun largeFont_live() = shoot("check_font130_live", phone, fontScale = 1.3f) { CameraScreen({}, {}, {}, initialTab = 0) }
}
