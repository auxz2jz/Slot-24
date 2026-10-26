# Site Route Planner v0.8 (Android)

**Native Android APK — in-app waypoint map and driving-road route.**

## Back up and restore (Android file picker)
- Tap **Backup** (top-right) then **Back up all routes to file**.
- Choose a folder or document provider and save the suggested `site-routes-YYYY-MM-DD_HH-mm.json` file.
- The JSON file includes every list and checkpoint, GPS coordinates, addresses, visit windows, notes, manual stop order, visited/skipped status, and the active list.
- Tap **Backup → Restore routes from file** and select a prior `.json` backup. The app checks the format and content, shows route/stop counts, and asks permission before **replacing** local data.
- Restoration is replace-all (not merge). Back up your current data before restoring an older file.
- Backups are manually saved; no automatic sync, cloud account, or scheduled cloud backup exists. The chosen document provider may upload the file depending on where you save it.
- Existing Android app data can be lost when uninstalling or replacing apps signed with a different debug key. Make a backup using an already installed compatible version before uninstalling. **Version 0.2 does not contain an export feature**, so a failed APK update may require manually re-entering stops into 0.3.

## Auto-reroute and voice choices (v0.8)
- While following a single stop, the app checks fresh GPS positions against the **full blue road polyline**, measuring distance to each road segment. If **two consecutive GPS fixes** (normally around 3–6 seconds) with accuracy of 35 m or better lie over **45 m away from the route**, the app recalculates a new road route from your current position to the **same stop**. GPS error can delay or falsely trigger rerouting; the app never assumes a safe or legal maneuver.
- Automatic retry requests are spaced at least **12 seconds** apart to limit use of the public OSRM demonstration server. Actual recalculation time depends on the internet connection and public server availability; the app may be slower, offline, or unable to provide a replacement route.
- The screen indicates **Recalculating from your current location**. The old blue road line is removed while fetching a replacement; an orange dashed line is only a checkpoint preview, never driving directions.
- When navigating to a site, choose **Voices** to browse available voices from the Android device's current TTS engine, preview a sample, and adjust speed (0.7–1.3x) and pitch (0.8–1.2x). These choices are saved on this device. Voice ON still must be enabled separately. Online voices may need data. Higher-quality or neural voices must be installed by the user through their device's preferred TTS engine; this app does not license third-party cloud voices.
- Experimental spoken turns are suppressed if GPS suggests you are off route or has poor accuracy. This is a prototype, **not** full commercial real-time navigation. Do not rely on it as the sole source of driving instructions.

## Tap a house or street address and heading-up navigation (v0.7)
- In **+ Site → Use Current GPS / Nearby Places**, zoom in on the map and tap a building or road-adjacent point. The app reverse geocodes that tapped position using Android Geocoder and searches nearby OpenStreetMap records tagged with house number/street. Choose a suggested address, business, **Exact tapped map point**, or **Exact GPS position** and then save. A house street address is shown only where the map data or geocoder has it. No house number is invented from coordinates.
- Nearby business/house results are offered on demand and may not be complete. The source supplies mapped feature centers, which may differ from entrances or driveways. Verify the selected point before driving.
- While navigating to a site, the map now **follows your GPS and rotates with your driving heading**; GPS travel bearing is preferred while moving and the phone's true-north compass is used when stationary. Tap **Heading up / North up** to switch orientation.
- **Voice OFF / Voice ON** enables experimental foreground text-to-speech turn alerts from OSRM maneuvers. Prompts are gated by GPS accuracy and proximity to the planned route. It is NOT full car-grade turn-by-turn navigation, cannot account for traffic/off-route rerouting immediately, and does not run in the background. Keep voice off if directions are unreliable and obey actual road signs.
- The map and directions need internet. Public OSRM and Overpass services are for personal limited experiments, not a production navigation service.
- Before uninstalling an older APK, use **Backup → Back up all routes to file**. Changing CI debug signing keys still prevents some in-place upgrades; a permanent private signing key remains future work.

