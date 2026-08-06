# SELENE Export Layout

## Contract

SELENE exports immutable snapshots. A snapshot is a directory whose name is
stable for its creation time and whose contents are never modified afterwards.

```text
SELENE-v1-<UTC timestamp>/context-events.json
```

`<UTC timestamp>` is formatted as `yyyyMMddTHHmmssSSSZ`, for example
`20260806T185439123Z`. `v1` identifies the snapshot-layout version, not the
Android application version.

The content file conforms to `selene-context-events/v1`:

```json
{
  "schema": "selene-context-events/v1",
  "device": { "platform": "android" },
  "generatedAt": "2026-08-06T18:54:39.123Z",
  "producer": {
    "name": "SELENE",
    "version": "0.2.0",
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
