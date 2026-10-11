package io.github.helliott20.dashlink.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("dashlink")

/** A camera the user has connected to before. */
data class SavedDevice(
    val ssid: String,
    val password: String,
    val model: String?,
    val lastConnected: Long,
)

data class AppSettings(
    val syncClockOnConnect: Boolean = true,
    val resumeRecordingOnExit: Boolean = true,
    val rtspOverTcp: Boolean = false,
    val autoConnectLast: Boolean = true,
    val keepScreenOnLive: Boolean = true,
)

class Prefs(private val context: Context) {
    private object K {
        val devices = stringPreferencesKey("devices")
        val syncClock = booleanPreferencesKey("sync_clock")
        val resumeRec = booleanPreferencesKey("resume_rec")
        val rtspTcp = booleanPreferencesKey("rtsp_tcp")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val devices: Flow<List<SavedDevice>> = context.dataStore.data.map { decode(it[K.devices].orEmpty()) }

    val settings: Flow<AppSettings> = context.dataStore.data.map {
        AppSettings(
            syncClockOnConnect = it[K.syncClock] ?: true,
            resumeRecordingOnExit = it[K.resumeRec] ?: true,
            rtspOverTcp = it[K.rtspTcp] ?: false,
            autoConnectLast = it[K.autoConnect] ?: true,
            keepScreenOnLive = it[K.keepScreenOn] ?: true,
        )
    }

    suspend fun saveDevice(device: SavedDevice) = context.dataStore.edit { p ->
        val list = decode(p[K.devices].orEmpty()).filterNot { it.ssid == device.ssid } + device
        p[K.devices] = encode(list.sortedByDescending { it.lastConnected })
    }

    suspend fun forgetDevice(ssid: String) = context.dataStore.edit { p ->
        p[K.devices] = encode(decode(p[K.devices].orEmpty()).filterNot { it.ssid == ssid })
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) = context.dataStore.edit { p ->
        val cur = AppSettings(
            p[K.syncClock] ?: true, p[K.resumeRec] ?: true, p[K.rtspTcp] ?: false,
            p[K.autoConnect] ?: true, p[K.keepScreenOn] ?: true,
        )
        val n = transform(cur)
        p[K.syncClock] = n.syncClockOnConnect
        p[K.resumeRec] = n.resumeRecordingOnExit
        p[K.rtspTcp] = n.rtspOverTcp
        p[K.autoConnect] = n.autoConnectLast
        p[K.keepScreenOn] = n.keepScreenOnLive
    }

    // Line/field separated with control chars that can't appear in an SSID or WPA passphrase.
    private fun encode(list: List<SavedDevice>) = list.joinToString("\u001e") {
        listOf(it.ssid, it.password, it.model.orEmpty(), it.lastConnected.toString()).joinToString("\u001f")
    }

    private fun decode(s: String): List<SavedDevice> = s.split('\u001e').mapNotNull { row ->
        val f = row.split('\u001f')
        if (f.size < 4 || f[0].isEmpty()) null
        else SavedDevice(f[0], f[1], f[2].ifEmpty { null }, f[3].toLongOrNull() ?: 0)
    }
}
