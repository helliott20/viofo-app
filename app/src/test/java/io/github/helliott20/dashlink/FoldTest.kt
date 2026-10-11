package io.github.helliott20.dashlink

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature as TestFoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.session.CameraMode
import io.github.helliott20.dashlink.session.Phase
import io.github.helliott20.dashlink.session.SessionState
import io.github.helliott20.dashlink.ui.LayoutInfo
import io.github.helliott20.dashlink.ui.LocalContainer
import io.github.helliott20.dashlink.ui.LocalLayoutInfo
import io.github.helliott20.dashlink.ui.WidthClass
import io.github.helliott20.dashlink.ui.layoutInfo
import io.github.helliott20.dashlink.ui.screens.CameraScreen
import io.github.helliott20.dashlink.ui.screens.LocalLivePreview
import io.github.helliott20.dashlink.ui.screens.RemotePlayerScreen
import io.github.helliott20.dashlink.ui.theme.DashLinkTheme
import io.github.helliott20.dashlink.util.Xml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Fold behaviour: posture detection through the real WindowManager path, and continuity across fold/unfold. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w791dp-h820dp-420dpi")
class FoldTest {
    private val publisher = WindowLayoutInfoPublisherRule()
    private val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(publisher).around(compose)

    private val app get() = ApplicationProvider.getApplicationContext<App>()

    // ---- Posture detection via androidx.window (what the Pixel Fold reports) ----

    private fun postureFor(state: FoldingFeature.State, orientation: FoldingFeature.Orientation): LayoutInfo {
        var seen: LayoutInfo? = null
        compose.setContent { seen = layoutInfo() }
        compose.waitForIdle()
        publisher.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(
            TestFoldingFeature(activity = compose.activity, state = state, orientation = orientation),
        )))
        compose.waitForIdle()
        return seen!!
    }

    @Test fun halfOpenHorizontalHingeIsTabletop() {
        val l = postureFor(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.HORIZONTAL)
        assertTrue(l.tabletop)
        assertFalse(l.book)
        assertNotNull(l.hingeTopPx)
        assertEquals(WidthClass.Medium, l.width)
    }

    @Test fun halfOpenVerticalHingeIsBook() {
        val l = postureFor(FoldingFeature.State.HALF_OPENED, FoldingFeature.Orientation.VERTICAL)
        assertTrue(l.book)
        assertFalse(l.tabletop)
        assertNotNull(l.hingeLeftPx)
    }

    @Test fun flatFoldIsOneScreen() {
        val l = postureFor(FoldingFeature.State.FLAT, FoldingFeature.Orientation.VERTICAL)
        assertFalse(l.book)
        assertFalse(l.tabletop)
        assertTrue(l.wide)
    }

    // ---- Continuity ----

    private fun connected(mode: CameraMode) = SessionState(
        phase = Phase.Ready, ssid = "VIOFO-A139P-TEST", firmware = "VIOFO_A139P_V1.2", model = Settings.detect("VIOFO_A139P_V1.2"),
        recording = true, mode = mode, filesLoaded = true,
        files = NovatekClient.parseFiles(Xml.parse(
            """<LIST><File><NAME>2026_1011_120000_0001F.MP4</NAME><FPATH>A:\DCIM\Movie\2026_1011_120000_0001F.MP4</FPATH><SIZE>1</SIZE><TIME>2026/10/11 12:00:00</TIME><ATTR>32</ATTR></File>
               <File><NAME>2026_1011_120000_0001R.MP4</NAME><FPATH>A:\DCIM\Movie\2026_1011_120000_0001R.MP4</FPATH><SIZE>1</SIZE><TIME>2026/10/11 12:00:00</TIME><ATTR>32</ATTR></File></LIST>""",
        )),
    )

    private val phone = LayoutInfo(WidthClass.Compact)
    private val unfolded = LayoutInfo(WidthClass.Medium)
    private val tabletop = LayoutInfo(WidthClass.Medium, tabletop = true, hingeTopPx = 1000, hingeBottomPx = 1000)
    private val book = LayoutInfo(WidthClass.Medium, book = true, hingeLeftPx = 1038, hingeRightPx = 1038)

    private fun host(layout: () -> LayoutInfo, preview: (@Composable (Modifier) -> Unit)? = null, content: @Composable () -> Unit) {
        Settings.load(app)
        compose.setContent {
            DashLinkTheme {
                CompositionLocalProvider(LocalContainer provides app.container, LocalLayoutInfo provides layout(), LocalLivePreview provides preview) { content() }
            }
        }
    }

    @Test fun liveVideoSurvivesFoldingAndPostureChanges() {
        app.container.session.debugSetState(connected(CameraMode.Live))
        var created = 0
        val surface: @Composable (Modifier) -> Unit = { m -> remember { created++ }; Box(m) }
        var layout by mutableStateOf(phone)
        host({ layout }, surface) { CameraScreen({}, {}, {}, initialTab = 0) }
        for (next in listOf(unfolded, tabletop, book, phone, unfolded)) {
            layout = next
            compose.waitForIdle()
        }
        assertEquals("live video was rebuilt during fold/unfold", 1, created)
    }

    @Test fun foldingWithAClipOpenContinuesFullScreen() {
        app.container.session.debugSetState(connected(CameraMode.Playback))
        val clip = "A:\\DCIM\\Movie\\2026_1011_120000_0001F.MP4"
        app.container.ui.select(clip)
        var opened: CamFile? = null
        var layout by mutableStateOf(unfolded)
        host({ layout }) { CameraScreen({}, { opened = it }, {}, initialTab = 1) }
        compose.waitForIdle()
        assertEquals(null, opened)
        layout = phone
        compose.waitForIdle()
        assertEquals(clip, opened?.fpath)
    }

    @Test fun unfoldingWhileWatchingMovesTheClipToThePane() {
        app.container.session.debugSetState(connected(CameraMode.Playback))
        val clip = "A:\\DCIM\\Movie\\2026_1011_120000_0001R.MP4"
        app.container.ui.select(clip)
        var toPane = 0
        var back = 0
        var layout by mutableStateOf(phone)
        host({ layout }) { RemotePlayerScreen(clip, back = { back++ }, toPane = { toPane++ }) }
        compose.waitForIdle()
        assertEquals(0, toPane)
        layout = unfolded
        compose.waitForIdle()
        assertEquals(1, toPane)
        assertEquals(0, back)
        assertEquals("selection kept for the pane", clip, app.container.ui.clip.value)
    }
}
