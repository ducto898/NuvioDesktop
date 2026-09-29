<#
.SYNOPSIS
  Launch the dev build with an ISOLATED profile, so it never touches the official Nuvio's
  %APPDATA%\Nuvio settings/watch progress or %LOCALAPPDATA%\Nuvio cache.

.DESCRIPTION
  Upstream resolves its data dirs from %APPDATA% / %LOCALAPPDATA% (DesktopStorage.kt). This
  script redirects both for the app process only (no code change), plus WEBVIEW2_USER_DATA_FOLDER,
  because the native bridge finds the WebView2 folder through the Windows known-folder API, which
  ignores the LOCALAPPDATA variable (until 2026-09-28 dev runs wrote the official
  %LOCALAPPDATA%\Nuvio\WebView2). measure.ps1 checks both official folders. The permanent fix (a distinct
  app identity and data folder) is requirement 20, Phase 8.
  Uses --no-daemon so the app JVM inherits exactly this environment.

.PARAMETER ProfileRoot
  Where the dev profile lives. Default: <repo>\..\devprofile (outside the repo).

.PARAMETER GradleArgs
  Extra Gradle arguments (e.g. -Pnuvio.desktop.smokePlayerUrl=...).

.PARAMETER Feature
  Force refresh-rate matching on (NUVIO_RR_ENABLE=1, the dev/measure override of the "Match display refresh rate"
  setting in Settings > Playback > Display; NUVIO_RR_ENABLE=0 forces it off). Without -Feature and without the
  variable, the dev profile's setting decides. Its log is <ProfileRoot>\Local\Nuvio\Cache\refresh-rate.log.

.PARAMETER MaxHz
  Phase 7 (Q30): measure-only mode cap. Sets NUVIO_RR_MEASURE=1 (per-second mpv stats into
  <ProfileRoot>\Local\Nuvio\Cache\nuvio-rr) and NUVIO_RR_MEASURE_MAX_HZ=<n>, so modes above <n> are hidden from the
  feature and a 24 fps title switches (e.g. 240 -> 143.973 with 144) and restores to the desktop default.
#>
param(
    [string]$ProfileRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'devprofile'),
    [string[]]$GradleArgs = @(),
    [switch]$Feature,
    [int]$MaxHz = 0
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'JDK 17 not found. Set JAVA_HOME or see FORK.md (toolchain).' }
    $env:JAVA_HOME = $jdk.FullName
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$roaming = Join-Path $ProfileRoot 'Roaming'
$local = Join-Path $ProfileRoot 'Local'
New-Item -ItemType Directory -Force $roaming, $local | Out-Null
$env:APPDATA = $roaming
$env:LOCALAPPDATA = $local
# The bridge picks the WebView2 user-data folder with SHGetKnownFolderPath(FOLDERID_LocalAppData)
# (player_bridge.cpp webViewUserDataDirectory), which IGNORES the LOCALAPPDATA variable, so without
# this the player overlay wrote into the OFFICIAL %LOCALAPPDATA%\Nuvio\WebView2 (found 2026-09-28).
# WebView2's own environment override redirects it without an upstream code change.
$env:WEBVIEW2_USER_DATA_FOLDER = Join-Path $local 'Nuvio\WebView2'
New-Item -ItemType Directory -Force $env:WEBVIEW2_USER_DATA_FOLDER | Out-Null
Write-Host "Dev profile: $ProfileRoot (APPDATA/LOCALAPPDATA redirected for this process)"
# Phase 8 (P8-7): dev and measure runs keep upstream folder names inside the dev profile (devprofile\Roaming\Nuvio, ...);
# only the packaged fork app uses its own identity (fork-identity.properties, Gradle hook H15).
$env:NUVIO_FORK_IDENTITY = 'off'
if ($Feature) {
    $env:NUVIO_RR_ENABLE = '1'
    Write-Host "Refresh-rate matching ON; log: $(Join-Path $local 'Nuvio\Cache\refresh-rate.log')"
}
if ($MaxHz -gt 0) {
    $env:NUVIO_RR_MEASURE = '1'
    $env:NUVIO_RR_MEASURE_MAX_HZ = "$MaxHz"
    Write-Host "Measure-only mode cap: modes above $MaxHz Hz hidden from the feature; mpv stats in $(Join-Path $local 'Nuvio\Cache\nuvio-rr')"
}

Set-Location $repo
& "$repo\gradlew.bat" :composeApp:run --no-daemon --no-configuration-cache --console=plain @GradleArgs
exit $LASTEXITCODE
