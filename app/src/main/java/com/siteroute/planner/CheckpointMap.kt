package com.siteroute.planner

import android.graphics.Color
import android.graphics.PointF
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val POINTS = "srp-pins"
private const val PENDING = "srp-pending"
private const val DONE = "srp-done"
private const val SKIPPED = "srp-skipped"
private const val SELECTED = "srp-selected"
private const val GPS = "srp-gps"
private const val ROAD = "srp-road"
private const val PREVIEW = "srp-preview"

/**
 * Route pins are rendered by the MapLibre style engine (GeoJSON + layers).
 * We no longer clear/recreate legacy marker annotations for every GPS or Compose update:
 * doing so made pins unreliable and camera positioning raced with initial view layout.
 */
@Composable
fun CheckpointMap(
    sites: List<Site>,
    selectedSiteId: String?,
    currentLocation: GeoPoint?,
    roadRoute: RoadRoute?,
    routeWaypoints: List<GeoPoint>,
    listId: String,
    followMe: Boolean,
    refocusCount: Int,
    onSiteTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = remember { MapView(context).apply { onCreate(null) } }
    val onClick by rememberUpdatedState(onSiteTap)
    var mapObject by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var lastFrameKey by remember { mutableStateOf("") }
    val newestSites by rememberUpdatedState(sites)

    DisposableEffect(view, owner) {
        val listener = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(listener)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onResume()
        onDispose {
            owner.lifecycle.removeObserver(listener)
            view.onPause()
            view.onStop()
            view.onDestroy()
        }
    }

    AndroidView(
        factory = { view },
        modifier = modifier,
        update = { mapView ->
            mapView.getMapAsync { map ->
                if (mapObject !== map) {
                    mapObject = map
                    map.setOnMapClickListener { clicked ->
                        val projection = map.projection
                        val touch = projection.toScreenLocation(clicked)
                        val limit = 44f * context.resources.displayMetrics.density
                        val nearest = newestSites.map { site ->
                            val pixel = projection.toScreenLocation(LatLng(site.latitude, site.longitude))
                            val dx = touch.x - pixel.x
                            val dy = touch.y - pixel.y
                            site.id to (dx * dx + dy * dy)
                        }.minByOrNull { it.second }
                        if (nearest != null && nearest.second <= limit * limit) {
                            onClick(nearest.first)
                            true
                        } else false
                    }
                    map.setStyle(Style.Builder().fromUri(STYLE_URL)) { style ->
                        setupLayers(style)
                        styleReady = true
                        lastFrameKey = ""
                    }
                }
            }
        }
    )

    // Map updates are driven by *data changes*, not asynchronous getMapAsync callback races.
    LaunchedEffect(styleReady, mapObject, sites, selectedSiteId, currentLocation,
        roadRoute, routeWaypoints, listId, refocusCount, followMe) {
        val map = mapObject
        val style = map?.style
        if (!styleReady || map == null || style == null) return@LaunchedEffect
        applyPoints(style, sites, selectedSiteId, currentLocation)
        applyRoad(style, roadRoute, routeWaypoints)

        // Only fit on list change / explicit Fit route / changed site geometry.
        // Do not unexpectedly move a manually panned map on every GPS update.
        val fitKey = "$listId/$refocusCount/" +
            sites.joinToString { "${it.id}:${it.latitude}:${it.longitude}" }
        val gps = currentLocation
        val cameraKey = if (followMe && gps != null)
            "follow/${String.format(java.util.Locale.US, "%.4f,%.4f", gps.latitude, gps.longitude)}"
        else fitKey
        if (cameraKey != lastFrameKey) {
            // Save only after camera successfully applies; view dimensions must exist.
            view.post {
                if (view.width <= 0 || view.height <= 0 || map.style == null) return@post
                if (followMe && gps != null) {
                    map.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(gps.latitude, gps.longitude), 15.5)
                    )
                } else if (sites.isNotEmpty()) {
                    val points = sites.map { LatLng(it.latitude, it.longitude) }
                    val first = points.first()
                    val unique = points.any {
                        kotlin.math.abs(it.latitude - first.latitude) > 0.000001 ||
                            kotlin.math.abs(it.longitude - first.longitude) > 0.000001
                    }
                    if (points.size == 1 || !unique) {
                        map.animateCamera(CameraUpdateFactory.newLatLngZoom(first, 13.0))
                    } else {
                        // Already measured: no zero-sized camera fit and no offscreen pins.
                        val bounds = LatLngBounds.Builder()
                        points.forEach(bounds::include)
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngBounds(bounds.build(), 64)
                        )
                    }
                } else if (gps != null) {
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(gps.latitude, gps.longitude), 13.0
                    ))
                }
                lastFrameKey = cameraKey
            }
        }
    }
}

