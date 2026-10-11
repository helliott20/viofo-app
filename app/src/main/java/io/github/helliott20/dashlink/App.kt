package io.github.helliott20.dashlink

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner


class App : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // If the user leaves the app while browsing files, the session puts the dashcam
        // back to recording (see CameraSession's safety net).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.session.setForeground(true)
            override fun onStop(owner: LifecycleOwner) = container.session.setForeground(false)
        })
    }

    /** Thumbnails come from the camera over the same bound client, and are cached on disk. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { container.thumbHttp }
        .components { add(VideoFrameDecoder.Factory()) }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(200L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}
