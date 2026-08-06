# SELENE Release Process

Each SELENE release is built into a new, immutable local release directory and
then attached to a GitHub Release. Release files are not committed to Git.
Choose one release version and align the Android `versionName`/`versionCode`
and Windows assembly version before packaging both platforms.

## Prerequisites

- Windows 10 22H2 or newer.
- .NET SDK 9.0.
- JDK 17.
- The project-local Android SDK and Gradle distribution under `.android-build`.

The script uses the project-local Android toolchain so it does not depend on a
machine-wide Android Studio installation. It does not download dependencies
when the local Gradle cache is warm; when a download is required, set the local
proxy in the shell before invoking it.

## Build and Verify

From the repository root, run:

~~~powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
.\tools\prepare-release.ps1 -Version 0.3.0
~~~

Pass `-Version` explicitly. The command refuses to reuse its matching
`releases\v<version>` directory. This protects an earlier local
staging directory from accidental overwrite. It performs the following checks:

1. Android debug lint and APK build.
2. Windows Release build.
3. Windows immutable-snapshot contract test.
4. Windows self-contained, single-file x64 publish.
5. Android manifest, ZIP alignment, and signature validation.
6. Windows ZIP inventory validation.
7. SHA-256 checksum generation.

Artifacts are placed here:

```text
releases/
  v0.3.0/
    published/windows-x64/       # unpacked self-contained Windows output
    artifacts/
      SELENE-0.3.0-windows-x64.zip
      SELENE-0.3.0-android-debug.apk
      SHA256SUMS.txt
```

`published/` and `artifacts/` are intentionally ignored by Git. The Android
artifact is debug-signed because no private release signing key is kept in this
repository. Its name states that fact explicitly.

## GitHub Release Checklist

1. Confirm source changes are committed and pushed.
2. Create the `v<version>` tag from that commit.
3. Create a GitHub Release from the tag.
4. Attach both binary artifacts and `SHA256SUMS.txt`.
5. State the privacy boundary in the release notes: SELENE collects only the
   documented local, non-text signals and does not collect chat content,
   keystrokes, clipboard data, screenshots, notification text, SMS, calls,
   payment history, or other-application databases.
6. Download one attached artifact and check its SHA-256 against the attached
   checksum file before announcing the release.
7. For Android releases with movement tracking, state that users must select
   precise location and "Allow all the time" before a confirmed track can be
   collected. Notification permission makes the foreground-service state
   visible but does not grant location access.

Use the release notes to identify the supported schema
`selene-context-events/v1`, so users know that THEIA imports only SELENE's
strict immutable snapshot envelope.
