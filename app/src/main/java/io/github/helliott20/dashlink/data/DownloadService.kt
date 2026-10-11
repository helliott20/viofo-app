package io.github.helliott20.dashlink.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.helliott20.dashlink.App
import io.github.helliott20.dashlink.MainActivity
import io.github.helliott20.dashlink.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the process alive (and the user informed) while clips are copied off the camera. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(
            this, ID, build("Preparing download…", 0, 0, true),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            (application as App).container.downloads.tasks.collectLatest { tasks ->
                val active = tasks.filter { it.isActive }
                if (active.isEmpty()) {
                    val done = tasks.count { it.state == DownloadState.Done }
                    val failed = tasks.count { it.state == DownloadState.Failed || it.state == DownloadState.Paused }
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    if (done + failed > 0) {
                        nm.notify(ID + 1, build(
                            if (failed == 0) "Downloaded $done clip${if (done == 1) "" else "s"}" else "$done downloaded, $failed need retry",
                            0, 0, false, ongoing = false,
                        ))
                    }
                    stopSelf()
                    return@collectLatest
                }
                val cur = active.firstOrNull { it.state == DownloadState.Running } ?: active.first()
                val pct = (cur.progress * 100).toInt()
                nm.notify(ID, build("${cur.name} · ${active.size} left", pct, 100, cur.totalBytes <= 0))
            }
        }
    }

    private fun build(text: String, progress: Int, max: Int, indeterminate: Boolean, ongoing: Boolean = true) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("DashLink")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .apply { if (ongoing) setProgress(max, progress, indeterminate) }
            .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 42
    }
}
