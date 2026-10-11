package io.github.helliott20.dashlink.api

import android.content.Context
import org.json.JSONObject

/**
 * Model knowledge, ported from the official VIOFO app (v3.2.23).
 *
 * The cameras don't describe their own menus. Instead, `cmd=3014` returns raw values
 * for every setting the camera supports, and the app has to know which command number
 * each model uses and which label table goes with it. The label tables were extracted
 * from the official app's resources into `assets/option_tables.json`.
 *
 * We only show settings whose command appears in the 3014 reply, so unsupported options
 * never show up and new firmware features that reuse known commands appear automatically.
 */
enum class Family { Legacy, Modern }

data class ModelInfo(
    /** Display name, e.g. "A139 Pro". */
    val name: String,
    val family: Family,
    /** Label-table suffixes to try, most specific first, e.g. ["a139pro", "a139"]. */
    val tables: List<String>,
    val version: Double,
    val firmware: String,
    /** Quirk flags. */
    val a229Plus: Boolean = false,
    val a139Pro: Boolean = false,
    val a129Duo: Boolean = false,
) {
    val pipCmd get() = if (family == Family.Modern) 8202 else 3028
    /** On these models 8202 with no parameter cycles the source; otherwise par selects one. */
    val pipCycles get() = family == Family.Modern && (name.startsWith("A139") || name == "A229" || name.startsWith("A329") || name.startsWith("A339"))
    val restartCmd get() = if (family == Family.Modern) 8230 else 9423
    val carNumberCmd get() = if (family == Family.Modern) 8218 else 9422
    val customStampCmd get() = if (family == Family.Modern) 8219 else 9417
    val getCarNumberCmd get() = if (family == Family.Modern) 8228 else 9426
    val getCustomStampCmd get() = if (family == Family.Modern) 8229 else 9427
    val blankReplace get() = if (family == Family.Modern) "*" else "+"
    val snapshotCmd get() = 2017
}

enum class Group(val title: String) {
    Video("Video"), Parking("Parking"), Stamps("Stamps & GPS"), Camera("Camera"), System("System")
}

enum class Kind { Choice, Switch }

data class SettingDef(
    val key: String,
    val title: String,
    val group: Group,
    val legacyCmd: Int?,
    val modernCmd: Int?,
    val table: String? = null,
    val kind: Kind = Kind.Choice,
    val hint: String? = null,
)

/** A setting resolved for the connected camera, ready to show. */
data class SettingView(
    val def: SettingDef,
    val cmd: Int,
    val options: List<String>,
    /** Index into [options] of the current value, or null if unknown. */
    val selected: Int?,
    val switchOn: Boolean,
    val editable: Boolean,
    val rawValue: String,
)

object Settings {
    val defs = listOf(
        SettingDef("resolution", "Resolution", Group.Video, 2002, 8222, "video_resolution"),
        SettingDef("cyclic", "Loop recording", Group.Video, 2003, 2003, "video_cyclic"),
        SettingDef("hdr", "HDR / WDR", Group.Video, 2004, 2004, "hdr", hint = "Improves high-contrast scenes"),
        SettingDef("ev", "Exposure", Group.Video, 2005, 8220, "exposure_value"),
        SettingDef("bitrate", "Bitrate", Group.Video, 9212, 8200, "bitrate"),
        SettingDef("frequency", "Light frequency", Group.Video, 9406, 8215, "frequency", hint = "Match mains frequency to avoid flicker"),
        SettingDef("audio", "Record audio", Group.Video, 2007, 2007, kind = Kind.Switch),
        SettingDef("gsensor", "G-sensor", Group.Video, 2011, 2011, "g_sensor", hint = "Locks clips on impact"),
        SettingDef("timelapse", "Time-lapse", Group.Video, 9201, 8206, "time_lapse_recording"),
        SettingDef("motion", "Motion detection", Group.Video, 2006, null, kind = Kind.Switch),

        SettingDef("parking", "Parking mode", Group.Parking, 9421, 8205, "parking_mode"),
        SettingDef("parking_gsensor", "Parking G-sensor", Group.Parking, 9220, 8204, "parking_g_sensor"),
        SettingDef("parking_motion", "Parking motion detection", Group.Parking, 9221, 8207, "parking_motion_detection"),
        SettingDef("parking_timer", "Parking recording timer", Group.Parking, 9428, 8232, "parking_recording_time"),
        SettingDef("enter_parking", "Enter parking mode after", Group.Parking, 9435, 8233, "enter_parking_mode_timer"),

        SettingDef("date_stamp", "Date stamp", Group.Stamps, 2008, 2008, kind = Kind.Switch),
        SettingDef("gps", "GPS", Group.Stamps, 9410, 8208, kind = Kind.Switch),
        SettingDef("gps_stamp", "GPS info stamp", Group.Stamps, 9214, 8210, "gps_info_stamp"),
        SettingDef("speed_unit", "Speed unit", Group.Stamps, 9412, 8209, "speed_unit"),
        SettingDef("model_stamp", "Camera model stamp", Group.Stamps, 9216, 8213, kind = Kind.Switch),
        SettingDef("date_format", "Date format", Group.Stamps, null, 8211, "date_format"),
        SettingDef("time_zone", "Time zone", Group.Stamps, 9411, 8212, "time_zone"),

        SettingDef("pip", "Live view / display", Group.Camera, 3028, null, "display_mode"),
        SettingDef("mic", "Microphone", Group.Camera, null, 8221, "microphone"),
        SettingDef("ir_led", "IR LED", Group.Camera, null, 8203, "ir_color"),
        SettingDef("rotate", "Image rotation", Group.Camera, 9093, 8224, "image_rotate"),
        SettingDef("rear_mirror", "Rear camera mirror", Group.Camera, 9219, 9220, kind = Kind.Switch),

        SettingDef("beep", "Beep / sounds", Group.System, 9094, 8214, "beep"),
        SettingDef("voice", "Voice notification volume", Group.System, null, 9221, "voice_notification_volume"),
        SettingDef("boot_delay", "Boot delay", Group.System, 9424, 8223, "boot_delay"),
        SettingDef("screen_saver", "Screen saver", Group.System, 9405, null, "screen_saver"),
        SettingDef("wifi_channel", "Wi-Fi band", Group.System, null, 8201, "wifi_channel", hint = "5 GHz is much faster for downloads"),
    )

