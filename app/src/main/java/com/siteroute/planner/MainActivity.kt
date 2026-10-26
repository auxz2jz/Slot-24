package com.siteroute.planner

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(applicationContext)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) { PlannerScreen() }
        }
    }
}

private fun launchDirections(context: Context, site: Site) {
    val coords = site.latitude.toString() + "," + site.longitude.toString()
    val url = "https://www.google.com/maps/dir/?api=1&destination=" +
        Uri.encode(coords) + "&travelmode=driving&dir_action=navigate"
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: ActivityNotFoundException) {
        // A device without a browser/navigation handler cannot launch directions.
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlannerScreen() {
    val context = LocalContext.current
    val store = remember { SiteStore(context.applicationContext) }
    var data by remember { mutableStateOf(store.load()) }
    var mapMode by remember { mutableStateOf(true) }
    var showAddList by remember { mutableStateOf(false) }
    var showDeleteList by remember { mutableStateOf(false) }
    var editingSite by remember { mutableStateOf<Site?>(null) }
    var isAddingSite by remember { mutableStateOf(false) }
    var selectedSiteId by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    fun update(newData: PlannerData) {
        data = newData
        store.save(newData)
    }

    val currentList = data.lists.firstOrNull { it.id == data.activeListId }
    val stops = RouteLogic.orderedSites(data)
    val nextStop = RouteLogic.nextPending(data)
    val finished = stops.count { it.status == "visited" }
    val selectedSite = stops.firstOrNull { it.id == selectedSiteId } ?: nextStop

    fun completeAndGo(site: Site, outcome: String) {
        val changed = data.copy(sites = data.sites.map {
            if (it.id == site.id) it.copy(status = outcome) else it
        })
        update(changed)
        val next = RouteLogic.nextPending(changed)
        selectedSiteId = next?.id
        if (next != null) launchDirections(context, next)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Site Route Planner") })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { menuOpen = true }) {
                        Text((currentList?.name ?: "Choose list") + " ▾")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        data.lists.forEach { list ->
                            DropdownMenuItem(text = { Text(list.name) }, onClick = {
                                update(data.copy(activeListId = list.id))
                                selectedSiteId = null
                                menuOpen = false
                            })
                        }
                    }
                }
                TextButton(onClick = { showAddList = true }) { Text("+ List") }
                TextButton(
                    enabled = data.lists.size > 1,
                    onClick = { showDeleteList = true }
                ) { Text("Delete") }
            }
            Text(
                "$finished / ${stops.size} visited • ${stops.count { it.status == "pending" }} remaining",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { isAddingSite = true }) { Text("+ Site") }
                OutlinedButton(onClick = { mapMode = !mapMode }) {
                    Text(if (mapMode) "Show list" else "Show map")
                }
                OutlinedButton(enabled = nextStop != null, onClick = {
                    if (nextStop != null) launchDirections(context, nextStop)
                }) { Text("Start / Next") }
            }
            Spacer(Modifier.height(8.dp))
            if (mapMode) {
                StopMap(
                    sites = stops,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onSiteTap = { selectedSiteId = it }
                )
                Text(
                    "Map © OpenFreeMap • © OpenMapTiles • © OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(6.dp))
                if (selectedSite == null) {
                    Text("Add a site to see its pin on the map.")
                } else {
                    SiteSummary(
                        site = selectedSite,
                        onDirections = { launchDirections(context, selectedSite) },
                        onComplete = { completeAndGo(selectedSite, "visited") },
                        onSkip = { completeAndGo(selectedSite, "skipped") }
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                if (stops.isEmpty()) {
                    Text("No sites yet. Tap + Site to add a location.")
                }
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(stops, key = { it.id }) { site ->
                        SiteRow(
                            site = site,
                            onDirections = { launchDirections(context, site) },
                            onComplete = { completeAndGo(site, "visited") },
                            onSkip = { completeAndGo(site, "skipped") },
                            onRestore = {
                                update(data.copy(sites = data.sites.map {
                                    if (it.id == site.id) it.copy(status = "pending") else it
                                }))
                            },
                            onEdit = { editingSite = site },
                            onDelete = {
                                update(data.copy(sites = data.sites.filterNot { it.id == site.id }))
                            },
                            onMoveUp = { update(RouteLogic.moved(data, site.id, -1)) },
                            onMoveDown = { update(RouteLogic.moved(data, site.id, 1)) }
                        )
                    }
                }
            }
            if (stops.isNotEmpty() && !mapMode) {
                TextButton(onClick = {
                    update(data.copy(sites = data.sites.map {
                        if (it.listId == data.activeListId) it.copy(status = "pending") else it
                    }))
                }) { Text("Reset visited/skipped for this list") }
            }
        }
    }

    if (showAddList) {
        TextEntryDialog(
            title = "New site list", label = "List name",
            onDismiss = { showAddList = false },
            onSave = { name ->
                val list = VisitList(name = name)
                update(data.copy(lists = data.lists + list, activeListId = list.id))
                selectedSiteId = null
                showAddList = false
            }
        )
    }

    if (showDeleteList) {
        AlertDialog(
            onDismissRequest = { showDeleteList = false },
            title = { Text("Delete list?") },
            text = { Text("This permanently deletes the list and every site in it.") },
            confirmButton = {
                TextButton(onClick = {
                    val id = data.activeListId
                    val remaining = data.lists.filterNot { it.id == id }
                    if (remaining.isNotEmpty()) {
                        update(data.copy(
                            lists = remaining,
                            sites = data.sites.filterNot { it.listId == id },
                            activeListId = remaining.first().id
                        ))
                    }
                    selectedSiteId = null
                    showDeleteList = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteList = false }) { Text("Cancel") }
            }
        )
    }

    if (isAddingSite || editingSite != null) {
        SiteEditor(
            initial = editingSite,
            onDismiss = { isAddingSite = false; editingSite = null },
            onSave = { site ->
                if (editingSite == null) {
                    val maxPosition = stops.maxOfOrNull { it.position } ?: -1
                    update(data.copy(sites = data.sites + site.copy(
                        listId = data.activeListId, position = maxPosition + 1
                    )))
                } else {
                    update(data.copy(sites = data.sites.map {
                        if (it.id == editingSite?.id) site else it
                    }))
                }
                isAddingSite = false
                editingSite = null
                selectedSiteId = site.id
            }
        )
    }
}

