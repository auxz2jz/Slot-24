package com.siteroute.planner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * GPS is not a postal address: user can tap a house or business on the zoomable map,
 * select an OSM address/place nearby, or choose precisely the tapped point.
 */
@Composable
fun NearbyGpsPicker(
    gps: GeoPoint,
    onSelect: (NearbyPlace) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var result by remember(gps) { mutableStateOf<NearbySearchResult?>(null) }
    var searching by remember(gps) { mutableStateOf(true) }
    var selectedId by remember(gps) { mutableStateOf("gps") }
    var retry by remember(gps) { mutableIntStateOf(0) }
    var tappedPoint by remember(gps) { mutableStateOf<GeoPoint?>(null) }
    var tapAddresses by remember(gps) { mutableStateOf<List<NearbyPlace>>(emptyList()) }
    var tapLoading by remember(gps) { mutableStateOf(false) }
    val origin = remember(gps) { NearbyPlaces.gpsOption(gps) }

    LaunchedEffect(gps, retry) {
        searching = true
        result = try {
            NearbyPlaces.search(context.applicationContext, gps)
        } catch (_: Exception) {
            NearbySearchResult(emptyList(),
                "Nearby data unavailable. You can still select exact GPS or tap any map position.")
        } finally {
            searching = false
        }
    }

    LaunchedEffect(tappedPoint) {
        val touched = tappedPoint ?: return@LaunchedEffect
        tapLoading = true
        tapAddresses = try {
            NearbyPlaces.atMapTap(context.applicationContext, touched)
        } catch (_: Exception) {
            emptyList()
        } finally {
            tapLoading = false
        }
        // Favor a house-number address very close to the touched building.
        val closest = (tapAddresses + result?.places.orEmpty())
            .filter { candidate ->
                val deltaLat = (candidate.location.latitude - touched.latitude) * 111000.0
                val deltaLon = (candidate.location.longitude - touched.longitude) *
                    111000.0 * kotlin.math.cos(Math.toRadians(touched.latitude))
                kotlin.math.hypot(deltaLat, deltaLon) < 65.0 &&
                    (candidate.category == "Nearby street address" ||
                        candidate.category == "Street address")
            }.minByOrNull {
                kotlin.math.hypot(it.location.latitude - touched.latitude,
                    it.location.longitude - touched.longitude)
            }
        selectedId = closest?.id ?: "tap-point"
    }

    val tapOption = tappedPoint?.let(NearbyPlaces::mapTapOption)
    val places = (listOf(origin) + listOfNotNull(tapOption) +
        tapAddresses + result?.places.orEmpty()).distinctBy { it.id }
    val showPlaces = if (tappedPoint == null) places else {
        val current = tappedPoint!!
        listOfNotNull(tapOption) +
            (tapAddresses + result?.places.orEmpty()).distinctBy { it.id }
                .sortedBy { option ->
                    val dLat = (option.location.latitude - current.latitude) * 111000.0
                    val dLon = (option.location.longitude - current.longitude) *
                        111000.0 * kotlin.math.cos(Math.toRadians(current.latitude))
                    kotlin.math.hypot(dLat, dLon)
                }.take(18) + origin
    }
    val previewSites = places.mapIndexed { index, place ->
        Site(id = place.id, listId = "nearby", name = place.name,
            address = place.address, latitude = place.location.latitude,
            longitude = place.location.longitude, position = index)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose nearby address or GPS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("Zoom in and tap a house, address, or business on the map.",
                    style = MaterialTheme.typography.bodySmall)
                CheckpointMap(
                    sites = previewSites,
                    selectedSiteId = selectedId,
                    focusedSiteId = null,
                    currentLocation = gps,
                    roadRoute = null,
                    routeWaypoints = emptyList(),
                    listId = "gps-picker",
                    followMe = false,
                    refocusCount = retry,
                    onSiteTap = { selectedId = it },
                    onMapTap = { touched ->
                        tappedPoint = touched
                        selectedId = "tap-point"
                        tapAddresses = emptyList()
                    },
                    pickerMode = true,
                    modifier = Modifier.fillMaxWidth().height(238.dp)
                )
                if (searching || tapLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Looking for nearby addresses and businesses…",
                        style = MaterialTheme.typography.bodySmall)
                }
                result?.warning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (tappedPoint != null) {
                    Text("Tap a listed address to select it, or use the exact tapped point.",
                        style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 188.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(showPlaces.distinctBy { it.id }, key = { it.id }) { place ->
                        val selected = selectedId == place.id
                        OutlinedButton(
                            onClick = { selectedId = place.id },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(8.dp),
                            colors = if (selected) ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ) else ButtonDefaults.outlinedButtonColors()
                        ) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(place.name)
                                if (place.address.isNotBlank() && place.address != place.name) {
                                    Text(place.address,
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Text(
                                    if (place.id == "tap-point" || place.id == "gps")
                                        String.format(Locale.US, "%.6f, %.6f",
                                            place.location.latitude, place.location.longitude)
                                    else place.category,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
                Text("Nearby addresses depend on mapped building data and geocoder coverage. " +
                    "Tap locations may be approximate. Your selected map point is sent to " +
                    "Android's geocoder for address lookup.",
                    style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelect(places.firstOrNull { it.id == selectedId } ?: origin)
            }) { Text("Use selected") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    tappedPoint = null
                    tapAddresses = emptyList()
                    selectedId = "gps"
                    retry++
                }, enabled = !searching) { Text("Recenter") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
