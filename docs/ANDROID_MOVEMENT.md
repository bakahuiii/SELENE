# SELENE Android Movement Tracking

## Scope

Android SELENE 0.3.0 records local, non-text timeline context. Its optional movement collector records confirmed continuous travel, sampled precise coordinates, approximate speed, and distance. It covers a walk that begins and ends between hourly WorkManager runs.

Data stays in the Android document-tree export folder selected by the user. SELENE does not upload it, inspect chat databases, read notification text, record calls or SMS, capture screenshots, read keyboard input, or access other applications' private databases.

## Enable Tracking

1. Install SELENE 0.3.0 or newer and open it once.
2. Select a SELENE export parent folder with Android's system folder picker.
3. Enable **Automatic local collection**.
4. Enable **Allow background continuous movement tracking and approximate speed**.
5. Grant precise location. On Android 10 and newer, choose **Allow all the time** for Location in the system app permission page.
6. On Android 13 and newer, allow notifications when requested so the foreground-service state is visible.

The service runs only while automatic collection, background location, export-folder access, precise location, and the platform-required background-location grant all remain enabled. Disabling automatic collection or background location stops the service and flushes a confirmed track.

## Collection Rules

SELENE uses GPS and network providers from a foreground service. It requests updates at most every 15 seconds and after at least 8 metres of provider-reported travel. Android or device power policies can still batch, delay, or suppress updates.

An accepted point must be at most five minutes old, at most two minutes in the future, and accurate to 80 metres or better. Jumps above 45 m/s are dropped. Two independent movement samples within 90 seconds are required before SELENE exports a track.

The start threshold is 0.8 m/s or 15 metres, raised to 75% of combined position accuracy when larger. During a confirmed track it is 0.65 m/s or 10 metres, raised to 50% of combined accuracy. A speed reading is standalone evidence only with position accuracy at or below 35 metres and, when Android supplies it, speed accuracy at or below 2.5 m/s.

This hysteresis rejects a few indoor steps, one-off GPS drift, poor network positioning, stale last-known locations, and implausible jumps. Candidate points are buffered and exported only after confirmation, retaining the start of a real walk without creating a false trip at home.

A confirmed track ends after about 90 seconds of stationary evidence or 150 seconds without movement evidence. A 30-second watchdog closes it at the last accepted point if Android stops supplying locations, so an unknown idle gap does not inflate duration.

## Export, Time, and Size

Confirmed points are precise `location` events with `values.moving: true`, a shared `trackId`, sequence, `speedMps`, `speedKmh`, distance from the previous point, accuracy, provider, and coordinates. Each completed track adds a `movement` summary with duration, distance, average and maximum speed, and sample count.

Point batches are written every 24 events or about two minutes, and again at track completion. The larger batch reduces repeated immutable-snapshot envelope metadata without dropping any points. Android omits producer-side `importedAt` because THEIA records actual import time; `capturedAt` and source point time remain intact.

JSON event times use the Android system timezone and ISO 8601 offset, for example `2026-08-06T22:54:39.123+08:00`. Snapshot directory names deliberately remain UTC (`SELENE-v1-...Z`) so naming and ordering are stable across timezone changes.

The hourly worker still collects app, calendar, device, and network snapshots. It can export a `last-known-fallback` location for place context only when it is no more than 30 minutes old; that event never starts a movement track.

See [EXPORT_LAYOUT.md](EXPORT_LAYOUT.md) for the complete envelope and [ANDROID_MOVEMENT.zh-CN.md](ANDROID_MOVEMENT.zh-CN.md) for the Chinese guide.

## Limits and Troubleshooting

Continuous background awareness costs more battery than hourly collection. Network connectivity is not needed for movement tracking. SELENE is not a navigation, emergency-tracking, or fitness-grade distance product: indoor GPS can be unavailable and vendors can throttle providers. After a device reboot, open SELENE once before expecting movement tracking; current Android versions restrict automatic location foreground-service starts at boot.

If no track appears, verify every enablement and permission step, confirm the export folder remains selected, and walk outdoors long enough to produce two accurate samples. A fallback-only export means Android did not provide enough qualifying live points. If a route is split, SELENE lost valid location updates past the no-evidence timeout; it intentionally does not invent a route across an unknown gap.

## Development Verification

```powershell
$env:JAVA_HOME = '<JDK_17_HOME>'
$env:ANDROID_HOME = '<ANDROID_SDK_HOME>'
gradle --no-daemon :app:lintDebug :app:assembleDebug
```

The debug APK is `app\build\outputs\apk\debug\app-debug.apk`.