    // ---- Model detection (mirrors SettingOptions.init in the official app) ----

    fun detect(firmware: String?): ModelInfo? {
        val fw = firmware?.trim().orEmpty()
        if (fw.isEmpty()) return null
        val v = versionOf(fw)
        fun legacy(name: String, vararg t: String, duo: Boolean = false) = ModelInfo(name, Family.Legacy, t.toList(), v, fw, a129Duo = duo)
        fun modern(name: String, vararg t: String, plus: Boolean = false, pro: Boolean = false) =
            ModelInfo(name, Family.Modern, t.toList(), v, fw, a229Plus = plus, a139Pro = pro)
        return when {
            fw.startsWith("A129Plus") -> legacy("A129 Plus", "a129_plus", "a129")
            "A119" in fw && "Mini2" in fw -> legacy("A119 Mini 2", "a119_Mini2", "a119_mini_2", "a119_Mini", "a119_mini")
            "A119" in fw && "M Pro" in fw -> legacy("A119M Pro", "a119m_pro", "a119_m_pro", "a119_Mini", "a119_mini")
            "A119" in fw && "Mini" in fw -> legacy("A119 Mini", "a119_Mini", "a119_mini")
            fw.startsWith("A129Pro_IR") -> legacy("A129 Pro IR", "a129_pro_ir", "a129_pro", "a129", duo = true)
            fw.startsWith("A129Pro") || "PDRM" in fw -> legacy("A129 Pro", "a129_pro", "a129", duo = true)
            fw.startsWith("A129_IR") -> legacy("A129 IR", "a129_ir", "a129", duo = true)
            fw.startsWith("A129") -> legacy("A129", "a129", duo = true)
            fw.startsWith("VIOFO_WR1") -> legacy("WR1", "wr1")
            fw.startsWith("MT1_") || fw.startsWith("GCS") -> legacy("MT1", "wm18")
            fw.startsWith("VIOFO_A139P") -> modern("A139 Pro", "a139pro", "a139", pro = true)
            fw.startsWith("VIOFO_A139") -> modern("A139", "a139")
            fw.startsWith("VIOFO_T130") -> modern("T130", "t130", "a139")
            fw.startsWith("VIOFO_A229PT") -> modern("A229 Pro (Tele)", "a229_p", "a229", "a139", plus = true)
            fw.startsWith("VIOFO_A229P") -> modern("A229 Pro", "a229_p", "a229", "a139", plus = true)
            fw.startsWith("VIOFO_A229S") -> modern("A229 Plus", "a229_s", "a229", "a139", plus = true)
            fw.startsWith("VIOFO_A229U") -> modern("A229 Duo", "a229_u", "a229", "a139", plus = true)
            fw.startsWith("VIOFO_A229") -> modern("A229", "a229", "a139")
            fw.startsWith("VIOFO_WM1") -> modern("WM1", "bm1")
            fw.startsWith("VIOFO_BM1") -> modern("BM1", "bm1")
            fw.startsWith("VIOFO_VS1") -> modern("VS1", "vs1")
            fw.startsWith("VIOFO_A329") || fw.startsWith("VIOFO_A339") -> modern(if ("A339" in fw) "A339" else "A329", "a339", "a229", "a139", plus = true)
            fw.startsWith("VIOFO_") -> modern(fw.removePrefix("VIOFO_").substringBefore('_'), "a139")
            else -> null
        }
    }

