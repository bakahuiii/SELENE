[CmdletBinding()]
param(
  [string]$Version = '0.5.2'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$projectRoot = Split-Path -Parent $PSScriptRoot
$releaseRoot = Join-Path $projectRoot "releases\v$Version"
$publishedDirectory = Join-Path $releaseRoot 'published\windows-x64'
$artifactsDirectory = Join-Path $releaseRoot 'artifacts'
$androidBuild = Join-Path $projectRoot '.android-build'
$gradle = Join-Path $androidBuild 'gradle-8.9\bin\gradle.bat'
$sdkBuildTools = Join-Path $androidBuild 'sdk\build-tools\35.0.0'
$syncthingProvenancePath = Join-Path $projectRoot 'app\src\main\jniLibs\SYNCTHING_PROVENANCE.json'

if (Test-Path $releaseRoot) {
  throw "Release directory already exists: $releaseRoot. SELENE release staging is immutable; choose a new version."
}
if (-not (Test-Path $gradle)) { throw "Gradle was not found: $gradle" }
if (-not (Get-Command dotnet -ErrorAction SilentlyContinue)) { throw 'dotnet SDK 9.0 is required.' }
if (-not (Test-Path -LiteralPath $syncthingProvenancePath -PathType Leaf)) { throw 'Syncthing provenance manifest is missing.' }

$syncthingProvenance = Get-Content -LiteralPath $syncthingProvenancePath -Raw -Encoding utf8 | ConvertFrom-Json
foreach ($entry in $syncthingProvenance.binaries) {
  $binaryPath = Join-Path $projectRoot ($entry.path -replace '/', '\')
  if (-not (Test-Path -LiteralPath $binaryPath -PathType Leaf)) { throw "Syncthing binary is missing: $($entry.path)" }
  $binary = Get-Item -LiteralPath $binaryPath
  $hash = (Get-FileHash -LiteralPath $binaryPath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($binary.Length -ne [long]$entry.size -or $hash -ne [string]$entry.sha256) {
    throw "Syncthing binary verification failed: $($entry.path)"
  }
}

New-Item -ItemType Directory -Path $publishedDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $artifactsDirectory -Force | Out-Null

Push-Location $projectRoot
try {
  $env:ANDROID_HOME = Join-Path $androidBuild 'sdk'
  & $gradle --no-daemon clean lintDebug assembleDebug
  if ($LASTEXITCODE -ne 0) { throw 'Android lint/build failed.' }

  dotnet build desktop\SELENE.Windows\SELENE.Windows.csproj -c Release
  if ($LASTEXITCODE -ne 0) { throw 'Windows build failed.' }
  dotnet run --project desktop\SELENE.Windows.ContractTests\SELENE.Windows.ContractTests.csproj -c Release
  if ($LASTEXITCODE -ne 0) { throw 'Windows immutable snapshot contract test failed.' }
  dotnet restore desktop\SELENE.Windows\SELENE.Windows.csproj -r win-x64
  if ($LASTEXITCODE -ne 0) { throw 'Windows runtime restore failed.' }
  dotnet publish desktop\SELENE.Windows\SELENE.Windows.csproj -c Release -r win-x64 --self-contained true --no-restore -p:PublishSingleFile=true -p:PublishTrimmed=false -p:IncludeNativeLibrariesForSelfExtract=true -o $publishedDirectory
  if ($LASTEXITCODE -ne 0) { throw 'Windows publish failed.' }

  $apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
  if (-not (Test-Path $apk)) { throw "Android APK not found: $apk" }
  $androidArtifact = Join-Path $artifactsDirectory "SELENE-$Version-android-debug.apk"
  Copy-Item -LiteralPath $apk -Destination $androidArtifact
  $apkArchive = [IO.Compression.ZipFile]::OpenRead($androidArtifact)
  try {
    $nativeEntries = @($apkArchive.Entries.FullName | Where-Object { $_ -like 'lib/*/libsyncthingnative.so' } | Sort-Object)
    $expectedNativeEntries = @(
      'lib/arm64-v8a/libsyncthingnative.so',
      'lib/armeabi-v7a/libsyncthingnative.so'
    )
    if (@(Compare-Object $expectedNativeEntries $nativeEntries).Count -ne 0) {
      throw "APK contains an unexpected Syncthing ABI set: $($nativeEntries -join ', ')"
    }
    if (-not ($apkArchive.Entries.FullName -contains 'assets/THIRD_PARTY_NOTICES.md') -or
        -not ($apkArchive.Entries.FullName -contains 'assets/licenses/MPL-2.0.txt')) {
      throw 'APK does not contain the required Syncthing source notice and MPL-2.0 license.'
    }
  } finally {
    $apkArchive.Dispose()
  }
  & (Join-Path $sdkBuildTools 'aapt.exe') dump badging $androidArtifact
  if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
  & (Join-Path $sdkBuildTools 'zipalign.exe') -c -v 4 $androidArtifact
  if ($LASTEXITCODE -ne 0) { throw 'APK alignment verification failed.' }
  & (Join-Path $sdkBuildTools 'apksigner.bat') verify --verbose $androidArtifact
  if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

  $windowsExecutable = Join-Path $publishedDirectory 'SELENE.Windows.exe'
  if (-not (Test-Path $windowsExecutable)) { throw "Windows executable not found: $windowsExecutable" }
  $windowsArtifact = Join-Path $artifactsDirectory "SELENE-$Version-windows-x64.zip"
  Compress-Archive -Path (Join-Path $publishedDirectory '*') -DestinationPath $windowsArtifact -CompressionLevel Optimal
  $zip = [IO.Compression.ZipFile]::OpenRead($windowsArtifact)
  try {
    if (-not ($zip.Entries | Where-Object FullName -eq 'SELENE.Windows.exe')) {
      throw 'Windows ZIP does not contain SELENE.Windows.exe.'
    }
  } finally {
    $zip.Dispose()
  }

  Get-FileHash -Algorithm SHA256 $androidArtifact, $windowsArtifact |
    ForEach-Object { "$($_.Hash.ToLowerInvariant())  $($_.Path | Split-Path -Leaf)" } |
    Set-Content -Encoding utf8 (Join-Path $artifactsDirectory 'SHA256SUMS.txt')

  Write-Host "Release artifacts prepared: $artifactsDirectory"
} finally {
  Pop-Location
}
