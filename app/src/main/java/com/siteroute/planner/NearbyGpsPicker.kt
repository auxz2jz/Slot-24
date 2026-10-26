package com.siteroute.planner

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Shows the acquired phone position and nearby business/street-address choices
 * on a real map. Selecting a choice fills the existing SiteEditor; the user still
 * confirms Save so a wrong map candidate doesn't silently create a stop.
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
    val origin = remember(gps) { NearbyPlaces.gpsOption(gps) }
    val places = listOf(origin) + result?.places.orEmpty()

    LaunchedEffect(gps, retry) {
        searching = true
        result = try {
            NearbyPlaces.search(context.applicationContext, gps)
        } catch (_: Exception) {
            NearbySearchResult(emptyList(),
                "Nearby places unavailable. You can still save the exact GPS position.")
        } finally {
            searching = false
        }
    }

    val previewSites = places.mapIndexed { index, option ->
        Site(
            id = option.id,
            listId = "preview",
            name = option.name,
            address = option.address,
            latitude = option.location.latitude,
            longitude = option.location.longitude,
            position = index
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add stop near my GPS") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    String.format(Locale.US, "GPS: %.6f, %.6f", gps.latitude, gps.longitude),
                    style = MaterialTheme.typography.bodySmall
                )
                CheckpointMap(
                    sites = previewSites,
                    selectedSiteId = selectedId,
                    focusedSiteId = null,
                    currentLocation = gps,
                    roadRoute = null,
                    routeWaypoints = emptyList(),
                    listId = "nearby-gps-${gps.latitude}-${gps.longitude}",
                    followMe = false,
                    refocusCount = retry,
                    onSiteTap = { selectedId = it },
                    modifier = Modifier.fillMaxWidth().height(195.dp)
                )
                if (searching) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Finding nearby businesses and addresses…",
                        style = MaterialTheme.typography.bodySmall)
                }
                result?.warning?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                Text("Choose a place below, or tap its numbered pin and Use Selected.",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(places, key = { it.id }) { place ->
                        val selected = selectedId == place.id
                        if (selected) {
                            Button(
                                onClick = { onSelect(place) },
                                contentPadding = PaddingValues(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(place.name)
                                    if (place.address.isNotBlank() && place.address != place.name) {
                                        Text(place.address,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (place.id != "gps") {
                                        Text("${place.category} • approx. ${place.metersFromGps} m away",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onSelect(place) },
                                contentPadding = PaddingValues(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(place.name)
                                    if (place.address.isNotBlank() && place.address != place.name) {
                                        Text(place.address,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (place.id != "gps") {
                                        Text("${place.category} • approx. ${place.metersFromGps} m away",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
                Text(
                    "Business names from OpenStreetMap may be incomplete. " +
                        "Searching sends this GPS position to a public map-data server.",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSelect(places.firstOrNull { it.id == selectedId } ?: origin)
            }) { Text("Use selected") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { retry++ }, enabled = !searching) {
                    Text("Search again")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