    fun modelFromFirmware(fw: String?): String? = detect(fw)?.name
    fun modelFromSsid(ssid: String?): String? = ssid?.let { Regex("(A\\d{3}\\w*|T\\d{3}|WM1|BM1|VS1)", RegexOption.IGNORE_CASE).find(it)?.value?.uppercase() }

    /** `VIOFO_A139_V1.9` -> 1.9, mirroring getFirmwareVersionCode. */
    fun versionOf(fw: String): Double {
        var s = fw.uppercase().trimEnd('B', 'T')
        val i = s.lastIndexOf(".V").takeIf { it >= 0 } ?: s.lastIndexOf("_V").takeIf { it >= 0 } ?: return 0.0
        s = s.substring(i + 2)
        return Regex("^\\d+(\\.\\d)?").find(s)?.value?.toDoubleOrNull() ?: 0.0
    }

    // ---- Label tables ----

    @Volatile private var tables: Map<String, List<String>> = emptyMap()

    fun load(context: Context) {
        if (tables.isNotEmpty()) return
        val json = context.assets.open("option_tables.json").bufferedReader().use { it.readText() }
        tables = parseTables(json)
    }

    fun parseTables(json: String): Map<String, List<String>> {
        val o = JSONObject(json)
        val out = HashMap<String, List<String>>()
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = List(a.length()) { a.getString(it) }
        }
        return out
    }

    /** For tests. */
    fun setTables(t: Map<String, List<String>>) { tables = t }

    /**
     * Finds the best label table: `<base>_<suffix>` for the most specific suffix, preferring the
     * newest versioned variant (`_v1_8`, `_1.7`, …) that the firmware is new enough for.
     */
    fun table(base: String, model: ModelInfo, variant: String = ""): List<String>? {
        val ver = Regex("^(?:_v|_)(\\d+)[._](\\d+)$", RegexOption.IGNORE_CASE)
        for (suffix in model.tables) {
            val stem = "${base}_${suffix}$variant"
            val candidates = tables.keys.filter { it.equals(stem, true) || it.startsWith(stem + "_", true) }
                .mapNotNull { k ->
                    if (k.equals(stem, true)) k to 0.0
                    else ver.find(k.substring(stem.length))?.let { m -> k to "${m.groupValues[1]}.${m.groupValues[2]}".toDouble() }
                }
                .filter { it.second <= model.version || it.second == 0.0 }
            val best = candidates.maxByOrNull { it.second } ?: continue
            return tables[best.first]
        }
        return null
    }

    /** Resolve every supported setting for the connected camera. */
    fun resolve(model: ModelInfo?, values: Map<Int, String>): List<SettingView> {
        val m = model ?: return emptyList()
        return defs.mapNotNull { def ->
            val cmd = (if (m.family == Family.Modern) def.modernCmd else def.legacyCmd) ?: return@mapNotNull null
            val raw = values[cmd] ?: return@mapNotNull null
            when (def.kind) {
                Kind.Switch -> SettingView(def, cmd, emptyList(), null, raw.trim() != "0", true, raw)
                Kind.Choice -> resolveChoice(def, cmd, raw.trim(), m)
            }
        }
    }

    private fun resolveChoice(def: SettingDef, cmd: Int, raw: String, m: ModelInfo): SettingView? {
        if (def.key == "resolution") return resolveResolution(def, cmd, raw, m)
        if (def.key == "ev" && m.family == Family.Modern) {
            val opts = table("exposure_value", m) ?: return null
            val idx = raw.split(',').firstOrNull()?.trim()?.toIntOrNull()
            return SettingView(def.copy(title = "Exposure (front)"), cmd, opts, idx?.takeIf { it in opts.indices }, false, true, raw)
        }
        val opts = def.table?.let { table(it, m) } ?: return null
        val v = raw.toIntOrNull() ?: return null
        val ui = v - offset(def.key, m)
        return SettingView(def, cmd, opts, ui.takeIf { it in opts.indices }, false, true, raw)
    }

    /** Some models number their options differently from the label table. */
    fun offset(key: String, m: ModelInfo): Int = when {
        m.a229Plus && key == "cyclic" -> 1
        m.a229Plus && key == "parking_gsensor" -> 1
        m.name == "A229" && key == "gsensor" && m.version >= 1.1 -> -1
        else -> 0
    }

    private fun resolveResolution(def: SettingDef, cmd: Int, raw: String, m: ModelInfo): SettingView? {
        if (m.family == Family.Legacy) {
            val single = table("video_resolution", m) ?: return null
            val v = raw.toIntOrNull() ?: return null
            if (m.a129Duo) {
                val duo = table("video_resolution_duo", m.copy(tables = m.tables)) ?: tableDuo(m)
                if (duo != null && v >= single.size) {
                    return SettingView(def.copy(title = "Resolution (front + rear)"), cmd, duo, (v - single.size).takeIf { it in duo.indices }, false, true, raw)
                }
            }
            return SettingView(def, cmd, single, v.takeIf { it in single.indices }, false, true, raw)
        }
        // Modern: "<lensCount>,<index>"
        val parts = raw.split(',').map { it.trim() }
        val lenses = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val idx = parts.getOrNull(1)?.toIntOrNull() ?: parts.getOrNull(0)?.toIntOrNull() ?: return null
        val variant = when (lenses) { 2 -> "_dual"; 3 -> "_triple"; else -> "" }
        val opts = table("video_resolution", m, variant) ?: (if (variant.isEmpty()) null else table("video_resolution", m)) ?: return null
        // A229 Plus/Pro/Duo and A329 use model-specific index remaps we can't verify; show read-only.
        val editable = !m.a229Plus
        val ui = if (m.a139Pro && lenses >= 2) a139ProRawToUi(idx, lenses) else idx
        return SettingView(def, cmd, opts, ui.takeIf { it in opts.indices }, false, editable, raw)
    }

    private fun tableDuo(m: ModelInfo): List<String>? =
        m.tables.firstNotNullOfOrNull { s -> tables.entries.filter { it.key.startsWith("video_resolution_duo_$s") }.maxByOrNull { it.key }?.value }

    fun a139ProRawToUi(raw: Int, lenses: Int): Int = when (lenses) {
        3 -> when (raw) { 0 -> 2; 3 -> 0; 4 -> 1; else -> raw }
        else -> when (raw) { 0 -> 2; 1 -> 3; 3 -> 0; 4 -> 1; else -> raw }
    }

    fun a139ProUiToRaw(ui: Int, lenses: Int): Int = when (lenses) {
        3 -> when (ui) { 2 -> 0; 0 -> 3; 1 -> 4; else -> ui }
        else -> when (ui) { 2 -> 0; 3 -> 1; 0 -> 3; 1 -> 4; else -> ui }
    }

    /** What to send for a chosen option. Returns (par, str); exactly one is non-null. */
    fun encode(view: SettingView, uiIndex: Int, m: ModelInfo): Pair<Int?, String?> {
        val key = view.def.key
        if (key == "resolution") {
            if (m.family == Family.Legacy) {
                val single = table("video_resolution", m)?.size ?: 0
                val isDuo = view.def.title.contains("rear")
                return (if (isDuo) uiIndex + single else uiIndex) to null
            }
            val lenses = view.rawValue.split(',').firstOrNull()?.trim()?.toIntOrNull() ?: 1
            val raw = if (m.a139Pro && lenses >= 2) a139ProUiToRaw(uiIndex, lenses) else uiIndex
            return null to "$lenses,$raw"
        }
        if (key == "ev" && m.family == Family.Modern) {
            val parts = view.rawValue.split(',').map { it.trim() }.toMutableList()
            if (parts.isEmpty()) parts += "0"
            parts[0] = uiIndex.toString()
            return null to parts.joinToString(",")
        }
        return (uiIndex + offset(key, m)) to null
    }

    /** Cameras expect spaces in free text replaced with '*' or '+'. */
    fun encodeText(text: String, m: ModelInfo): String {
        val t = text.uppercase().take(11).replace(' ', m.blankReplace[0])
        return t.ifEmpty { if (m.family == Family.Legacy) "+" else "" }
    }

    fun decodeText(text: String?): String = text?.replace('*', ' ')?.replace('+', ' ')?.trim().orEmpty()

    /** Labels for the live view camera switcher. */
    fun pipOptions(m: ModelInfo?): List<String> =
        m?.let { table("display_mode", it) } ?: listOf("Front Camera", "Rear Camera", "Both Front (Rear overlaid)", "Both Rear (Front overlaid)")
}
