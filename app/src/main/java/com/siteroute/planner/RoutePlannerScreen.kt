package com.siteroute.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
    var directToSite by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var addList by remember { mutableStateOf(false) }
    var deleteList by remember { mutableStateOf(false) }
    var addSite by remember { mutableStateOf(false) }
    var editSite by remember { mutableStateOf<Site?>(null) }
    var manageStops by remember { mutableStateOf(false) }
    var showTurns by remember { mutableStateOf(false) }
    var followMe by remember { mutableStateOf(false) }
    var headingUp by remember { mutableStateOf(true) }
    var voiceEnabled by remember { mutableStateOf(false) }
    var refocusCount by remember { mutableIntStateOf(0) }
    var rerouteCount by remember { mutableIntStateOf(0) }
    var offRouteFixes by remember { mutableIntStateOf(0) }
    var lastRerouteAt by remember { mutableLongStateOf(0L) }
    var rerouteStatus by remember { mutableStateOf<String?>(null) }
    val voicePrefs = remember {
        context.getSharedPreferences("route_voice_settings", Context.MODE_PRIVATE)
    }
    var voiceSettingsOpen by remember { mutableStateOf(false) }
    var voiceName by remember { mutableStateOf(voicePrefs.getString("voice", "") ?: "") }
    var voiceSpeed by remember { mutableFloatStateOf(voicePrefs.getFloat("rate", 1.0f)) }
    var voicePitch by remember { mutableFloatStateOf(voicePrefs.getFloat("pitch", 1.0f)) }
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
    val nextSite = if (directToSite) {
        allSites.firstOrNull { it.id == selectedId }
    } else {
        pending.firstOrNull()
    }
    val routeSites = NavigationPlan.stops(allSites, selectedId, directToSite)
    // Overview connects pending checkpoints in the saved manual order.
    // Direct mode uses exactly two points: current GPS and the selected site.

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
    var gpsCourse by remember { mutableStateOf<Float?>(null) }
    var gpsAccuracy by remember { mutableStateOf<Float?>(null) }
    var routeOrigin by remember { mutableStateOf<GeoPoint?>(null) }

    fun navigateTo(siteId: String) {
        offRouteFixes = 0
        rerouteStatus = null
        // Take the freshest GPS fix at the moment the user taps a destination.
        routeOrigin = livePosition ?: routeOrigin
        selectedId = siteId
        directToSite = true
        followMe = true
        headingUp = true
        refocusCount++
    }

    fun showFullRoute() {
        selectedId = null
        directToSite = false
        followMe = false
        refocusCount++
    }

    val isDirectNavigation by rememberUpdatedState(directToSite)

    DisposableEffect(hasLocationPermission) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = GeoPoint(location.latitude, location.longitude)
                livePosition = fix
                gpsAccuracy = if (location.hasAccuracy()) location.accuracy else null
                gpsCourse = if (location.hasSpeed() && location.speed >= 1.5f &&
                    location.hasBearing() &&
                    (!location.hasBearingAccuracy() ||
                        location.bearingAccuracyDegrees <= 45f))
                    location.bearing else null
                val last = routeOrigin
                if (last == null) {
                    routeOrigin = fix
                } else {
                    val distance = FloatArray(1)
                    Location.distanceBetween(
                        last.latitude, last.longitude,
                        fix.latitude, fix.longitude, distance
                    )
                    // The full-route overview refreshes occasionally as before.
                    // During active turn-by-turn navigation, only a confirmed
                    // deviation requests a new road route to avoid server flooding.
                    if (!isDirectNavigation && distance[0] >= 250f) routeOrigin = fix
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

    val compassHeading = rememberCompassHeading(
        enabled = directToSite && followMe, position = livePosition
    )
    val directionDegrees = gpsCourse ?: compassHeading

    var roadRoute by remember { mutableStateOf<RoadRoute?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var routing by remember { mutableStateOf(false) }
    val routeWaypoints = NavigationPlan.points(
        routeOrigin, allSites, selectedId, directToSite
    )

    // Two reliable off-road GPS fixes trigger rerouting immediately, instead of
    // waiting until we've moved 250 meters. Limit requests to the public demo API.
    LaunchedEffect(directToSite, selectedId, data.activeListId) {
        offRouteFixes = 0
        rerouteStatus = null
    }
    LaunchedEffect(livePosition, gpsAccuracy) {
        if (!directToSite || selectedId == null || routing || roadRoute == null) {
            offRouteFixes = 0
            return@LaunchedEffect
        }
        val off = RouteDeviation.offRoute(roadRoute, livePosition, gpsAccuracy)
        if (!off) {
            offRouteFixes = 0
            if (rerouteStatus == "Checking alternate roads…") rerouteStatus = null
            return@LaunchedEffect
        }
        offRouteFixes++
        val now = SystemClock.elapsedRealtime()
        if (offRouteFixes >= RouteDeviation.CONFIRM_FIXES &&
            now - lastRerouteAt >= RouteDeviation.RETRY_COOLDOWN_MS) {
            lastRerouteAt = now
            offRouteFixes = 0
            val fresh = livePosition ?: return@LaunchedEffect
            routeOrigin = fresh
            rerouteCount++
            rerouteStatus = "Recalculating from your current location…"
        } else if (offRouteFixes > 0) {
            rerouteStatus = "Checking alternate roads…"
        }
    }

    LaunchedEffect(data.activeListId, routeWaypoints, rerouteCount) {
        roadRoute = null
        routeError = null
        val points = routeWaypoints
        when {
            routeSites.isEmpty() -> routing = false
            points.size < 2 -> {
                routing = false
                routeError = if (directToSite) {
                    "Waiting for GPS. Enable Location to calculate a driving route from your position."
                } else {
                    "One checkpoint: enable GPS to route from your position. The pin remains on the map."
                }
            }
            points.size > 25 -> {
                routing = false
                routeError = "Too many checkpoints for one route (maximum 24 with GPS). Split the list."
            }
            else -> {
                routing = true
                try {
                    roadRoute = withContext(Dispatchers.IO) { RouteService.buildRoute(points) }
                    rerouteStatus = null
                } catch (e: Exception) {
                    routeError = "Road directions unavailable. Orange dashed lines show checkpoint order only, NOT drivable roads."
                    rerouteStatus = null
                } finally {
                    routing = false
                }
            }
        }
    }

    val speech = rememberNavigationSpeech()
    LaunchedEffect(speech.ready, speech.voices, voiceName, voiceSpeed, voicePitch) {
        if (speech.ready) {
            val voice = speech.voices.firstOrNull { it.name == voiceName }
            if (voice != null) speech.tts.setVoice(voice)
            else speech.tts.setLanguage(java.util.Locale.getDefault())
            speech.tts.setSpeechRate(voiceSpeed)
            speech.tts.setPitch(voicePitch)
        }
    }
    SpokenTurnGuidance(
        enabled = voiceEnabled && directToSite && followMe &&
            !RouteDeviation.offRoute(roadRoute, livePosition, gpsAccuracy),
        route = roadRoute,
        gps = livePosition,
        accuracyMeters = gpsAccuracy,
        speech = speech
    )

    fun finishStop(site: Site, status: String) {
        val updated = data.copy(sites = data.sites.map {
            if (it.id == site.id) it.copy(status = status) else it
        })
        update(updated)
        if (directToSite) {
            selectedId = NavigationPlan.nextPendingAfter(updated, site.id)?.id
            directToSite = selectedId != null
        } else {
            selectedId = null
        }
        refocusCount++
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Site Route Planner") },
            actions = {
                Box {
                    TextButton(onClick = { backupMenu = true }) { Text("Backup") }
                    DropdownMenu(
                        expanded = backupMenu,
                        onDismissRequest = { backupMenu = false }
                    ) {
                        DropdownMenuItem(text = { Text("Back up all routes to file") },
                            onClick = {
                                backupMenu = false
                                try {
                                    backupSnapshot = PlannerBackupCodec.encode(data)
                                    val date = LocalDateTime.now().format(
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm")
                                    )
                                    saveBackup.launch("site-routes-$date.json")
                                } catch (e: Exception) {
                                    backupMessage = e.message ?: "Could not prepare backup."
                                }
                            })
                        DropdownMenuItem(text = { Text("Restore routes from file") },
                            onClick = {
                                backupMenu = false
                                openBackup.launch(arrayOf(
                                    "application/json", "text/plain", "application/octet-stream"
                                ))
                            })
                    }
                }
            }
        )
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
                                showFullRoute()
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
                    Text("Stops (${allSites.size})")
                }
                OutlinedButton(onClick = { showFullRoute() }) {
                    Text("Full route")
                }
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
                focusedSiteId = if (directToSite) nextSite?.id else null,
                currentLocation = livePosition,
                roadRoute = roadRoute,
                routeWaypoints = routeWaypoints,
                listId = data.activeListId,
                followMe = followMe,
                headingUp = headingUp && directToSite,
                headingDegrees = directionDegrees,
                refocusCount = refocusCount,
                onSiteTap = { navigateTo(it) },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            if (directToSite) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        headingUp = !headingUp
                        followMe = true
                    }) { Text(if (headingUp) "Heading up ✓" else "North up") }
                    TextButton(onClick = { voiceEnabled = !voiceEnabled }) {
                        Text(if (voiceEnabled) "Voice ON" else "Voice OFF")
                    }
                    TextButton(onClick = { voiceSettingsOpen = true }) {
                        Text("Voices")
                    }
                    if (directionDegrees == null) {
                        Text("Finding heading…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(
                (if (directToSite) "To selected site" else "Full route") +
                    " • ${allSites.size} pins • Blue = road, orange dashes = sequence only",
                style = MaterialTheme.typography.labelSmall
            )
            if (directToSite && rerouteStatus != null) {
                Text(rerouteStatus.orEmpty(), style = MaterialTheme.typography.bodySmall)
            }
            if (routing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (roadRoute != null) {
                Text(
                    (if (directToSite) "Fastest estimated drive (no live traffic): " else "All pending stops: ") +
                        RouteService.miles(roadRoute!!.distanceMeters) + " • " +
                        RouteService.minutes(roadRoute!!.durationSeconds),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                routeError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.height(4.dp))
            // The list belongs BELOW the map, never in a horizontal strip above it.
            Text(
                "Sites in order • Tap a site for GPS directions • ↑ ↓ reorder",
                style = MaterialTheme.typography.labelMedium
            )
            if (allSites.isEmpty()) {
                Text("No sites in this list. Tap + Site to add one.",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 205.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    itemsIndexed(allSites, key = { _, site -> site.id }) { index, site ->
                        val active = directToSite && selectedId == site.id
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            if (active) {
                                Button(
                                    onClick = { navigateTo(site.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text("${index + 1}. ${site.name}", maxLines = 2)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { navigateTo(site.id) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text("${index + 1}. ${site.name}" +
                                        (if (site.status == "visited") " ✓" else
                                        if (site.status == "skipped") " (skipped)" else ""),
                                        maxLines = 2)
                                }
                            }
                            IconButton(
                                enabled = index > 0,
                                onClick = {
                                    update(RouteLogic.moved(data, site.id, -1))
                                    if (!directToSite) refocusCount++
                                }
                            ) { Text("↑") }
                            IconButton(
                                enabled = index < allSites.lastIndex,
                                onClick = {
                                    update(RouteLogic.moved(data, site.id, 1))
                                    if (!directToSite) refocusCount++
                                }
                            ) { Text("↓") }
                        }
                    }
                }
            }
            if (nextSite != null) {
                Text(
                    (if (directToSite) "Destination: " else "Next checkpoint: ") +
                        nextSite.name + " • " + RouteLogic.timeDescription(nextSite),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2
                )
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
                    ) { Text("Turns") }
                }
            }
            Spacer(Modifier.height(5.dp))
        }
    }

    if (voiceSettingsOpen) {
        NavigationVoiceSettings(
            speech = speech,
            selectedVoice = voiceName,
            rate = voiceSpeed,
            pitch = voicePitch,
            onVoice = { chosen ->
                voiceName = chosen
                voicePrefs.edit().putString("voice", chosen).apply()
            },
            onRate = { chosen ->
                voiceSpeed = chosen
                voicePrefs.edit().putFloat("rate", chosen).apply()
            },
            onPitch = { chosen ->
                voicePitch = chosen
                voicePrefs.edit().putFloat("pitch", chosen).apply()
            },
            onDismiss = { voiceSettingsOpen = false }
        )
    }

    if (backupMessage != null) {
        AlertDialog(
            onDismissRequest = { backupMessage = null },
            title = { Text("Backup / Restore") },
            text = { Text(backupMessage.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { backupMessage = null }) { Text("OK") }
            }
        )
    }
    restoreCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("Restore this backup?") },
            text = {
                Text("The file contains ${candidate.lists.size} routes and " +
                    "${candidate.sites.size} stops. Restoring will REPLACE all " +
                    "routes and progress currently saved on this device. " +
                    "Make a backup of your current data first if you want to keep it.")
            },
            confirmButton = {
                TextButton(onClick = {
                    update(candidate)
                    showFullRoute()
                    restoreCandidate = null
                    backupMessage = "Backup restored: ${candidate.lists.size} routes, " +
                        "${candidate.sites.size} stops."
                }) { Text("Replace and restore") }
            },
            dismissButton = {
                TextButton(onClick = { restoreCandidate = null }) { Text("Cancel") }
            }
        )
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
                                    navigateTo(site.id)
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
                showFullRoute()
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
                    showFullRoute()
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
                navigateTo(site.id)
                addSite = false
                editSite = null
            }
        )
    }
}
