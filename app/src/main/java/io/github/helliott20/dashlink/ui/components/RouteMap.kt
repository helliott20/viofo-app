package io.github.helliott20.dashlink.ui.components

import android.graphics.Color as AColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.helliott20.dashlink.gps.GpsPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** OpenStreetMap view with the clip's route and a marker that follows playback. */
@Composable
fun RouteMap(points: List<GpsPoint>, current: GpsPoint?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val map = remember {
        Configuration.getInstance().userAgentValue = context.packageName
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
        }
    }
    val marker = remember { Marker(map).apply { setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER) } }
    DisposableEffect(points) {
        map.overlays.clear()
        if (points.isNotEmpty()) {
            val geo = points.map { GeoPoint(it.lat, it.lon) }
            map.overlays += Polyline(map).apply {
                setPoints(geo)
                outlinePaint.color = AColor.rgb(0xFF, 0xB0, 0x20)
                outlinePaint.strokeWidth = 10f
            }
            map.overlays += marker
            map.post {
                if (geo.size > 1) map.zoomToBoundingBox(BoundingBox.fromGeoPoints(geo).increaseByScale(1.3f), false)
                else { map.controller.setZoom(16.0); map.controller.setCenter(geo.first()) }
            }
        }
        onDispose { }
    }
    DisposableEffect(Unit) {
        map.onResume()
        onDispose { map.onPause(); map.onDetach() }
    }
    AndroidView(factory = { map }, modifier = modifier, update = {
        if (current != null) {
            marker.position = GeoPoint(current.lat, current.lon)
            marker.rotation = -current.bearing
            it.invalidate()
        }
    })
}
