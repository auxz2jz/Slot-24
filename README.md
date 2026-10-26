# Site Route Planner v0.2 (Android)

**Native Android APK — in-app waypoint map and driving-road route.**

## One-map workflow
- Start on a single MapLibre map, with no Google Maps redirect.
- Add checkpoints by street address or coordinates. Pins remain visible together.
- Organize locations into several lists and switch lists without losing progress.
- See one road-following route connecting your current GPS position and your remaining checkpoints **in the order you choose**.
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