@Composable
private fun TextEntryDialog(
    title: String, label: String, onDismiss: () -> Unit, onSave: (String) -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        title = { Text(title) },
        onDismissRequest = onDismiss,
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SiteEditor(
    initial: Site?,
    onDismiss: () -> Unit,
    onSave: (Site) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var address by remember(initial) { mutableStateOf(initial?.address ?: "") }
    var lat by remember(initial) { mutableStateOf(initial?.latitude?.toString() ?: "") }
    var lon by remember(initial) { mutableStateOf(initial?.longitude?.toString() ?: "") }
    var start by remember(initial) { mutableStateOf(initial?.startTime ?: "") }
    var end by remember(initial) { mutableStateOf(initial?.endTime ?: "") }
    var notes by remember(initial) { mutableStateOf(initial?.notes ?: "") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) "Add site" else "Edit site") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Site name *") }, singleLine = true)
                OutlinedTextField(address, { newAddress ->
                    if (newAddress != address) { lat = ""; lon = "" }
                    address = newAddress
                }, label = { Text("Street address or place") })
                Text("Enter an address OR coordinates. Address lookup needs internet.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(lat, { lat = it }, Modifier.weight(1f),
                        label = { Text("Latitude") }, singleLine = true)
                    OutlinedTextField(lon, { lon = it }, Modifier.weight(1f),
                        label = { Text("Longitude") }, singleLine = true)
                }
                Text("Visit time (24-hour local time, e.g. 09:30):",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(start, { start = it }, Modifier.weight(1f),
                        label = { Text("Not before") }, singleLine = true)
                    OutlinedTextField(end, { end = it }, Modifier.weight(1f),
                        label = { Text("No later than") }, singleLine = true)
                }
                Text("Use the same time in both fields for an exact visit time.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") })
                if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                val normalizedStart = RouteLogic.normalizeTime(start)
                val normalizedEnd = RouteLogic.normalizeTime(end)
                if (normalizedStart == null || normalizedEnd == null) {
                    error = "Enter times as HH:mm, such as 08:30 or 14:00."
                } else if (normalizedStart.isNotBlank() && normalizedEnd.isNotBlank() &&
                    normalizedStart > normalizedEnd) {
                    error = "For now, time windows must not cross midnight."
                } else {
                    busy = true
                    scope.launch {
                        val manualLat = lat.trim().toDoubleOrNull()
                        val manualLon = lon.trim().toDoubleOrNull()
                        val coordinates: Pair<Double, Double>? = if (
                            manualLat != null && manualLon != null &&
                            manualLat in -90.0..90.0 && manualLon in -180.0..180.0
                        ) {
                            manualLat to manualLon
                        } else if (lat.isNotBlank() || lon.isNotBlank()) {
                            null
                        } else {
                            withContext(Dispatchers.IO) {
                                try {
                                    @Suppress("DEPRECATION")
                                    val found = Geocoder(context, Locale.getDefault())
                                        .getFromLocationName(address, 1)?.firstOrNull()
                                    found?.let { it.latitude to it.longitude }
                                } catch (_: Exception) {
                                    null
                                }
                            }
                        }
                        if (coordinates == null) {
                            error = "Location not found. Check the address or enter valid latitude/longitude."
                            busy = false
                        } else {
                            onSave(Site(
                                id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                                listId = initial?.listId ?: "",
                                name = name.trim(), address = address.trim(),
                                latitude = coordinates.first, longitude = coordinates.second,
                                startTime = normalizedStart, endTime = normalizedEnd,
                                notes = notes.trim(),
                                position = initial?.position ?: 0,
                                status = initial?.status ?: "pending"
                            ))
                        }
                    }
                }
            }) { Text(if (busy) "Finding..." else "Save") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun SiteSummary(
    site: Site,
    onDirections: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit
) {
    Text(site.name, style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold)
    Text(RouteLogic.timeDescription(site), style = MaterialTheme.typography.bodySmall)
    RouteLogic.timeWarning(site)?.let { warning ->
        Text(warning, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error)
    }
    if (site.notes.isNotBlank()) Text(site.notes, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(onClick = onDirections) { Text("Directions") }
        Button(enabled = site.status == "pending", onClick = onComplete) { Text("Done → Next") }
        OutlinedButton(enabled = site.status == "pending", onClick = onSkip) { Text("Skip") }
    }
}

@Composable
private fun SiteRow(
    site: Site,
    onDirections: () -> Unit,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onRestore: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(site.name + " • " + site.status.uppercase(),
                style = MaterialTheme.typography.titleSmall)
            if (site.address.isNotBlank()) Text(site.address,
                style = MaterialTheme.typography.bodySmall)
            Text(RouteLogic.timeDescription(site),
                style = MaterialTheme.typography.bodySmall)
            RouteLogic.timeWarning(site)?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (site.notes.isNotBlank()) Text(site.notes,
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onDirections) { Text("Directions") }
                Button(enabled = site.status == "pending", onClick = onComplete) {
                    Text("Done → Next")
                }
                OutlinedButton(enabled = site.status == "pending", onClick = onSkip) {
                    Text("Skip")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onMoveUp) { Text("↑") }
                TextButton(onClick = onMoveDown) { Text("↓") }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onRestore) { Text("Restore") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

/** MapLibre is for viewing pins. Actual driving guidance opens the chosen maps app. */
@Composable
private fun StopMap(
    sites: List<Site>,
    modifier: Modifier,
    onSiteTap: (String) -> Unit
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val latestSites by rememberUpdatedState(sites)
    val latestOnTap by rememberUpdatedState(onSiteTap)
    var cameraSet by remember { mutableStateOf(false) }

    fun updatePins(map: MapLibreMap) {
        if (map.style == null) return
        map.clear()
        latestSites.forEach { site ->
            map.addMarker(MarkerOptions()
                .position(LatLng(site.latitude, site.longitude))
                .title(site.name)
                .snippet(site.id))
        }
        if (!cameraSet && latestSites.isNotEmpty()) {
            val site = latestSites.first()
            map.moveCamera(CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder().target(LatLng(site.latitude, site.longitude))
                    .zoom(12.0).build()
            ))
            cameraSet = true
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
        // Map mode can be shown after the Activity has already resumed.
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
                    )) { updatePins(map) }
                    map.addOnMarkerClickListener { marker ->
                        marker.snippet?.let(latestOnTap)
                        true
                    }
                }
            }
        },
        update = { view ->
            view.getMapAsync { map -> updatePins(map) }
        },
        modifier = modifier
    )
}
