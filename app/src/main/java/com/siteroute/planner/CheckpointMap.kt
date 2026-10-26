package com.siteroute.planner

import android.graphics.Color
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val ROAD_SOURCE = "checkpoint-road-geojson"
private const val ROAD_LAYER = "checkpoint-road-layer"

@Composable
fun CheckpointMap(
    sites: List<Site>,
    selectedSiteId: String?,
    currentLocation: GeoPoint?,
    roadRoute: RoadRoute?,
    listId: String,
    followMe: Boolean,
    refocusCount: Int,
    onSiteTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val latestSites by rememberUpdatedState(sites)
    val latestSelection by rememberUpdatedState(selectedSiteId)
    val latestGps by rememberUpdatedState(currentLocation)
    val latestRoad by rememberUpdatedState(roadRoute)
    val latestClick by rememberUpdatedState(onSiteTap)
    val latestFollow by rememberUpdatedState(followMe)
    val latestRefocus by rememberUpdatedState(refocusCount)
    val latestList by rememberUpdatedState(listId)
    var cameraKey by remember { mutableStateOf("") }

    fun draw(map: MapLibreMap) {
        val style = map.style ?: return
        map.clear()
        latestSites.forEach { site ->
            val flag = when {
                site.id == latestSelection && site.status == "pending" -> "NEXT • "
                site.status == "visited" -> "DONE • "
                site.status == "skipped" -> "SKIPPED • "
                else -> ""
            }
            map.addMarker(
                MarkerOptions()
                    .position(LatLng(site.latitude, site.longitude))
                    .title(flag + site.name)
                    .snippet(site.id)
            )
        }
        latestGps?.let {
            map.addMarker(
                MarkerOptions().position(LatLng(it.latitude, it.longitude))
                    .title("Your current location")
                    .snippet("gps-location")
            )
        }

        val coords = latestRoad?.geometry.orEmpty()
        if (coords.size >= 2) {
            val line = LineString.fromLngLats(coords.map {
                Point.fromLngLat(it.longitude, it.latitude)
            })
            val source = style.getSource(ROAD_SOURCE) as? GeoJsonSource
            if (source == null) {
                style.addSource(GeoJsonSource(ROAD_SOURCE, line))
                style.addLayer(
                    LineLayer(ROAD_LAYER, ROAD_SOURCE).withProperties(
                        PropertyFactory.lineColor(Color.rgb(22, 108, 227)),
                        PropertyFactory.lineWidth(6f),
                        PropertyFactory.lineOpacity(0.93f)
                    )
                )
            } else {
                source.setGeoJson(line)
            }
        } else {
            val source = style.getSource(ROAD_SOURCE) as? GeoJsonSource
            if (source != null) {
                style.removeLayer(ROAD_LAYER)
                style.removeSource(ROAD_SOURCE)
            }
        }

        val gps = latestGps
        val target = latestSites.firstOrNull { it.id == latestSelection }
        val newKey = if (latestFollow && gps != null) {
            "gps/${String.format(java.util.Locale.US, "%.4f:%.4f", gps.latitude, gps.longitude)}"
        } else "${latestList}/${latestSelection}/${latestRefocus}"
        if (newKey != cameraKey) {
            cameraKey = newKey
            if (latestFollow && gps != null) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                    LatLng(gps.latitude, gps.longitude), 15.5
                ))
            } else {
                val pending = latestSites.filter { it.status == "pending" }
                val shown = (if (gps != null) listOf(gps) else emptyList()) +
                    pending.map { GeoPoint(it.latitude, it.longitude) }
                if (shown.size >= 2) {
                    val bounds = LatLngBounds.Builder()
                    shown.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 70))
                } else {
                    val point = target?.let { GeoPoint(it.latitude, it.longitude) }
                        ?: shown.firstOrNull()
                    if (point != null) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(point.latitude, point.longitude), 13.0
                        ))
                    }
                }
            }
        }
    }

    DisposableEffect(owner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            mapView.also { view ->
                view.getMapAsync { map ->
                    map.setStyle(Style.Builder().fromUri(
                        "https://tiles.openfreemap.org/styles/liberty"
                    )) { draw(map) }
                    map.setOnMarkerClickListener { marker ->
                        marker.snippet?.takeUnless { it == "gps-location" }?.let(latestClick)
                        true
                    }
                }
            }
        },
        update = { view -> view.getMapAsync { map -> draw(map) } },
        modifier = modifier
    )
}
