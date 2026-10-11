package io.github.helliott20.dashlink

import io.github.helliott20.dashlink.api.Family
import io.github.helliott20.dashlink.api.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SettingsTest {
    companion object {
        @BeforeClass @JvmStatic fun load() {
            val f = listOf("src/main/assets/option_tables.json", "app/src/main/assets/option_tables.json").map(::File).first { it.exists() }
            Settings.setTables(Settings.parseTables(f.readText()))
        }
    }

    @Test fun detectsModels() {
        assertEquals("A139 Pro", Settings.detect("VIOFO_A139P_V1.2")!!.name)
        assertEquals("A139", Settings.detect("VIOFO_A139_V1.9")!!.name)
        assertEquals(Family.Modern, Settings.detect("VIOFO_A229P_V1.3")!!.family)
        assertTrue(Settings.detect("VIOFO_A229P_V1.3")!!.a229Plus)
        assertEquals("A229 Pro (Tele)", Settings.detect("VIOFO_A229PT_V1.0")!!.name)
        assertEquals("A229", Settings.detect("VIOFO_A229_V1.1")!!.name)
        assertEquals("A129 Pro", Settings.detect("A129Pro_V2.3")!!.name)
        assertEquals(Family.Legacy, Settings.detect("A129Pro_V2.3")!!.family)
        assertEquals("A129 Plus", Settings.detect("A129Plus_V1.9")!!.name)
        assertEquals("A119 Mini 2", Settings.detect("A119 Mini2_V1.2")!!.name)
        assertNull(Settings.detect(null))
        assertNull(Settings.detect("SomethingElse"))
    }

    @Test fun parsesVersions() {
        assertEquals(1.9, Settings.versionOf("VIOFO_A139_V1.9"), 0.001)
        assertEquals(1.2, Settings.versionOf("VIOFO_A139P_V1.2B"), 0.001)
        assertEquals(2.3, Settings.versionOf("A129Pro_V2.3"), 0.001)
        assertEquals(0.0, Settings.versionOf("nothing"), 0.001)
    }

    @Test fun picksVersionedTables() {
        val a139new = Settings.detect("VIOFO_A139_V1.9")!!
        val a139old = Settings.detect("VIOFO_A139_V1.5")!!
        assertEquals(listOf("Off", "On", "Auto"), Settings.table("hdr", a139new))
        assertNull(Settings.table("hdr", a139old)) // HDR arrived in 1.7
        assertEquals(listOf("1440P 60fps", "1440P 30fps", "1080P 60fps"), Settings.table("video_resolution", a139new))
        assertEquals("1440P 60fps", Settings.table("video_resolution", a139new, "_dual")!![0].substringBefore(" +").substringBefore("+"))
        // Version filter must not pick a different model's table (a129 vs a129_pro).
        val a129 = Settings.detect("A129_V1.6")!!
        assertEquals(Settings.table("video_resolution", a129), listOf(
            *Settings.table("video_resolution", a129)!!.toTypedArray()))
        assertNotNull(Settings.table("parking_mode", a129))
    }

    @Test fun resolvesOnlySupportedSettings() {
        val m = Settings.detect("VIOFO_A139_V1.9")!!
        val views = Settings.resolve(m, mapOf(2003 to "3", 8222 to "2,1", 2007 to "1", 8205 to "1", 8220 to "6,6,5"))
        val keys = views.map { it.def.key }
        assertTrue("cyclic" in keys && "resolution" in keys && "audio" in keys && "parking" in keys && "ev" in keys)
        assertFalse("bitrate" in keys) // not in the 3014 reply
        val cyclic = views.single { it.def.key == "cyclic" }
        assertEquals("3 min", cyclic.options[cyclic.selected!!])
        val res = views.single { it.def.key == "resolution" }
        assertTrue(res.editable)
        assertEquals("2,1" , res.rawValue)
        assertEquals(null to "2,0", Settings.encode(res, 0, m))
        val ev = views.single { it.def.key == "ev" }
        assertEquals("+0.0", ev.options[ev.selected!!])
        assertEquals(null to "3,6,5", Settings.encode(ev, 3, m))
        assertEquals("Auto Event Detection", views.single { it.def.key == "parking" }.let { it.options[it.selected!!] })
    }

    @Test fun a139ProResolutionRemap() {
        val m = Settings.detect("VIOFO_A139P_V1.2")!!
        val v = Settings.resolve(m, mapOf(8222 to "2,3")).single()
        assertEquals(0, v.selected) // raw 3 -> UI 0
        assertEquals(null to "2,3", Settings.encode(v, 0, m))
        for (ui in 0..3) assertEquals(ui, Settings.a139ProRawToUi(Settings.a139ProUiToRaw(ui, 2), 2))
        for (ui in 0..2) assertEquals(ui, Settings.a139ProRawToUi(Settings.a139ProUiToRaw(ui, 3), 3))
    }

    @Test fun a229PlusOffsetsAndReadOnlyResolution() {
        val m = Settings.detect("VIOFO_A229P_V1.3")!!
        val views = Settings.resolve(m, mapOf(2003 to "1", 8222 to "1,0"))
        val cyclic = views.single { it.def.key == "cyclic" }
        assertEquals("1 min", cyclic.options[cyclic.selected!!])
        assertEquals(2 to null, Settings.encode(cyclic, 1, m))
        assertFalse(views.single { it.def.key == "resolution" }.editable)
    }

    @Test fun legacyA129DuoResolution() {
        val m = Settings.detect("A129Pro_V2.3")!!
        val single = Settings.table("video_resolution", m)!!
        val v = Settings.resolve(m, mapOf(2002 to (single.size + 1).toString())).single()
        assertTrue(v.def.title.contains("rear"))
        assertEquals(1, v.selected)
        assertEquals((single.size + 2) to null, Settings.encode(v, 2, m))
    }

    @Test fun encodesText() {
        val modern = Settings.detect("VIOFO_A139_V1.9")!!
        val legacy = Settings.detect("A129Pro_V2.3")!!
        assertEquals("AB12*CDE", Settings.encodeText("ab12 cde", modern))
        assertEquals("AB12+CDE", Settings.encodeText("ab12 cde", legacy))
        assertEquals("+", Settings.encodeText("", legacy))
        assertEquals("AB12 CDE", Settings.decodeText("AB12*CDE"))
        assertEquals(11, Settings.encodeText("123456789012345", modern).length)
    }
}