## Add a stop from GPS / nearby business in 0.6
- Tap **+ Site**, then **Use Current GPS / Nearby Places** in the stop editor. Approve Android location permission and enable phone Location if prompted.
- The app obtains a reasonably fresh current location and opens a nearby-selection **map**. Your exact GPS point is always selectable.
- Nearby named businesses such as supermarkets, restaurants and other OpenStreetMap places may appear with distances. Street-address suggestions come from the Android Geocoder. Tap a suggestion in the list, or a numbered pin then **Use selected**.
- A chosen business uses the mapped place's coordinates, which may differ from the exact phone location. The site name/address and coordinates are filled into the normal editor. Review and tap **Save** to create your stop.
- OpenStreetMap community data is incomplete, so a nearby Walmart or street address cannot be guaranteed. Exact GPS coordinates work even if both external services fail.
- Nearby lookup happens **only when the user explicitly taps the GPS button**; searching sends the phone coordinates to a public Overpass map-data server (https://overpass-api.de) and loads OpenFreeMap tiles. The service may be slow, rate limited, or unavailable; do not rely on this public service for production-scale use.
- Stops, routes and backups remain stored locally on your phone (unless you explicitly export a backup to a cloud document provider).

## One-screen driving layout in 0.5
- Select any saved list from the dropdown (e.g. **My Stops** or **Test**); the map shows every stop as a numbered pin.
- The **vertical, scrollable sites list is below the map**. Tap any site, including a previously visited site, to show the estimated fastest road route from the phone's current GPS position **to only that site**. The map frames your position and the destination, plus the road geometry when it arrives. No other app is opened.
- Press **Full route** to connect all pending checkpoints in saved order, not just the last tapped destination. This mode redraws the driving-road route when a stop is completed/skipped or the order changes.
- Beside each site in the vertical list, tap **↑** or **↓** to move its saved visit order. The numbers on the map and the road route will follow the new order. You can also reorder in Stops.
- **Done → Next** or **Skip → Next** updates progress and advances to the next unfinished stop if navigating to a site, or redraws the full route if showing the whole itinerary.
- The routing server finds a road route using travel-time estimates **without current traffic data**. "Fastest" means estimated driving time using map data, **not guaranteed fastest with live traffic**. Your GPS and checkpoint coordinates are shared with the OSRM demo server for each request.
- Selecting a single destination needs a GPS location fix and internet access. In that mode we do not substitute a fake straight-line route when GPS or routing is unavailable.

## Map visibility fix in 0.4
- Checkpoint pins use **persistent GeoJSON layers**, not legacy markers that are cleared and recreated on every state change.
- Selecting a list draws all of that list's stored stops on the map, regardless of completion status. The map camera fits ALL selected-list pins after layout. Pin numbers match saved ordering.
- A **blue line** follows calculated driving roads (OSRM) through all pending checkpoints in the selected order.
- If OSRM fails or is still loading, an **orange dashed preview** joins checkpoints to show sequence ONLY; it is **not** a drivable route and must not be used for driving directions.
- GPS is optional for visualizing multiple saved stops. GPS is needed to route from your current position to a single stop.
- Tap the numbered map locations to choose which pending stop goes next.
- This fixes map data drawing and positioning but has **not been validated on a physical phone**.

## Installing updates
Android apps can update in place only if the package name and signing certificate match. GitHub Actions' default debug keystore may vary between builds, so **debug APK updates may require uninstalling**. That is a build signing issue, not how final production updates should work. Before uninstalling 0.3, use **Backup → Back up all routes to file**, then after installing 0.4 use **Backup → Restore routes from file**. Never uninstall without a backup if stops are important. A private, persistent release signing key is required for reliable future in-place updates; do not commit a private signing key to a public repository.

## One-map workflow
- Start on a single MapLibre map, with no Google Maps redirect.
- Add checkpoints by street address or coordinates. Pins remain visible together.
- Organize locations into several lists and switch lists without losing progress.
- See **all** stops from the selected list on one map, including visited and skipped stops, with numbered colored pins in the saved order.
- Use the labeled stop strip above the map for order and selection. **Fit route** now includes all locations in the selected list.
- The blue road route connects your position and the remaining pending checkpoints in saved order, automatically updating after completing or skipping each stop.
- Selecting a different pending checkpoint starts from that checkpoint and then continues through the rest in the saved order, wrapping to earlier pending checkpoints if needed.
- Tap any pending checkpoint on the map to make it your next destination (followed by the other remaining checkpoints).
- **Done → Next** marks it visited and redraws the route to the next pending stop. **Skip → Next** marks it skipped and moves on.
- **Stops** opens a management overlay where you can move, edit, restore, delete and reset stops. The main view remains the map.
- Show earliest/latest scheduled arrival windows, notes, and missed-time warnings.
- **Fit route** zooms out to show the pending sites. **Follow GPS** follows your current location.
- **Directions** shows textual road directions for the first leg, on the same screen.

## How GPS and routing work
- The app requests only foreground fine/coarse location permission and shows a location marker while open.
- Android location updates are used while the app screen is active. A route is recalculated after a roughly 250-meter change in routing origin, when changing next checkpoint, or when editing the list.
- Routes come from the public OSRM **demo** routing service, which is **only appropriate for experimentation or limited personal testing**, subject to availability and usage restrictions. It sends waypoint and current-location coordinates to the routing server. Do not use for heavy usage, production navigation or safety-critical driving. Obtain a dedicated routing backend before broader deployment.
- Estimated route times do not include traffic or time spent at checkpoints.
- With location permission off or no GPS fix, road routing starts at the first pending checkpoint when multiple checkpoints exist. The app will never draw a fake straight line and call it a driving route.
- This version does **not** provide spoken turn-by-turn directions, automatic arrival detection, background navigation, or offline road routing.
- Map tiles require internet (OpenFreeMap). Saved lists and checkpoint progress remain on device.

## Build
GitHub Actions workflow `.github/workflows/android.yml` runs Android unit tests, builds the debug APK and publishes a downloadable `site-route-planner-debug-apk` artifact after a successful run.

For local build: install Android Studio, Android SDK 36, JDK 17 and Gradle 8.13 or use the GitHub workflow. App supports Android 8+.

## Tech and attribution
- Kotlin, Jetpack Compose, Android LocationManager
- MapLibre 13.7.1 with online OpenFreeMap Liberty map style (OpenStreetMap contributors)
- Road routing and geometry: Project OSRM demo server (OpenStreetMap contributors)
- Android Geocoder for address lookup
- JSON persisted locally using SharedPreferences; no user account, no app analytics.

Road paths may be wrong or outdated. Always obey road signs and traffic law. Do not interact with the app while driving.
