package com.siteroute.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.withContext

/**
 * A single native map-first UI: no external Google Maps activity or map switching.
 * Tap a checkpoint on the map, mark it visited/skipped, or switch a list at any time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen() {
    val context = LocalContext.current
    val store = remember { SiteStore(context.applicationContext) }
    var data by remember { mutableStateOf(store.load()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var listMenu by remember { mutableStateOf(false) }
    var addList by remember { mutableStateOf(false) }
    var deleteList by remember { mutableStateOf(false) }
    var addSite by remember { mutableStateOf(false) }
    var editSite by remember { mutableStateOf<Site?>(null) }
    var manageStops by remember { mutableStateOf(false) }
    var showTurns by remember { mutableStateOf(false) }
    var followMe by remember { mutableStateOf(false) }
    var refocusCount by remember { mutableIntStateOf(0) }
    var backupMenu by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupSnapshot by remember { mutableStateOf<String?>(null) }
    var restoreCandidate by remember { mutableStateOf<PlannerData?>(null) }
    val scope = rememberCoroutineScope()

    // Android's document picker allows local or cloud backups without storage permissions.
    val saveBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(PlannerBackupCodec.MIME)
    ) { uri ->
        val snapshot = backupSnapshot
        backupSnapshot = null
        if (uri != null && snapshot != null) {
            scope.launch {
                backupMessage = try {
                    withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openOutputStream(uri, "wt")
                            ?: error("Could not open the selected destination.")
                        stream.use { it.write(snapshot.toByteArray(Charsets.UTF_8)) }
                    }
                    "Backup saved. Keep the JSON file somewhere safe."
                } catch (e: Exception) {
                    "Backup failed: " + (e.message ?: "Could not write the file.")
                }
            }
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not open the selected backup.")
                        input.use { stream ->
                            val output = ByteArrayOutputStream()
                            val block = ByteArray(8192)
                            while (true) {
                                val count = stream.read(block)
                                if (count < 0) break
                                require(output.size() + count <= PlannerBackupCodec.MAX_BYTES) {
                                    "Backup is larger than 8 MB."
                                }
                                output.write(block, 0, count)
                            }
                            output.toString("UTF-8")
                        }
                    }
                    restoreCandidate = PlannerBackupCodec.decode(json)
                } catch (e: Exception) {
                    backupMessage = "Restore failed: " +
                        (e.message ?: "The selected file is not a valid backup.")
                }
            }
        }
    }

    fun update(newData: PlannerData) {
        data = newData
        store.save(newData)
    }

    val allSites = RouteLogic.orderedSites(data)
    val pending = allSites.filter { it.status == "pending" }
    val nextSite = pending.firstOrNull { it.id == selectedId } ?: pending.firstOrNull()
    val routeSites = if (nextSite == null) emptyList() else {
        // Preserve the list's manual order, starting at the chosen next checkpoint.
        val start = pending.indexOfFirst { it.id == nextSite.id }
        pending.drop(start) + pending.take(start)
    }
    val currentList = data.lists.firstOrNull { it.id == data.activeListId }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions.values.any { it }
    }
    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    var livePosition by remember { mutableStateOf<GeoPoint?>(null) }
    var routeOrigin by remember { mutableStateOf<GeoPoint?>(null) }

    DisposableEffect(hasLocationPermission) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = GeoPoint(location.latitude, location.longitude)
                livePosition = fix
                val last = routeOrigin
                if (last == null) {
                    routeOrigin = fix
                } else {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        last.latitude, last.longitude,
                        fix.latitude, fix.longitude, distance
                    )
                    // Re-route only after substantial movement; avoid polling the demo service.
                    if (distance[0] >= 250f) routeOrigin = fix
                }
            }
        }
        if (hasLocationPermission) {
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            try {
                val providers = if (fine) {
                    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                } else listOf(LocationManager.NETWORK_PROVIDER)
                providers.forEach { provider ->
                    if (manager.isProviderEnabled(provider)) {
                        manager.getLastKnownLocation(provider)?.let(listener::onLocationChanged)
                        manager.requestLocationUpdates(provider, 3000L, 5f, listener)
                    }
                }
            } catch (_: SecurityException) {
                hasLocationPermission = false
            } catch (_: IllegalArgumentException) {
                // Provider might be removed or disabled while opening app.
            }
        }
        onDispose { manager.removeUpdates(listener) }
    }

    var roadRoute by remember { mutableStateOf<RoadRoute?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var routing by remember { mutableStateOf(false) }
    LaunchedEffect(data.activeListId, routeSites, routeOrigin) {
        roadRoute = null
        routeError = null
        val points = (if (routeOrigin == null) emptyList()
            else listOf(routeOrigin!!)) +
            routeSites.map { GeoPoint(it.latitude, it.longitude) }
        when {
            routeSites.isEmpty() -> routing = false
            points.size < 2 -> {
                routing = false
                routeError = "Enable GPS to draw a road route to this checkpoint."
            }
            points.size > 25 -> {
                routing = false
                routeError = "Too many checkpoints for one route (maximum 24 with GPS). Split the list."
            }
            else -> {
                routing = true
                try {
                    roadRoute = withContext(Dispatchers.IO) { RouteService.buildRoute(points) }
                } catch (e: Exception) {
                    routeError = "Could not load the road route. Check the internet and try again."
                } finally {
                    routing = false
                }
            }
        }
    }

    fun finishStop(site: Site, status: String) {
        val updated = data.copy(sites = data.sites.map {
            if (it.id == site.id) it.copy(status = status) else it
        })
        update(updated)
        selectedId = if (site.id == nextSite?.id) {
            routeSites.drop(1).firstOrNull { it.id != site.id }?.id
        } else {
            nextSite?.id ?: RouteLogic.nextPending(updated)?.id
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Site Route Planner") })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { listMenu = true }) {
                        Text((currentList?.name ?: "Select route") + " ▾")
                    }
                    DropdownMenu(
                        expanded = listMenu,
                        onDismissRequest = { listMenu = false }
                    ) {
                        data.lists.forEach { list ->
                            DropdownMenuItem(text = { Text(list.name) }, onClick = {
                                update(data.copy(activeListId = list.id))
                                selectedId = null
                                listMenu = false
                                refocusCount++
                            })
                        }
                    }
                }
                TextButton(onClick = { addList = true }) { Text("+ List") }
                TextButton(
                    enabled = data.lists.size > 1,
                    onClick = { deleteList = true }
                ) { Text("Delete list") }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { addSite = true }) { Text("+ Site") }
                OutlinedButton(onClick = { manageStops = true }) {
                    Text("Stops (${pending.size})")
                }
                OutlinedButton(onClick = {
                    followMe = false
                    refocusCount++
                }) { Text("Fit route") }
                if (hasLocationPermission) {
                    TextButton(onClick = { followMe = !followMe }) {
                        Text(if (followMe) "Following" else "Follow GPS")
                    }
                } else {
                    TextButton(onClick = {
                        permissionLauncher.launch(arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    }) { Text("Enable GPS") }
                }
            }

            CheckpointMap(
                sites = allSites,
                selectedSiteId = nextSite?.id,
                currentLocation = livePosition,
                roadRoute = roadRoute,
                listId = data.activeListId,
                followMe = followMe,
                refocusCount = refocusCount,
                onSiteTap = { selectedId = it },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Text(
                "Map: OpenFreeMap / OpenStreetMap • Road routing: OSRM",
                style = MaterialTheme.typography.labelSmall
            )
            if (routing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Drawing road route…", style = MaterialTheme.typography.bodySmall)
            } else if (roadRoute != null) {
                val route = roadRoute!!
                Text(
                    "Road route: ${RouteService.miles(route.distanceMeters)} • " +
                        RouteService.minutes(route.durationSeconds) +
                        " estimated, excluding time at stops",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val errorText = routeError
                if (errorText != null) {
                    Text(errorText, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            if (nextSite == null) {
                Text(
                    if (allSites.isEmpty()) "Tap + Site to add your first checkpoint."
                    else "No pending checkpoints. Use Stops to restore or reset a visit.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Text("NEXT CHECKPOINT: ${nextSite.name}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(RouteLogic.timeDescription(nextSite),
                            style = MaterialTheme.typography.bodySmall)
                        RouteLogic.timeWarning(nextSite)?.let {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (nextSite.notes.isNotBlank()) {
                            Text(nextSite.notes, style = MaterialTheme.typography.bodySmall)
                        }
                        if (routeOrigin != null && roadRoute?.legs?.isNotEmpty() == true) {
                            val leg = roadRoute!!.legs.first()
                            Text(
                                "From your position: ${RouteService.miles(leg.distanceMeters)} • " +
                                    RouteService.minutes(leg.durationSeconds),
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else if (routeOrigin == null) {
                            Text("Waiting for a GPS position; remaining checkpoints stay on the map.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { finishStop(nextSite, "visited") }) {
                                Text("Done → Next")
                            }
                            OutlinedButton(onClick = { finishStop(nextSite, "skipped") }) {
                                Text("Skip → Next")
                            }
                            TextButton(
                                enabled = roadRoute?.legs?.firstOrNull()?.instructions?.isNotEmpty() == true,
                                onClick = { showTurns = true }
                            ) { Text("Directions") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }

    if (showTurns) {
        AlertDialog(
            onDismissRequest = { showTurns = false },
            title = { Text("Driving directions") },
            text = {
                val instructions = roadRoute?.legs?.firstOrNull()?.instructions.orEmpty()
                LazyColumn(Modifier.heightIn(max = 410.dp)) {
                    items(instructions) { step -> Text("• $step", Modifier.padding(5.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTurns = false }) { Text("Close") }
            }
        )
    }

    if (manageStops) {
        AlertDialog(
            onDismissRequest = { manageStops = false },
            title = { Text("Checkpoints • ${currentList?.name}") },
            text = {
                if (allSites.isEmpty()) {
                    Text("No saved checkpoints.")
                } else {
                    LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 470.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allSites, key = { it.id }) { site ->
                            SiteRow(
                                site = site,
                                onDirections = {
                                    selectedId = site.id
                                    manageStops = false
                                },
                                onComplete = { finishStop(site, "visited") },
                                onSkip = { finishStop(site, "skipped") },
                                onRestore = {
                                    update(data.copy(sites = data.sites.map {
                                        if (it.id == site.id) it.copy(status = "pending") else it
                                    }))
                                },
                                onEdit = {
                                    editSite = site
                                    manageStops = false
                                },
                                onDelete = {
                                    update(data.copy(sites = data.sites.filterNot { it.id == site.id }))
                                },
                                onMoveUp = { update(RouteLogic.moved(data, site.id, -1)) },
                                onMoveDown = { update(RouteLogic.moved(data, site.id, 1)) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { manageStops = false }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = {
                    update(data.copy(sites = data.sites.map {
                        if (it.listId == data.activeListId) it.copy(status = "pending") else it
                    }))
                    selectedId = null
                }) { Text("Reset all") }
            }
        )
    }

    if (addList) {
        TextEntryDialog(
            title = "New site list", label = "List name",
            onDismiss = { addList = false },
            onSave = { name ->
                val list = VisitList(name = name)
                update(data.copy(lists = data.lists + list, activeListId = list.id))
                selectedId = null
                addList = false
            }
        )
    }

    if (deleteList) {
        AlertDialog(
            onDismissRequest = { deleteList = false },
            title = { Text("Delete this list?") },
            text = { Text("All checkpoints in this list will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    val rest = data.lists.filterNot { it.id == data.activeListId }
                    if (rest.isNotEmpty()) {
                        update(data.copy(
                            lists = rest,
                            sites = data.sites.filterNot { it.listId == data.activeListId },
                            activeListId = rest.first().id
                        ))
                    }
                    selectedId = null
                    deleteList = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteList = false }) { Text("Cancel") }
            }
        )
    }

    if (addSite || editSite != null) {
        SiteEditor(
            initial = editSite,
            onDismiss = { addSite = false; editSite = null },
            onSave = { site ->
                val previous = editSite
                if (previous == null) {
                    val position = allSites.maxOfOrNull { it.position } ?: -1
                    update(data.copy(sites = data.sites + site.copy(
                        listId = data.activeListId, position = position + 1
                    )))
                } else {
                    update(data.copy(sites = data.sites.map {
                        if (it.id == previous.id) site else it
                    }))
                }
                selectedId = site.id
                addSite = false
                editSite = null
            }
        )
    }
}
