# SELENE

[简体中文](README.md) | [English](README.en.md)

**SELENE** is the standalone Android and Windows timeline collector for THEIA.
It collects explicitly authorized, non-text device context locally and writes
immutable snapshots for THEIA to import. Each platform has its own package,
settings, scheduler, and export directory; neither platform depends on, reads,
or modifies THEIA files.

SELENE does not read chat databases, notification contents, SMS, calls,
keyboard input, payment history, screenshots, or other-application databases.

Android 0.5.2 embeds a verified Syncthing native core. SELENE Windows creates a
five-minute one-use enrollment QR and automatically approves the scanning
phone. After one same-LAN enrollment, later sync works across networks without
a self-hosted server or another scan. See [one-time P2P pairing](docs/P2P_SYNC.md)
or the [Chinese guide](docs/P2P_SYNC.zh-CN.md).

## Platforms

- **Android 0.5.2** collects screen use, foreground app sessions, calendar,
  device and network snapshots. With explicit background-location consent, it
  also records confirmed continuous movement, sampled speed, and a fresh
  location fallback for place context.
- **Windows 0.5.2** collects foreground process sessions, idle time, power
  state, and network transport while the tray application is running. Users
  can explicitly opt into window titles, executable paths, and the URL of the
  current foreground browser; those fields are marked sensitive.

See [Android movement tracking](docs/ANDROID_MOVEMENT.md) for Android setup,
permissions, filtering, data fields, and limitations. The Chinese guide is
[ANDROID_MOVEMENT.zh-CN.md](docs/ANDROID_MOVEMENT.zh-CN.md). Windows guides:
[WINDOWS_DESKTOP.md](docs/WINDOWS_DESKTOP.md) and
[WINDOWS_DESKTOP.zh-CN.md](docs/WINDOWS_DESKTOP.zh-CN.md).

For architecture, the immutable event contract, exact movement thresholds,
THEIA import behavior, testing, and safe extension procedures, read the
[Developer Guide](docs/DEVELOPER_GUIDE.md) or
[Chinese Developer Guide](docs/DEVELOPER_GUIDE.zh-CN.md).

## Immutable Export Layout

Every successful collection run creates a new directory below the active
output root. Before pairing this is the SAF folder selected by the user; after
pairing it is the private Send Only root synchronized to the Windows inbox:

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
    "version": "0.5.2",
    "layout": "immutable-snapshot-v1"
  }
}
```

THEIA's connected-directory import already scans subdirectories recursively, so
choose the parent `selected-export-folder`, not an individual snapshot.

THEIA imports only this SELENE contract. SELENE never reads, converts, or
modifies earlier files.

## Build

Requirements: Android SDK Platform 35, JDK 17, and Gradle 8.9 or a compatible
version. Configure `JAVA_HOME`, `ANDROID_HOME`, and Gradle locally before
building.

```powershell
gradle --no-daemon lintDebug assembleDebug
```

The Android application is `0.5.2`. The APK supports physical-phone
`arm64-v8a` and `armeabi-v7a` ABIs. See
[EXPORT_LAYOUT.md](docs/EXPORT_LAYOUT.md) for the data contract and
[EXPORT_LAYOUT.zh-CN.md](docs/EXPORT_LAYOUT.zh-CN.md) for its Chinese version.

## Windows Build

Requirements: Windows 10 22H2 or Windows 11 x64 and .NET SDK 9.0. The Windows
collector is a native WPF application and has no third-party runtime
dependencies.

~~~powershell
dotnet build desktop\SELENE.Windows\SELENE.Windows.csproj -c Release
dotnet run --project desktop\SELENE.Windows.ContractTests\SELENE.Windows.ContractTests.csproj -c Release
dotnet publish desktop\SELENE.Windows\SELENE.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:PublishTrimmed=false -o releases\SELENE-0.5.2-windows-x64
~~~

The Windows collector records only foreground process names and bounded usage
sessions, idle time, power state, and network transport. It does not collect
window titles, web content, keystrokes, clipboard data, notifications, chat
databases, screenshots, or payment history. See
[WINDOWS_DESKTOP.md](docs/WINDOWS_DESKTOP.md) and
[WINDOWS_DESKTOP.zh-CN.md](docs/WINDOWS_DESKTOP.zh-CN.md).

## One-Time Android Pairing

In SELENE Windows, confirm the inbox and select **Generate one-time pairing
QR**. This explicit action installs the official winget Syncthing package when
needed. Scan the QR in Android SELENE, or paste its text form. Pairing enables
automatic collection and a private Send Only folder on Android; Windows uses
Receive Only and stores the inbox in the user-level `THEIA_SELENE_INBOX`.
Only enrollment needs one trusted LAN. Later delivery uses Syncthing discovery,
NAT traversal, or encrypted relay fallback across networks.

Android requires a low-importance foreground-service notification for legal
and reliable background sync; SELENE does not attempt to hide it. Full protocol,
security, recovery, and troubleshooting details are in
[P2P_SYNC.md](docs/P2P_SYNC.md). Third-party sources and licenses are in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Release Artifacts

The Windows package is self-contained for x64 Windows. The Android package is
debug-signed until a release-signing key is managed outside the repository.
The repeatable build, validation, checksum, and GitHub Release procedure is in
[RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md) and
[RELEASE_PROCESS.zh-CN.md](docs/RELEASE_PROCESS.zh-CN.md).
