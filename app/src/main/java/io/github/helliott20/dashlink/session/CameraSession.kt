package io.github.helliott20.dashlink.session

import android.util.Log
import io.github.helliott20.dashlink.api.CamFile
import io.github.helliott20.dashlink.api.CameraException
import io.github.helliott20.dashlink.api.Cmd
import io.github.helliott20.dashlink.api.Family
import io.github.helliott20.dashlink.api.ModelInfo
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.api.SettingView
import io.github.helliott20.dashlink.api.Settings
import io.github.helliott20.dashlink.data.DownloadState
import io.github.helliott20.dashlink.data.Downloads
import io.github.helliott20.dashlink.data.Prefs
import io.github.helliott20.dashlink.data.SavedDevice
import io.github.helliott20.dashlink.net.CameraLink
import io.github.helliott20.dashlink.net.Notify
import io.github.helliott20.dashlink.net.NotifySocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

enum class Phase { Disconnected, JoiningWifi, Handshake, Ready, Reconnecting, Failed }

enum class CameraMode { Unknown, Live, Playback }

data class SessionState(
    val phase: Phase = Phase.Disconnected,
    val ssid: String? = null,
    val message: String? = null,
    val firmware: String? = null,
    val model: ModelInfo? = null,
    val freeBytes: Long? = null,
    val cardPresent: Boolean = true,
    val recording: Boolean? = null,
    val micOn: Boolean? = null,
    val mode: CameraMode = CameraMode.Unknown,
    val values: Map<Int, String> = emptyMap(),
    val liveUrl: String = NovatekClient.DEFAULT_LIVE_URL,
    /** Bumped whenever the live stream must be re-opened (camera count changed, etc.). */
    val liveToken: Int = 0,
    val files: List<CamFile> = emptyList(),
    val filesLoaded: Boolean = false,
    val filesLoading: Boolean = false,
    val filesError: String? = null,
    val busy: Boolean = false,
    val carNumber: String? = null,
    val customStamp: String? = null,
    val wifiSsid: String? = null,
    val wifiPassword: String? = null,
) {
    val connected get() = phase == Phase.Ready
    val settings: List<SettingView> get() = Settings.resolve(model, values)
    val modelName: String? get() = model?.name
}

/**
 * Orchestrates one camera connection: joining its Wi-Fi, the handshake, keep-alive, the
 * push-event socket, mode switching between live view and file browsing, and recovering
 * from dropouts without the user having to do anything.
 */
