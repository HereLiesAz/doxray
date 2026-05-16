package com.hereliesaz.doxray.ui.dossier

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.preference.PreferenceManager
import com.hereliesaz.doxray.db.Encounter
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-bleed map showing every [Encounter] with non-null lat/lon as a pin.
 * Tapping a pin opens osmdroid's default InfoWindow with the formatted
 * timestamp and accuracy radius.
 */
@Composable
fun EncounterMap(
    encounters: List<Encounter>,
    modifier: Modifier = Modifier,
) {
    var mapView: MapView? = null
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            osmdroidInit(ctx)
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                addEncounterMarkers(this, encounters)
                fitMapToEncounters(this, encounters)
                onResume()
            }.also { mapView = it }
        },
        update = { map ->
            map.overlays.clear()
            addEncounterMarkers(map, encounters)
            fitMapToEncounters(map, encounters)
            map.invalidate()
            map.onResume()
        },
    )
    DisposableEffect(Unit) {
        onDispose {
            mapView?.onPause()
            mapView?.onDetach()
        }
    }
}

private fun osmdroidInit(context: Context) {
    val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    val cfg = Configuration.getInstance()
    cfg.load(context, prefs)
    cfg.userAgentValue = "com.hereliesaz.doxray"
    cfg.osmdroidTileCache = File(context.cacheDir, "osmdroid_tiles")
}

private fun addEncounterMarkers(map: MapView, encounters: List<Encounter>) {
    val fmt = SimpleDateFormat("MMM dd yyyy, HH:mm:ss", Locale.getDefault())
    for (e in encounters) {
        val lat = e.latitude ?: continue
        val lon = e.longitude ?: continue
        val marker = Marker(map).apply {
            position = GeoPoint(lat, lon)
            title = fmt.format(Date(e.timestamp))
            snippet = e.locationAccuracyMeters?.let { "±${it.toInt()}m" }.orEmpty()
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        }
        map.overlays.add(marker)
    }
}

private fun fitMapToEncounters(map: MapView, encounters: List<Encounter>) {
    val points = encounters.mapNotNull { e ->
        val lat = e.latitude ?: return@mapNotNull null
        val lon = e.longitude ?: return@mapNotNull null
        GeoPoint(lat, lon)
    }
    if (points.isEmpty()) return
    if (points.size == 1) {
        map.controller.setZoom(15.0)
        map.controller.setCenter(points[0])
    } else {
        val box = BoundingBox.fromGeoPoints(points)
        map.zoomToBoundingBox(box, false, 64)
    }
}
