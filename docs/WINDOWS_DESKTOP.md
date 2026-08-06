# SELENE Windows Desktop

SELENE Windows is a small native WPF collector that runs in the current Windows
user session. It is intentionally a tray application rather than a service:
this keeps installation simple, avoids administrator privileges, and makes the
collection boundary visible and controllable to the user.

## Requirements

- Windows 10 version 22H2 or Windows 11, x64.
- The published single-file package does not require a separate .NET runtime.
- Development builds require .NET SDK 9.0.
- No administrator permission is requested.

## First Run

1. Extract the SELENE-0.3.0-windows-x64.zip archive to a normal user folder.
2. Run SELENE.Windows.exe.
3. Choose a parent export folder. This can be the same folder used by Android
   SELENE, although each platform writes its own immutable snapshot directories.
4. Leave automatic collection enabled and choose 5, 15, 30, or 60 minutes.
5. Enable Windows startup only when running the published executable. Debug
   runs intentionally do not write a startup entry.

Closing the window hides SELENE in the system tray. The tray menu can show the
window, trigger an immediate collection, or exit the process. A snapshot is
written only while the application is running; the startup option is the
supported way to keep it available after login.

## Collected Signals

Each interval produces a new SELENE-v1-UTC-timestamp directory containing one
context-events.json file. The Windows collector currently records:

- foreground process name and start/end time for sessions observed by SELENE;
- aggregate foreground seconds and distinct active application count;
- current idle duration from GetLastInputInfo;
- battery percentage when Windows reports it, charging state and AC state;
- whether a network is available and its coarse transport (wifi, ethernet, vpn,
  other, or none).

Event timestamps use the Windows system timezone and ISO 8601 offset, such as
`2026-08-06T22:54:39.123+08:00`. Snapshot directory names remain UTC for stable
sorting. The JSON is compact UTF-8; SELENE reports the written byte count after
each capture.

The process name is the executable name such as chrome or devenv; SELENE does
not read the window title, document name, URL, text, clipboard, or process
arguments. Sessions shorter than five seconds are not emitted as individual
activity events, although they remain part of the bounded aggregate when
observed.

Windows SELENE does not currently read calendar databases, precise location,
notification contents, or application-specific usage histories. Those sources
remain platform-specific and are intentionally not approximated with
unreliable scraping.

For Android background movement tracking, including its separate permission
and foreground-service boundary, see [ANDROID_MOVEMENT.md](ANDROID_MOVEMENT.md).

## Data and Privacy

Settings are stored in:

~~~text
%LOCALAPPDATA%\SELENE\desktop-settings.json
~~~

Diagnostics contain event names, timestamps, and error messages only:

~~~text
%LOCALAPPDATA%\SELENE\logs\selene-YYYYMMDD.log
~~~

The raw timeline is written only to the export folder chosen by the user.
SELENE never scans that folder before writing, never merges old events, never
rewrites an existing JSON file, and never deletes an old snapshot. If a write
is interrupted, the incomplete snapshot is left for inspection and a later
run creates a new directory.

The snapshot uses:

~~~json
{
  "schema": "selene-context-events/v1",
  "device": { "platform": "windows" },
  "producer": {
    "name": "SELENE",
    "version": "0.3.0",
    "layout": "immutable-snapshot-v1"
  },
  "events": []
}
~~~

THEIA receives only the coarse model projection. Exact coordinates and
address-like fields, when present in another SELENE platform's local data,
are not sent to the model.

## Troubleshooting

If no new folder appears, verify that the selected parent directory still
exists and that SELENE is visible in the tray. If the folder is on a removable
drive, use a stable local directory instead. A failed write is recorded in the
daily log; the application remains available for an immediate retry.

If Windows startup was enabled for an old extracted directory, disable it in
the SELENE window before moving or deleting that directory. The startup entry
is stored under the current user's Run key and does not affect other users.

## Development

~~~powershell
dotnet build desktop\SELENE.Windows\SELENE.Windows.csproj -c Release
dotnet run --project desktop\SELENE.Windows.ContractTests\SELENE.Windows.ContractTests.csproj -c Release
dotnet publish desktop\SELENE.Windows\SELENE.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:PublishTrimmed=false -o releases\SELENE-0.3.0-windows-x64
~~~

The contract test writes two snapshots using the same timestamp and asserts
that their directories differ and that the first JSON remains byte-for-byte
unchanged.
