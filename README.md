# SELENE

**SELENE** is the standalone Android and Windows timeline collector for THEIA.
It collects explicitly authorized, non-text device context locally and writes
immutable snapshots for THEIA to import. Each platform has its own package,
settings, scheduler, and export directory; neither platform depends on, reads,
or modifies THEIA files.

SELENE does not read chat databases, notification contents, SMS, calls,
keyboard input, payment history, screenshots, or other-application databases.

## Platforms

- **Android 0.3.0** collects screen use, foreground app sessions, calendar,
  device and network snapshots. With explicit background-location consent, it
  also records confirmed continuous movement, sampled speed, and a fresh
  location fallback for place context.
- **Windows 0.3.0** collects foreground process sessions, idle time, power
  state, and network transport while the tray application is running.

See [Android movement tracking](docs/ANDROID_MOVEMENT.md) for Android setup,
permissions, filtering, data fields, and limitations. The Chinese guide is
[ANDROID_MOVEMENT.zh-CN.md](docs/ANDROID_MOVEMENT.zh-CN.md). Windows guides:
[WINDOWS_DESKTOP.md](docs/WINDOWS_DESKTOP.md) and
[WINDOWS_DESKTOP.zh-CN.md](docs/WINDOWS_DESKTOP.zh-CN.md).

## Immutable Export Layout

Every successful collection run creates a new directory in the folder selected
through Android's system folder picker:

```text
selected-export-folder/
  SELENE-v1-20260806T185439123Z/
    context-events.json
  SELENE-v1-20260806T195441876Z/
    context-events.json
```

The directory name contains both the layout version (`v1`) and the UTC creation
timestamp (`yyyyMMddTHHmmssSSSZ`). `context-events.json` is written once.
SELENE never opens, merges, rewrites, or deletes an older snapshot directory or
JSON file. Duplicate event IDs across snapshots are expected after retries;
THEIA deduplicates them during import.

Every export uses the strict `selene-context-events/v1` contract and includes a
required producer marker:

```json
{
  "producer": {
    "name": "SELENE",
    "version": "0.3.0",
    "layout": "immutable-snapshot-v1"
  }
}
```

THEIA's connected-directory import already scans subdirectories recursively, so
choose the parent `selected-export-folder`, not an individual snapshot.

THEIA imports only this SELENE contract. SELENE never reads, converts, or
modifies earlier files.

## Build

Requirements: Android SDK Platform 35 and JDK 17. The prepared Android build
toolchain is stored with SELENE under `H:\work\SELENE\.android-build`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:ANDROID_HOME = 'H:\work\SELENE\.android-build\sdk'
& 'H:\work\SELENE\.android-build\gradle-8.9\bin\gradle.bat' --no-daemon lintDebug assembleDebug
```

The Android application is `0.3.0`. See
[EXPORT_LAYOUT.md](docs/EXPORT_LAYOUT.md) for the data contract and
[EXPORT_LAYOUT.zh-CN.md](docs/EXPORT_LAYOUT.zh-CN.md) for its Chinese version.

## Windows Build

Requirements: Windows 10 22H2 or Windows 11 x64 and .NET SDK 9.0. The Windows
collector is a native WPF application and has no third-party runtime
dependencies.

~~~powershell
dotnet build desktop\SELENE.Windows\SELENE.Windows.csproj -c Release
dotnet run --project desktop\SELENE.Windows.ContractTests\SELENE.Windows.ContractTests.csproj -c Release
dotnet publish desktop\SELENE.Windows\SELENE.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:PublishTrimmed=false -o releases\SELENE-0.3.0-windows-x64
~~~

The Windows collector records only foreground process names and bounded usage
sessions, idle time, power state, and network transport. It does not collect
window titles, web content, keystrokes, clipboard data, notifications, chat
databases, screenshots, or payment history. See
[WINDOWS_DESKTOP.md](docs/WINDOWS_DESKTOP.md) and
[WINDOWS_DESKTOP.zh-CN.md](docs/WINDOWS_DESKTOP.zh-CN.md).

## Release Artifacts

The Windows package is self-contained for x64 Windows. The Android package is
debug-signed until a release-signing key is managed outside the repository.
The repeatable build, validation, checksum, and GitHub Release procedure is in
[RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md) and
[RELEASE_PROCESS.zh-CN.md](docs/RELEASE_PROCESS.zh-CN.md).