class CameraSession(
    private val link: CameraLink,
    val client: NovatekClient,
    private val prefs: Prefs,
    private val downloads: Downloads,
    private val scope: CoroutineScope,
    sockets: javax.net.SocketFactory = javax.net.SocketFactory.getDefault(),
    private val onNewNetwork: () -> Unit = {},
) {
    private val _state = MutableStateFlow(SessionState())
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off user-facing messages (snackbars). */
    val events: SharedFlow<String> = _events

    private var heartbeatJob: Job? = null
    private var handshakeJob: Job? = null
    private var target: SavedDevice? = null
    private val modeLock = Mutex()
    /** Was the camera recording before we switched it to playback? */
    private var resumeRecording = false
    private val notify = NotifySocket(client.baseUrl.host, NovatekClient.NOTIFY_PORT, scope, sockets, ::onNotify)
    private var reconnectAttempts = 0

    /** Screens that need the camera in playback mode (file list, remote player). */
    private val browsers = MutableStateFlow(0)
    private val foreground = MutableStateFlow(true)

    private val model get() = _state.value.model

    init {
        // Safety net: whenever nothing needs playback mode, put the camera back to
        // recording. Covers leaving the file browser, backgrounding the app and
        // downloads finishing while nobody is looking.
        scope.launch {
            combine(_state, browsers, foreground, downloads.tasks) { st, b, fg, tasks ->
                st.connected && st.mode != CameraMode.Live && (b == 0 || !fg) && tasks.none { it.isActive }
            }.distinctUntilChanged().collectLatest { idle ->
                if (idle) {
                    delay(1_500)
                    // Own job, so the state change it causes can't cancel it via collectLatest.
                    scope.launch { enterLive() }
                }
            }
        }
        scope.launch {
            link.state.collect { s ->
                when (s) {
                    is CameraLink.State.Connecting -> if (_state.value.phase != Phase.Reconnecting)
                        _state.update { it.copy(phase = Phase.JoiningWifi, ssid = s.ssid ?: it.ssid, message = null) }
                    is CameraLink.State.Connected -> { onNewNetwork(); startHandshake(s.ssid) }
                    is CameraLink.State.Failed -> onLinkFailed(s.reason, allowRetry = true)
                    CameraLink.State.Idle -> Unit
                }
            }
        }
    }

    /** For screenshot tests and previews only. */
    @androidx.annotation.VisibleForTesting
    fun debugSetState(s: SessionState) { _state.value = s }

    /** Call while a screen needs the camera's files (pair with [releaseBrowsing]). */
    fun acquireBrowsing() = browsers.update { it + 1 }
    fun releaseBrowsing() = browsers.update { (it - 1).coerceAtLeast(0) }
    fun setForeground(fg: Boolean) { foreground.value = fg }

    fun connect(device: SavedDevice) {
        reconnectAttempts = 0
        target = device
        _state.value = SessionState(phase = Phase.JoiningWifi, ssid = device.ssid)
        link.connect(device.ssid, device.password)
    }

    fun connectCurrentWifi() {
        reconnectAttempts = 0
        target = null
        _state.value = SessionState(phase = Phase.JoiningWifi)
        link.useCurrentWifi()
    }

    fun disconnect() {
        stopBackground()
        downloads.pauseAll()
        val wasPlayback = _state.value.mode == CameraMode.Playback && _state.value.phase == Phase.Ready
        _state.value = SessionState()
        scope.launch {
            // Leave the camera recording, as it would be with no phone attached.
            if (wasPlayback) runCatching {
                client.setMode(Cmd.MODE_MOVIE)
                delay(RESUME_DELAY_MS)
                client.setRecording(true)
            }
            link.disconnect()
        }
    }

    private fun stopBackground() {
        heartbeatJob?.cancel(); handshakeJob?.cancel()
        notify.stop()
    }

    private fun onLinkFailed(reason: String, allowRetry: Boolean) {
        stopBackground()
        val s = _state.value
        if (s.phase == Phase.Disconnected) return
        val wasReady = s.phase == Phase.Ready || s.phase == Phase.Reconnecting
        if (downloads.tasks.value.any { it.isActive }) downloads.pauseAll()
        val t = target
        if (allowRetry && wasReady && t != null && reconnectAttempts < MAX_RECONNECTS) {
            reconnectAttempts++
            // Automatic recovery: cameras drop Wi-Fi briefly, e.g. when the engine cranks.
            _state.update { it.copy(phase = Phase.Reconnecting, message = reason) }
            scope.launch {
                delay(2_000)
                if (_state.value.phase == Phase.Reconnecting) link.connect(t.ssid, t.password)
            }
        } else {
            _state.update { it.copy(phase = Phase.Failed, message = reason) }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun startHandshake(ssid: String?) {
        handshakeJob?.cancel()
        val reconnecting = _state.value.phase == Phase.Reconnecting
        _state.update { it.copy(phase = if (reconnecting) Phase.Reconnecting else Phase.Handshake, ssid = ssid ?: it.ssid) }
        handshakeJob = scope.launch {
            // DHCP and the camera's web server can take a few seconds after association.
            val alive = withTimeoutOrNull(20_000) {
                while (!client.heartbeat()) delay(700)
                true
            } ?: false
            if (!alive) {
                _state.update { it.copy(phase = Phase.Failed, message = "Joined the Wi-Fi but no camera answered at ${NovatekClient.DEFAULT_HOST}. Is this a VIOFO camera?") }
                return@launch
            }
            client.claim()
            val fw = runCatching { client.firmware() }.getOrNull()
            val model = Settings.detect(fw)
            val card = runCatching { client.cardStatus() }.getOrNull()
            if (prefs.settings.first().syncClockOnConnect) runCatching { client.syncClock() }
            val values = runCatching { client.currentState() }.getOrDefault(emptyMap())
            val free = runCatching { client.freeSpace() }.getOrNull()
            val wifi = runCatching { client.wifiCredentials() }.getOrNull()
            reconnectAttempts = 0
            _state.update {
                it.copy(
                    phase = Phase.Ready, message = null, firmware = fw, model = model,
                    // Unknown after (re)connect: the screens / safety net pick the right mode.
                    mode = CameraMode.Unknown,
                    freeBytes = free, cardPresent = card != 0, values = values,
                    recording = values[Cmd.RECORDING_TIME]?.let { v -> v != "0" } ?: it.recording,
                    micOn = values[Cmd.AUDIO]?.let { v -> v != "0" } ?: it.micOn,
                    wifiSsid = wifi?.first, wifiPassword = wifi?.second,
                )
            }
            if (card == 3025 || card == 3026) _events.tryEmit("The SD card needs formatting (Settings → Format SD card)")
            if (model == null && fw != null) _events.tryEmit("Unrecognised camera ($fw). Basic features only.")

            val name = ssid ?: wifi?.first ?: link.currentSsid()
            if (name != null) {
                val pw = target?.takeIf { t -> t.ssid == name }?.password ?: wifi?.second ?: NovatekClient.DEFAULT_PASSWORD
                val saved = SavedDevice(name, pw, model?.name, System.currentTimeMillis())
                target = saved
                prefs.saveDevice(saved)
            }
            loadTexts()
            notify.start()
            startHeartbeat()
            if (reconnecting) {
                _events.tryEmit("Reconnected")
                if (downloads.tasks.value.any { it.state == DownloadState.Paused || it.state == DownloadState.Failed }) {
                    enterPlayback()
                    downloads.retryAllFailed()
                }
            }
        }
    }

    private suspend fun loadTexts() {
        val m = model ?: return
        val car = runCatching { client.readText(m.getCarNumberCmd) }.getOrNull()
        val stamp = runCatching { client.readText(m.getCustomStampCmd) }.getOrNull()
        _state.update { it.copy(carNumber = Settings.decodeText(car), customStamp = Settings.decodeText(stamp)) }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            var misses = 0
            var tick = 0
            while (isActive) {
                delay(HEARTBEAT_MS)
                // A big transfer proves the link is alive; don't compete with it.
                if (downloads.tasks.value.any { it.state == DownloadState.Running }) { misses = 0; continue }
                if (client.heartbeat()) {
                    misses = 0
                    if (++tick % 12 == 0) refreshFree()
                } else if (++misses >= 3) {
                    onLinkFailed("Camera stopped responding", allowRetry = true)
                    return@launch
                }
            }
        }
    }

    private fun onNotify(code: Int) {
        when (code) {
            Notify.RECORD_STARTED -> _state.update { it.copy(recording = true) }
            Notify.RECORD_STOPPED -> _state.update { it.copy(recording = false) }
            Notify.MIC_ON -> _state.update { it.copy(micOn = true) }
            Notify.MIC_OFF -> _state.update { it.copy(micOn = false) }
            Notify.POWER_OFF -> onLinkFailed("The camera switched off", allowRetry = false)
            Notify.REMOVED_BY_USER -> onLinkFailed("Another phone connected to the camera", allowRetry = false)
            Notify.SENSOR_CHANGED, Notify.REAR_INSERTED, Notify.REAR_REMOVED -> scope.launch {
                delay(3_000)
                refreshValues()
                _state.update { it.copy(liveToken = it.liveToken + 1) }
                _events.tryEmit(if (code == Notify.REAR_REMOVED) "Rear camera disconnected" else "Camera configuration changed")
            }
            Notify.CARD_REMOVED -> { _state.update { it.copy(cardPresent = false) }; _events.tryEmit("SD card removed") }
            Notify.CARD_INSERTED -> { _state.update { it.copy(cardPresent = true) }; scope.launch { refreshFree() } }
            Notify.STORAGE_FULL -> _events.tryEmit("SD card is full")
            Notify.CARD_SLOW -> _events.tryEmit("SD card is too slow for this resolution")
            Notify.CARD_WRITE_ERROR -> _events.tryEmit("SD card write error — consider formatting")
        }
    }

    private suspend fun refreshFree() {
        runCatching { client.freeSpace() }.getOrNull()?.let { f -> _state.update { it.copy(freeBytes = f) } }
    }

    suspend fun refreshValues() {
        val values = runCatching { client.currentState() }.getOrNull() ?: return
        _state.update {
            it.copy(
                values = values,
                recording = values[Cmd.RECORDING_TIME]?.let { v -> v != "0" } ?: it.recording,
                micOn = values[Cmd.AUDIO]?.let { v -> v != "0" } ?: it.micOn,
            )
        }
    }

    // ---- Modes ----

    /**
     * Live view: movie mode, live view on, fetch stream URL. Resumes recording if we paused it.
     * Non-cancellable: callers are often effects keyed on the mode this changes, and a
     * half-finished switch could leave the camera not recording.
     */
    suspend fun enterLive() = withContext(NonCancellable) { modeLock.withLock {
        if (!_state.value.connected || _state.value.mode == CameraMode.Live) return@withLock
        val fromPlayback = _state.value.mode == CameraMode.Playback
        if (fromPlayback && downloads.tasks.value.any { it.isActive }) return@withLock // keep serving files
        runCatching { client.setMode(Cmd.MODE_MOVIE) }
        client.startLiveView()
        val url = client.liveUrl()
        if (fromPlayback && model?.let { it.a229Plus || it.name == "VS1" } == true) delay(4_000)
        _state.update { it.copy(mode = CameraMode.Live, liveUrl = url) }
        refreshValues()
        if (resumeRecording && prefs.settings.first().resumeRecordingOnExit && _state.value.recording != true) {
            delay(RESUME_DELAY_MS)
            runCatching { client.setRecording(true) }.onSuccess { _state.update { it.copy(recording = true) } }
        }
        resumeRecording = false
    } }

    /**
     * The camera can only list and serve files in playback mode, which stops recording.
     * We remember whether it was recording so [enterLive] can resume it.
     */
    suspend fun enterPlayback() = withContext(NonCancellable) { modeLock.withLock {
        if (_state.value.mode == CameraMode.Playback) return@withLock
        if (_state.value.recording == true) {
            resumeRecording = true
            runCatching { client.setRecording(false) }
        } else if (_state.value.mode == CameraMode.Unknown) {
            resumeRecording = true
        }
        runCatching { client.setMode(Cmd.MODE_PLAYBACK) }.recoverCatching {
            delay(1_000); client.setMode(Cmd.MODE_PLAYBACK)
        }.onFailure { _events.tryEmit("Couldn't switch the camera to playback: ${it.message}") }
        _state.update { it.copy(mode = CameraMode.Playback, recording = false) }
        delay(MODE_SETTLE_MS)
    } }

    // ---- Actions ----

    fun loadFiles() = scope.launch {
        if (_state.value.filesLoading) return@launch
        _state.update { it.copy(filesLoading = true, filesError = null) }
        try {
            if (!_state.value.cardPresent) throw CameraException("No SD card in the camera")
            enterPlayback()
            val files = client.files()
            _state.update { it.copy(files = files, filesLoaded = true, filesLoading = false) }
        } catch (e: Exception) {
            _state.update { it.copy(filesLoading = false, filesError = e.message ?: "Couldn't read the SD card") }
        }
    }

    fun toggleRecording() = act("recording") {
        val on = _state.value.recording != true
        if (_state.value.mode == CameraMode.Playback) enterLive()
        client.setRecording(on)
        _state.update { it.copy(recording = on) }
    }

    fun toggleMic() = act("microphone") {
        val on = _state.value.micOn != true
        client.setMic(on)
        _state.update { it.copy(micOn = on, values = it.values + (Cmd.AUDIO to if (on) "1" else "0")) }
    }

    fun takeSnapshot() = act("snapshot") {
        // The camera can only grab a still while recording.
        if (_state.value.recording != true) {
            client.setRecording(true)
            _state.update { it.copy(recording = true) }
            delay(1_000)
        }
        client.takePhoto()
        _events.tryEmit("Snapshot saved to the SD card")
    }

    /** Forces the live stream to re-open, e.g. after the user taps retry. */
    fun reloadLive() = _state.update { it.copy(liveToken = it.liveToken + 1) }

    /** Next camera (cycling models) or a specific display mode index. */
    fun switchCamera(index: Int?) = act("camera switch") {
        val m = model
        val cmd = m?.pipCmd ?: Cmd.PIP
        if (m?.pipCycles == true || index == null) client.switchCamera(cmd, null) else client.switchCamera(cmd, index)
        if (index != null) _state.update { it.copy(values = it.values + (cmd to index.toString())) }
    }

    fun applySetting(view: SettingView, uiIndex: Int) = act(view.def.title.lowercase()) {
        val m = model ?: throw CameraException("Unknown camera model")
        val (par, str) = Settings.encode(view, uiIndex, m)
        withRecordingPaused {
            if (str != null) client.setText(view.cmd, str) else client.setSetting(view.cmd, par!!)
        }
        if (view.def.key == "resolution") delay(RESOLUTION_SETTLE_MS)
        // Optimistic update, then confirm with the camera.
        _state.update { it.copy(values = it.values + (view.cmd to (str ?: par.toString()))) }
        refreshValues()
        if (view.def.key == "wifi_channel") _events.tryEmit("Restart the camera for the Wi-Fi band change to take effect")
    }

    fun applySwitch(view: SettingView, on: Boolean) = act(view.def.title.lowercase()) {
        withRecordingPaused { client.setSetting(view.cmd, if (on) 1 else 0) }
        _state.update { it.copy(values = it.values + (view.cmd to if (on) "1" else "0")) }
    }

    fun setCarNumber(text: String) = setTextSetting(text, isCar = true)
    fun setCustomStamp(text: String) = setTextSetting(text, isCar = false)

    private fun setTextSetting(text: String, isCar: Boolean) = act("text") {
        val m = model ?: throw CameraException("Unknown camera model")
        val encoded = Settings.encodeText(text, m)
        withRecordingPaused { client.setText(if (isCar) m.carNumberCmd else m.customStampCmd, encoded) }
        _state.update { if (isCar) it.copy(carNumber = text.uppercase().take(11)) else it.copy(customStamp = text.uppercase().take(11)) }
        _events.tryEmit("Saved")
    }

    fun syncClock() = act("clock sync") { client.syncClock(); _events.tryEmit("Camera clock set to phone time") }

    fun formatCard() = act("format") {
        enterPlayback()
        client.formatCard()
        _state.update { it.copy(files = emptyList(), filesLoaded = true) }
        refreshFree()
        _events.tryEmit("SD card formatted")
    }

    fun resetSettings() = act("reset") {
        withRecordingPaused { client.resetSettings() }
        _events.tryEmit("Settings reset. The camera is restarting…")
        model?.let { client.restart(it.restartCmd) }
    }

    fun restartCamera() = act("restart") {
        client.restart(model?.restartCmd ?: 9095)
        _events.tryEmit("Camera is restarting…")
    }

    fun delete(files: List<CamFile>, onDone: () -> Unit = {}) = act("delete") {
        enterPlayback()
        val failed = mutableListOf<String>()
        val deleted = mutableSetOf<String>()
        for (f in files) {
            runCatching { client.delete(f) }
                .onSuccess { deleted += f.fpath }
                .onFailure { failed += (it.message ?: "error") }
        }
        _state.update { it.copy(files = it.files.filterNot { f -> f.fpath in deleted }) }
        if (failed.isNotEmpty()) _events.tryEmit("${failed.size} file(s) not deleted: ${failed.first()}")
        else _events.tryEmit("Deleted ${deleted.size} file(s)")
        refreshFree()
        onDone()
    }

    fun download(files: List<CamFile>) = act("download") {
        enterPlayback()
        files.forEach { downloads.enqueue(it.name, client.fileUrl(it), it.size) }
        _events.tryEmit("Downloading ${files.size} file(s) — you can keep using the app")
    }

    fun setWifi(ssid: String?, password: String?) = act("Wi-Fi change") {
        val m = model
        client.setWifi(ssid, password, m?.blankReplace?.first() ?: '+', m?.restartCmd ?: 9095)
        val old = target
        val newSsid = ssid?.takeIf { it.isNotBlank() } ?: old?.ssid
        if (newSsid != null) {
            val updated = SavedDevice(newSsid, password?.takeIf { it.isNotBlank() } ?: old?.password ?: NovatekClient.DEFAULT_PASSWORD, m?.name, System.currentTimeMillis())
            if (old != null && old.ssid != newSsid) prefs.forgetDevice(old.ssid)
            prefs.saveDevice(updated)
            target = updated
        }
        _events.tryEmit("Wi-Fi updated. Reconnecting with the new details…")
        _state.update { it.copy(phase = Phase.Reconnecting, message = "Camera is restarting its Wi-Fi") }
        stopBackground()
        delay(8_000)
        target?.let { link.connect(it.ssid, it.password) }
    }

    /** Most settings can't be changed mid-recording; pause, apply, resume. */
    private suspend fun <T> withRecordingPaused(block: suspend () -> T): T {
        val rec = _state.value.recording == true && _state.value.mode != CameraMode.Playback
        if (rec) runCatching { client.setRecording(false) }
        try {
            return block()
        } finally {
            if (rec) {
                delay(300)
                runCatching { client.setRecording(true) }
            }
        }
    }

    private fun act(what: String, block: suspend () -> Unit) = scope.launch {
        if (!_state.value.connected) { _events.tryEmit("Not connected to a camera"); return@launch }
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (e: CameraException) {
            _events.tryEmit("Couldn't change $what: ${e.message}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Action $what failed", e)
            _events.tryEmit("Couldn't complete $what — check the connection")
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    val isModern get() = model?.family == Family.Modern

    companion object {
        private const val TAG = "CameraSession"
        private const val HEARTBEAT_MS = 5_000L
        private const val MODE_SETTLE_MS = 500L
        private const val RESUME_DELAY_MS = 600L
        private const val RESOLUTION_SETTLE_MS = 6_000L
        private const val MAX_RECONNECTS = 4
    }
}
