# Sillage

Sillage is an Android companion app for [singletrack](https://singletrack.quintard.me), a self-hosted
tool to plan mountain-bike rides with friends. It records your ride without any network, lets you drop
key moments with one tap (even with the phone locked), and exports everything as standard GPX / KML.

*Sillage* is French for the wake a boat leaves behind — the trace you leave on the trail.

## Status

Early development. Planned for the first version:

- Record a GPS track fully offline, screen off, in a foreground service (nothing leaves the phone).
- Key moments in one tap, from the map or from the notification, without unlocking.
- Map and position over the lock screen while recording.
- Export as GPX 1.1 / KML with key moments as waypoints; import a GPX / KML / KMZ track to follow.
- With a singletrack server: open a ride from its QR code or link, pair an account, send your track to
  the ride, download offline maps and route offline with BRouter.

## Principles

- **Works without an account and without network.** singletrack is optional; recording never needs it.
- **Free software only.** No Google Play Services, ML Kit or Firebase: built for
  [F-Droid](https://f-droid.org). Location comes from Android's own `LocationManager`.
- **The server is yours.** The singletrack address is a setting, so Sillage works with any instance.

## Build

Requirements: JDK 17+ and the Android SDK (platform 36). Android Studio is optional.

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # unit tests
./gradlew installDebug         # install on a connected device
```

## Map data

The online map is [OpenTopoMap](https://opentopomap.org) (© OpenStreetMap contributors, SRTM; style
CC-BY-SA), displayed on demand only: its terms forbid bulk prefetching, so offline maps will come from
your singletrack server.

## License

[GNU AGPL v3](LICENSE) or later.
