# Site Route Planner (Android)

**Repository:** Slot-24 | **Status:** source-code MVP (not yet build-verified)

A native Android application to organize stops you need to visit, see their locations on a map, follow them one at a time, change lists during the day, and enter appointment/time windows for individual stops.

## What the first version does

- **Lists:** Create multiple named routes such as "Morning", "Afternoon", "Urgent".
- **Sites:** Add names, street addresses (geocoded on the phone), or exact latitude/longitude; add notes.
- **Map:** Show locations as tappable pins on a MapLibre/OpenFreeMap map.
- **Visit order:** Set your route sequence using up/down controls.
- **Directions:** Tap **Start / Next** for the first pending site. **Done → Next** marks the current site complete and opens the next pending destination in Google Maps. **Skip** skips a site and moves ahead. On return, switch lists and keep each list's status independently.
- **Time windows:** Specify earliest and/or latest allowed arrival, 24-hour format HH:mm. Both equal for a fixed target time. Sites past their latest time show a warning.
- **Persistence:** Lists and statuses are stored privately on the device using SharedPreferences; no login or remote database.

## Technology

- Kotlin + Jetpack Compose / Material 3
- MapLibre Native 13.7.1 for the pin map; OpenFreeMap Liberty style (no API key)
- Google Maps Directions URL intent for driving directions (no Google Maps Platform API key)
- Android Geocoder for address-to-coordinate lookup
- Local JSON persistence in SharedPreferences

## Build / run

1. Open this GitHub repository in Android Studio.
2. Install Android SDK 36 and JDK 17. In Android Studio, open the root folder and allow the Gradle sync.
3. Select the `app` configuration and run on an Android 8.0+ (API 26+) device or emulator with internet access.
4. Grant location access to Google Maps itself if prompted when launching driving directions. This app does not request location permission.
5. Add a list, add a site, enter address or coordinates, and navigate.

The repository also has a GitHub Actions workflow at `.github/workflows/android.yml` that attempts a debug APK build and unit tests on pushes to `main`. If the workflow succeeds, the APK is available as a short-lived Actions artifact; this has not yet been verified here.

**Important:** A Gradle wrapper (`gradlew`, wrapper JAR, and properties) and APK are not included yet. Android Studio can open the Gradle project; generate a wrapper with an installed Gradle if required. The source has not been compiled or device-tested here.

## Navigation behavior

Manual stop order is authoritative in the MVP. This app does **not** yet calculate driving ETAs, optimize stop sequence, automatically replan around deadlines, or detect that you have arrived. The time labels/warnings help you decide when to change lists. You manually tap **Done → Next** or **Skip**. See [Roadmap](docs/ROADMAP.md).

## Map attribution / third-party services

- MapLibre SDK: https://maplibre.org
- Map styles/tiles: https://openfreemap.org/ (follow its attribution/usage terms)
- Map data © OpenStreetMap contributors
- Driving directions open externally: https://developers.google.com/maps/documentation/urls/get-started
- Geocoding uses the device's Android Geocoder implementation, whose availability and accuracy vary.

## Privacy

Lists, site locations, and notes are stored on this device by default. Viewing a map sends tile requests to the tile provider; geocoding may use a device geocoder service; requesting driving directions sends destination coordinates to the external maps handler. Do not enter sensitive addresses into a publicly shared demo/screenshots.

This is a development prototype, not a safety-critical navigation system.
