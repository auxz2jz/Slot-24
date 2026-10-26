# Roadmap

## Implemented in first source version
- Create multiple named site lists; switch active list at any time
- Create/edit/delete location pins using address geocoding or entered coordinates
- MapLibre map with OpenFreeMap style and pin selection
- Manually reorder stops, mark visited, skip, restore, reset
- "Done → Next" immediately opens directions to the next pending stop in the active list
- Scheduling fields for earliest and latest local-time arrivals
- Local private JSON preferences persistence across app restarts
- Separate map and list views

## Next iterations
1. Verify Android build on a machine with Android SDK/Android Studio; add Gradle wrapper and CI.
2. Improve routing across lists: priority sites, pinned must-visit times, late-warning dashboard.
3. ETA calculation and route optimization that respects visit windows (requires routing service).
4. Recurring daily/weekly lists and calendar-date-specific visits.
5. Arrival detection and optional auto-advance with background location permission.
6. CSV/KML/GPX import/export, Google Maps sharing, backups.
7. Accessibility, persistent notifications and offline maps using approved tile packages.
8. More careful lifecycle handling and large-list optimization.

## Explicit limitations
- No built-in turn-by-turn GPS engine; it opens Google Maps directions as an external app.
- No ETA/routing optimization yet; visit order is manually chosen.
- No automatic arrival detection, background location tracking, or notifications yet.
- Time windows repeat in the phone's current local timezone and do not cross midnight.
- Address geocoding depends on an available Android geocoder and connectivity; coordinate entry is a fallback.
- Tiles are online; local site lists remain available without internet.
- This repository contains source files only. APK is not built/tested in this environment.
