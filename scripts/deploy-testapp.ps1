<#
.SYNOPSIS
  Install a packaged Nuvio RR at the fixed path C:\TestAPP\Nuvio RR\Nuvio RR.exe and point the desktop shortcut at it.

.DESCRIPTION
  Owner request 2026-10-26: the newest build always lives at C:\TestAPP\Nuvio RR, so the desktop shortcut, the
  hdr-meta\*.cmd launchers and the owner's NVIDIA profile for Nuvio RR.exe (refresh-rate mode) keep working across
  updates. package-fork.ps1 calls this after the zip (skip with -NoDeploy there).
  Steps:
    1. Refuse a -dirty zip (uncommitted changes) unless -Force; refuse while Nuvio RR.exe runs from the fixed path.
    2. Unzip into C:\TestAPP\.staging, move the current C:\TestAPP\Nuvio RR to C:\TestAPP\old\<its build> (nothing
       is deleted), then move the new one into place. BUILD.txt in it names the zip.
       Versioned folders of the old layout (C:\TestAPP\Nuvio-RR-*) are moved to old\ as well.
    3. Every .lnk on the user's and the public desktop whose target is a Nuvio RR.exe under the test folder is pointed
       at the fixed exe (none found: "Nuvio RR.lnk" is created on the user's desktop), then read back.
    4. .cmd launchers under the test folder that start a Nuvio RR.exe elsewhere under it are pointed at the fixed exe.
    5. Only with -Keep N (N >= 1): old\ is trimmed to the newest N builds. Default 0: delete nothing (Claude Code's
       auto mode refuses unattended deletes).
  App data is not in these folders (%APPDATA%\Nuvio RR, %LOCALAPPDATA%\Nuvio RR).

.EXAMPLE
  pwsh -File scripts\deploy-testapp.ps1                     # newest zip in ..\dist
  pwsh -File scripts\deploy-testapp.ps1 -Zip ..\dist\Nuvio-RR-1.1.32-7aba5f98.zip
#>
param(
    [string]$Zip = '',
    [string]$Root = 'C:\TestAPP',
    [int]$Keep = 0,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$appName = 'Nuvio RR'
$exeName = "$appName.exe"

if (-not $Zip) {
    $Zip = Get-ChildItem (Join-Path (Split-Path -Parent $repo) 'dist') -Filter 'Nuvio-RR-*.zip' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $Zip) { throw 'no Nuvio-RR-*.zip in ..\dist; run package-fork.ps1 first' }
}
$Zip = (Resolve-Path $Zip).Path
$build = [IO.Path]::GetFileNameWithoutExtension($Zip)
if ($build -like '*-dirty' -and -not $Force) { Write-Host "deploy skipped: $build has uncommitted changes (-Force to deploy anyway)"; return }

New-Item -ItemType Directory -Force $Root | Out-Null
$Root = (Resolve-Path $Root).Path.TrimEnd('\')
$live = Join-Path $Root $appName
$exe = Join-Path $live $exeName
$oldDir = Join-Path $Root 'old'
$running = @(Get-CimInstance Win32_Process -Filter "Name='$exeName'" -ErrorAction SilentlyContinue | ForEach-Object ExecutablePath)
if ($running -contains $exe) { throw "$exe is running; close Nuvio RR and deploy again" }

function Move-ToOld([string]$path, [string]$label) {
    New-Item -ItemType Directory -Force $oldDir | Out-Null
    $dest = Join-Path $oldDir $label
    if (Test-Path $dest) { $dest = '{0}-{1:yyyyMMdd-HHmmss}' -f $dest, (Get-Date) }
    Move-Item $path $dest
    Write-Host "previous build moved to: $dest"
}

# --- 2. install at the fixed path -------------------------------------------------------------------------------
$staging = Join-Path $Root '.staging'
if (Test-Path $staging) { Move-ToOld $staging ".staging-leftover" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[IO.Compression.ZipFile]::ExtractToDirectory($Zip, $staging)
$fresh = Join-Path $staging $appName
if (-not (Test-Path (Join-Path $fresh $exeName))) { throw "the zip has no '$appName\$exeName' ($Zip)" }
Set-Content -LiteralPath (Join-Path $fresh 'BUILD.txt') -Value $build -Encoding utf8

if (Test-Path $live) {
    $buildFile = Join-Path $live 'BUILD.txt'
    $label = if (Test-Path $buildFile) { (Get-Content $buildFile -TotalCount 1).Trim() } else { 'Nuvio-RR-unknown' }
    Move-ToOld $live $label
}
Get-ChildItem $Root -Directory -Filter 'Nuvio-RR-*' | ForEach-Object {   # the old versioned layout
    if ($running -contains (Join-Path $_.FullName "$appName\$exeName")) { Write-Host "left in place (running): $($_.FullName)"; return }
    Move-ToOld $_.FullName $_.Name
}
Move-Item $fresh $live
Remove-Item $staging   # empty now; no -Recurse, so it fails rather than delete anything left in it
Write-Host "installed: $exe ($build)"

# --- 3. desktop shortcuts ---------------------------------------------------------------------------------------
$shell = New-Object -ComObject WScript.Shell
$under = $Root + '\'
$desktops = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory')) |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
$links = @(foreach ($d in $desktops) {
    Get-ChildItem $d -Filter *.lnk -ErrorAction SilentlyContinue | Where-Object {
        $t = $shell.CreateShortcut($_.FullName).TargetPath
        $t -and $t.StartsWith($under, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $t -Leaf) -eq $exeName
    } | ForEach-Object FullName
})
if (-not $links) { $links = @(Join-Path ([Environment]::GetFolderPath('Desktop')) "$appName.lnk") }
foreach ($path in $links) {
    $link = $shell.CreateShortcut($path)
    $link.TargetPath = $exe
    $link.WorkingDirectory = $live
    $link.IconLocation = "$exe,0"
    $link.Save()
    $check = $shell.CreateShortcut($path)
    if ($check.TargetPath -ne $exe -or $check.WorkingDirectory -ne $live -or -not (Test-Path $check.TargetPath)) {
        throw "shortcut $path did not take the new target (reads back: $($check.TargetPath))"
    }
    Write-Host "shortcut OK: $path -> $($check.TargetPath)"
}

# --- 4. .cmd launchers ------------------------------------------------------------------------------------------
$pattern = '(?i)' + [regex]::Escape($under) + '[^"\r\n]*?\\' + [regex]::Escape($exeName)
Get-ChildItem $Root -Recurse -Depth 2 -Filter *.cmd -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notlike "$live\*" -and $_.FullName -notlike "$oldDir\*" } | ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName)
        $new = [regex]::Replace($text, $pattern, { param($m) $exe })
        if ($new -ne $text) {
            [IO.File]::WriteAllText($_.FullName, $new)
            Write-Host "launcher updated: $($_.FullName)"
        }
    }

# --- 5. trim old\ (opt-in) --------------------------------------------------------------------------------------
if ($Keep -lt 1 -or -not (Test-Path $oldDir)) { return }
Get-ChildItem $oldDir -Directory | Sort-Object CreationTime -Descending | Select-Object -Skip $Keep | ForEach-Object {
    Remove-Item $_.FullName -Recurse -Force
    Write-Host "removed old build: $($_.FullName)"
}
