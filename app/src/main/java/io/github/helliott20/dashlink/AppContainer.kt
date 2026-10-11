package io.github.helliott20.dashlink

import android.content.Context
import io.github.helliott20.dashlink.api.NovatekClient
import io.github.helliott20.dashlink.data.Downloads
import io.github.helliott20.dashlink.data.Prefs
import io.github.helliott20.dashlink.net.CameraLink
import io.github.helliott20.dashlink.session.CameraSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Hand-rolled dependency graph; small enough not to need a DI framework. */
class AppContainer(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Commands: short timeouts so a dead link is noticed quickly. */
    val commandHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS) // file list on a full card can be slow
        .retryOnConnectionFailure(true)
        // The camera's tiny web server misbehaves with keep-alive under load.
        .connectionPool(ConnectionPool(1, 5, TimeUnit.SECONDS))
        .build()

    /** Bulk transfers. */
    val downloadHttp: OkHttpClient = commandHttp.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(1, 30, TimeUnit.SECONDS))
        .build()

    /** Thumbnails; the camera serves them one at a time, so cap concurrency. */
    val thumbHttp: OkHttpClient = commandHttp.newBuilder()
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 2 })
        .build()

    val prefs = Prefs(context)
    val link = CameraLink(context, scope)
    val client = NovatekClient(commandHttp)
    val downloads = Downloads(context, downloadHttp, scope)
    val session = CameraSession(link, client, prefs, downloads, scope)
}
