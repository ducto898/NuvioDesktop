<#
.SYNOPSIS
  Build the fork as a portable app folder and zip it (SPEC P8-10, Q40): no installer, no WiX, no admin rights.

.DESCRIPTION
  Runs :composeApp:createDistributable with the fork identity (fork-identity.properties, Gradle hook H15), so the
  folder is composeApp\build\compose\binaries\main\app\<name>\ with <name>.exe, the bundled Java runtime, and libmpv,
  the player bridge and WebView2Loader inside the app jar (unpacked at run time into %LOCALAPPDATA%\<name>\Cache).
  Then zips it to <repo>\..\dist\<name>-<version>-<commit>.zip (outside the repo).
  Unzip anywhere and run <name>.exe. Data: %APPDATA%\<name>; cache and WebView2: %LOCALAPPDATA%\<name>.

.PARAMETER NoZip
  Build the folder only.
#>
param([switch]$NoZip)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $repo

if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw 'JDK 17 not found. Set JAVA_HOME or see FORK.md (toolchain).' }
    $env:JAVA_HOME = $jdk.FullName
}
$idFile = Join-Path $repo 'fork-identity.properties'
$line = if (Test-Path $idFile) { Get-Content $idFile | Where-Object { $_ -match '^\s*name\s*=' } | Select-Object -First 1 }
if (-not $line) { throw "no name in ${idFile}: this would build the upstream-named app" }
$name = ($line -split '=', 2)[1].Trim()
Remove-Item Env:NUVIO_FORK_IDENTITY -ErrorAction SilentlyContinue   # identity ON for the package

Push-Location $repo
try {
    & .\gradlew.bat :composeApp:createDistributable --no-configuration-cache --console=plain
    if ($LASTEXITCODE) { throw "createDistributable failed (exit $LASTEXITCODE)" }
} finally { Pop-Location }

$app = Join-Path $repo "composeApp\build\compose\binaries\main\app\$name"
$cfg = Join-Path $app "app\$name.cfg"
if (-not (Test-Path (Join-Path $app "$name.exe"))) { throw "missing $name.exe in $app" }
if (-not (Select-String -Path $cfg -SimpleMatch "-Dnuvio.fork.name=$name" -Quiet)) { throw "launcher lacks -Dnuvio.fork.name=$name ($cfg)" }
$jar = Get-ChildItem (Join-Path $app 'app') -Filter 'composeApp-desktop-*.jar' | Select-Object -First 1
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zipJar = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    foreach ($need in 'native/windows/player_bridge.dll', 'native/windows/libmpv-2.dll', 'native/windows/WebView2Loader.dll') {
        if (-not ($zipJar.Entries | Where-Object FullName -eq $need)) { throw "app jar lacks $need" }
    }
} finally { $zipJar.Dispose() }
Write-Host "app folder: $app"

if (-not $NoZip) {
    $version = (Select-String -Path $cfg -Pattern '-Djpackage.app-version=(\S+)').Matches.Groups[1].Value
    $commit = (git -C $repo rev-parse --short=8 HEAD)
    $dirty = if (git -C $repo status --porcelain -- composeApp fork-identity.properties) { '-dirty' } else { '' }
    $dist = Join-Path $root 'dist'
    New-Item -ItemType Directory -Force $dist | Out-Null
    $zip = Join-Path $dist ("{0}-{1}-{2}{3}.zip" -f ($name -replace ' ', '-'), $version, $commit, $dirty)
    if (Test-Path $zip) { Remove-Item $zip }
    [IO.Compression.ZipFile]::CreateFromDirectory($app, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
    Write-Host ("zip: {0} ({1:n0} MB)" -f $zip, ((Get-Item $zip).Length / 1MB))
}
