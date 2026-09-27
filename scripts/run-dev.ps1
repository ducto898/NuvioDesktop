<#
.SYNOPSIS
  Launch the dev build with an ISOLATED profile, so it never touches the official Nuvio's
  %APPDATA%\Nuvio settings/watch progress or %LOCALAPPDATA%\Nuvio cache.

.DESCRIPTION
  Upstream resolves its data dirs from %APPDATA% / %LOCALAPPDATA% (DesktopStorage.kt). This
  script redirects both for the app process only (no code change). The permanent fix (a distinct
  app identity and data folder) is requirement 20, Phase 8.
  Uses --no-daemon so the app JVM inherits exactly this environment.

.PARAMETER ProfileRoot
  Where the dev profile lives. Default: <repo>\..\devprofile (outside the repo).

.PARAMETER GradleArgs
  Extra Gradle arguments (e.g. -Pnuvio.desktop.smokePlayerUrl=...).
#>
param(
    [string]$ProfileRoot = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'devprofile'),
    [string[]]$GradleArgs = @()
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
Write-Host "Dev profile: $ProfileRoot (APPDATA/LOCALAPPDATA redirected for this process)"

Set-Location $repo
& "$repo\gradlew.bat" :composeApp:run --no-daemon --no-configuration-cache --console=plain @GradleArgs
exit $LASTEXITCODE
