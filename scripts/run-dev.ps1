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
  Turn refresh-rate matching on (NUVIO_RR_ENABLE=1, the dev knob until the Phase 6 setting). Its log is
  <ProfileRoot>\Local\Nuvio\Cache\refresh-rate.log.
#>
param(
    [string]$ProfileRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'devprofile'),
    [string[]]$GradleArgs = @(),
    [switch]$Feature
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
if ($Feature) {
    $env:NUVIO_RR_ENABLE = '1'
    Write-Host "Refresh-rate matching ON; log: $(Join-Path $local 'Nuvio\Cache\refresh-rate.log')"
}

Set-Location $repo
& "$repo\gradlew.bat" :composeApp:run --no-daemon --no-configuration-cache --console=plain @GradleArgs
exit $LASTEXITCODE
