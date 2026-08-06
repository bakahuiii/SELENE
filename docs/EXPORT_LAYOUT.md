# SELENE Export Layout

## Contract

SELENE exports immutable snapshots. A snapshot is a directory whose name is
stable for its creation time and whose contents are never modified afterwards.

```text
SELENE-v1-<UTC timestamp>/context-events.json
```

`<UTC timestamp>` is formatted as `yyyyMMddTHHmmssSSSZ`, for example
`20260806T185439123Z`. `v1` identifies the snapshot-layout version, not the
application version. Directory timestamps always stay in UTC for stable naming
and sorting. The human-facing ISO 8601 fields inside JSON use the device's
current system timezone and offset, for example `2026-08-06T22:54:39.123+08:00`.

The content file conforms to `selene-context-events/v1`:

```json
{
  "schema": "selene-context-events/v1",
  "device": { "platform": "android" },
  "generatedAt": "2026-08-06T18:54:39.123Z",
  "producer": {
    "name": "SELENE",
    "version": "0.3.0",
    "layout": "immutable-snapshot-v1"
  },
  "events": []
}
```

`producer` is required metadata. THEIA uses it to reject non-SELENE JSON before
it enters the timeline import path.

## Write Semantics

1. The collector creates one new snapshot directory.
2. It creates `context-events.json` inside that directory.
3. It writes only the events from that collection run.
4. It never reads a prior export and never edits or removes a prior export.

An interrupted run may leave a partial snapshot. Importers should reject JSON
that cannot be parsed, while retaining the directory for inspection. A later
successful run creates another independent snapshot rather than repairing the
old one.

JSON is compact UTF-8 without indentation. Android omits `importedAt` because
THEIA records the actual import time and otherwise derives it from the snapshot
generation time. This removes a duplicated producer-side timestamp without
changing event timing information.

## Import Semantics

Import the parent directory. THEIA scans JSON files recursively, retains their
relative path as provenance, and deduplicates events by stable event ID. This
allows an hourly run to overlap an earlier time window without turning the
export directory into a mutable database.

SELENE never reads, migrates, alters, or deletes files outside its own immutable
snapshot directories.

The device.platform value is android for the Android collector and windows for
the Windows collector. Both emit the same event contract and share the
selene-context-events/v1 schema.

## Android Movement Events

When Android automatic collection and background location are enabled, SELENE
uses a foreground location service for movement rather than relying on an
hourly WorkManager run. A movement is confirmed only after two nearby location
samples independently show sustained travel. Short indoor steps, a lone noisy
fix, stale last-known locations, poor-accuracy fixes, and implausible jumps are
not exported as movement.

Confirmed tracks emit precise `location` events with `values.moving: true`, a
stable `trackId`, sampled speed in metres per second and kilometres per hour,
distance since the preceding point, accuracy, and provider. When a track ends,
SELENE emits one `movement` summary with duration, distance, average and
maximum speed, and sample count. A short pre-confirmation buffer is exported
with a confirmed track so the start of a walk is retained without recording a
couple of steps at home as a separate trip.

```json
{
  "id": "SELENE-movement-point-<trackId>-<sequence>",
  "kind": "location",
  "startAt": "2026-08-06T22:54:39.123+08:00",
  "privacy": "precise",
  "location": {
    "latitude": 31.230416,
    "longitude": 121.473701,
    "accuracyMeters": 12.4
  },
  "locationConsent": {
    "exactLocation": true,
    "captureMode": "foreground",
    "grantedAt": "2026-08-01T09:00:00.000+08:00"
  },
  "values": {
    "trackId": "<trackId>",
    "sequence": 0,
    "moving": true,
    "speedMps": 1.21,
    "speedKmh": 4.4,
    "distanceFromPreviousMeters": 15.3,
    "accuracyMeters": 12.4,
    "provider": "gps",
    "sampleMode": "foreground-service"
  }
}
```

The final event for a confirmed track has `kind: "movement"` and includes
`durationSeconds`, `distanceMeters`, `averageSpeedMps`, `averageSpeedKmh`,
`maxSpeedMps`, `maxSpeedKmh`, `sampleCount`, and the common `trackId`.

The hourly worker may additionally export a recent `last-known-fallback`
location for general place context. It is explicitly not a movement record and
is discarded when more than 30 minutes old.

See [ANDROID_MOVEMENT.md](ANDROID_MOVEMENT.md) for the complete Android guide,
or [EXPORT_LAYOUT.zh-CN.md](EXPORT_LAYOUT.zh-CN.md) for this document in
Chinese.
