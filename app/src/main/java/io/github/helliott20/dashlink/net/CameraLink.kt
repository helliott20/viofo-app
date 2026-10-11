package io.github.helliott20.dashlink.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the Wi-Fi link to the dashcam.
 *
 * The camera's access point has no internet, so Android keeps routing traffic over mobile
 * data — the #1 cause of "can't connect" in the stock app. We hand out the camera
 * [Network] so that camera sockets are created on it explicitly (see CameraSocketFactory),
 * while the rest of the phone's traffic carries on over its normal connection.
 */
class CameraLink(context: Context, private val scope: CoroutineScope) {

    sealed interface State {
        data object Idle : State
        data class Connecting(val ssid: String?) : State
        data class Connected(val ssid: String?, val network: Network) : State
        data class Failed(val reason: String) : State
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var requestCallback: ConnectivityManager.NetworkCallback? = null
    private var timeoutJob: Job? = null

    val boundNetwork: Network? get() = (state.value as? State.Connected)?.network

    /**
     * Requests a connection to the camera's access point. With [ssid] null we match any
     * SSID that looks like a VIOFO camera. Falls back to [useCurrentWifi] on Android 9 and
     * below, where apps cannot request a specific network.
     */
    fun connect(ssid: String?, password: String?) {
        release()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            useCurrentWifi()
            return
        }
        _state.value = State.Connecting(ssid)
        val spec = WifiNetworkSpecifier.Builder().apply {
            if (ssid != null) setSsid(ssid) else setSsidPattern(PatternMatcher("VIOFO", PatternMatcher.PATTERN_PREFIX))
            if (!password.isNullOrEmpty()) setWpa2Passphrase(password)
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkUp(network, ssid)
            override fun onLost(network: Network) = onNetworkLost(network)
            override fun onUnavailable() {
                _state.value = State.Failed("Camera Wi-Fi not found or connection was declined")
                requestCallback = null
            }
        }
        requestCallback = cb
        try {
            cm.requestNetwork(request, cb)
        } catch (e: SecurityException) {
            _state.value = State.Failed("Missing permission to change Wi-Fi")
            requestCallback = null
            return
        }
        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (state.value is State.Connecting) {
                release()
                _state.value = State.Failed("Timed out joining camera Wi-Fi. Is the camera's Wi-Fi switched on?")
            }
        }
    }

    /**
     * Uses whatever Wi-Fi network the phone is already on (e.g. the user joined the
     * camera via system settings). We still bind to it so traffic doesn't leak to mobile data.
     */
    fun useCurrentWifi() {
        release()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        _state.value = State.Connecting(null)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onNetworkUp(network, currentSsid(network))
            override fun onLost(network: Network) = onNetworkLost(network)
        }
        requestCallback = cb
        cm.registerNetworkCallback(request, cb)
        timeoutJob = scope.launch {
            delay(5_000)
            if (state.value is State.Connecting) {
                release()
                _state.value = State.Failed("Phone isn't connected to any Wi-Fi network")
            }
        }
    }

    private fun onNetworkUp(network: Network, ssid: String?) {
        timeoutJob?.cancel()
        Log.i(TAG, "Camera network available: $network ($ssid)")
        _state.value = State.Connected(ssid ?: currentSsid(network), network)
    }

    private fun onNetworkLost(network: Network) {
        if (boundNetwork == network) _state.value = State.Failed("Lost connection to camera Wi-Fi")
    }

    /** Drops the camera network and restores normal routing. */
    fun disconnect() {
        release()
        _state.value = State.Idle
    }

    private fun release() {
        timeoutJob?.cancel()
        requestCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        requestCallback = null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun currentSsid(network: Network? = null): String? {
        val info: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && network != null) {
            cm.getNetworkCapabilities(network)?.transportInfo as? WifiInfo
        } else {
            wifi.connectionInfo
        }
        val raw = info?.ssid ?: return null
        val clean = raw.removeSurrounding("\"")
        return if (clean == WifiManager.UNKNOWN_SSID.removeSurrounding("\"") || clean.isBlank()) null else clean
    }

    companion object {
        private const val TAG = "CameraLink"
        private const val CONNECT_TIMEOUT_MS = 45_000L
    }
}