private fun pointsSource(style: Style, key: String, features: List<Feature>) {
    val collection = FeatureCollection.fromFeatures(features)
    val old = style.getSource(key) as? GeoJsonSource
    if (old != null) old.setGeoJson(collection)
    else style.addSource(GeoJsonSource(key, collection))
}

private fun addCircles(style: Style, source: String, color: Int, radius: Float) {
    style.addLayer(CircleLayer("$source-circles", source).withProperties(
        PropertyFactory.circleColor(color),
        PropertyFactory.circleRadius(radius),
        PropertyFactory.circleStrokeColor(Color.WHITE),
        PropertyFactory.circleStrokeWidth(2f)
    ))
}

private fun setupLayers(style: Style) {
    for (src in listOf(PREVIEW, ROAD)) {
        style.addSource(GeoJsonSource(src))
    }
    style.addLayer(LineLayer("$PREVIEW-line", PREVIEW).withProperties(
        PropertyFactory.lineColor(Color.rgb(228, 121, 28)),
        PropertyFactory.lineWidth(4f),
        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        PropertyFactory.lineOpacity(0.9f)
    ))
    style.addLayer(LineLayer("$ROAD-line", ROAD).withProperties(
        PropertyFactory.lineColor(Color.rgb(18, 105, 229)),
        PropertyFactory.lineWidth(7f),
        PropertyFactory.lineOpacity(0.95f)
    ))
    for (src in listOf(PENDING, DONE, SKIPPED, SELECTED, GPS, POINTS)) {
        style.addSource(GeoJsonSource(src))
    }
    addCircles(style, PENDING, Color.rgb(24, 101, 209), 15f)
    addCircles(style, DONE, Color.rgb(35, 147, 78), 15f)
    addCircles(style, SKIPPED, Color.rgb(117, 124, 133), 15f)
    addCircles(style, SELECTED, Color.rgb(221, 55, 40), 18f)
    addCircles(style, GPS, Color.rgb(58, 142, 255), 9f)
    style.addLayer(SymbolLayer("$POINTS-numbers", POINTS).withProperties(
        PropertyFactory.textField(Expression.get("number")),
        PropertyFactory.textColor(Color.WHITE),
        PropertyFactory.textSize(14f),
        PropertyFactory.textAllowOverlap(true),
        PropertyFactory.textIgnorePlacement(true)
    ))
}

private fun feature(site: Site, index: Int): Feature =
    Feature.fromGeometry(Point.fromLngLat(site.longitude, site.latitude)).apply {
        addStringProperty("number", (index + 1).toString())
        addStringProperty("id", site.id)
    }

private fun applyPoints(
    style: Style, sites: List<Site>, selectedId: String?, gps: GeoPoint?
) {
    val numbered = sites.mapIndexed { index, site -> site to feature(site, index) }
    pointsSource(style, POINTS, numbered.map { it.second })
    pointsSource(style, PENDING, numbered.filter {
        it.first.status == "pending" && it.first.id != selectedId
    }.map { it.second })
    pointsSource(style, DONE, numbered.filter { it.first.status == "visited" }.map { it.second })
    pointsSource(style, SKIPPED, numbered.filter { it.first.status == "skipped" }.map { it.second })
    pointsSource(style, SELECTED, numbered.filter {
        it.first.status == "pending" && it.first.id == selectedId
    }.map { it.second })
    pointsSource(style, GPS, if (gps == null) emptyList() else listOf(
        Feature.fromGeometry(Point.fromLngLat(gps.longitude, gps.latitude))
    ))
}

private fun applyRoad(style: Style, route: RoadRoute?, waypoints: List<GeoPoint>) {
    val real = route?.geometry.orEmpty()
    val preview = if (real.size >= 2) emptyList() else waypoints
    val road = style.getSource(ROAD) as? GeoJsonSource
    val fallback = style.getSource(PREVIEW) as? GeoJsonSource
    road?.setGeoJson(lineFeatures(real))
    fallback?.setGeoJson(lineFeatures(preview))
}

private fun lineFeatures(points: List<GeoPoint>): FeatureCollection =
    FeatureCollection.fromFeatures(
        if (points.size < 2) emptyList() else listOf(
            Feature.fromGeometry(LineString.fromLngLats(points.map {
                Point.fromLngLat(it.longitude, it.latitude)
            }))
        )
    )
