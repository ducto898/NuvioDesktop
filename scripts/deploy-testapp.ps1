<#
.SYNOPSIS
  Unzip a packaged Nuvio RR into the owner's test folder (C:\TestAPP) and point the desktop shortcut at it.

.DESCRIPTION
  Owner request 2026-10-26: every package lands in C:\TestAPP\<zip name>\Nuvio RR\Nuvio RR.exe, and the desktop
  shortcut opens that build. package-fork.ps1 calls this after the zip (skip with -NoDeploy there).
  Steps:
    1. Refuse a -dirty zip (uncommitted changes) unless -Force.
    2. Unzip into a fresh C:\TestAPP\<zip name> (an existing folder of the same name is replaced, unless that build
       is running).
    3. Every .lnk on the user's and the public desktop whose target is a "Nuvio RR.exe" under the test folder is
       pointed at the new exe (none found: "Nuvio RR.lnk" is created on the user's desktop). Each one is read back
       and must name the new exe, which must exist.
    4. .cmd launchers under the test folder (e.g. hdr-meta\play-*.cmd) that start a Nuvio RR.exe from an older
       build folder are pointed at the new one.
    5. Only with -Keep N (N >= 1): older Nuvio-RR-* build folders are deleted, keeping the newest N (e.g. 2: this one
       and the previous, for a quick rollback). A folder whose Nuvio RR.exe is running is kept. Default 0: delete
       nothing (Claude Code's auto mode refuses the delete unattended).
  App data is not in these folders (%APPDATA%\Nuvio RR, %LOCALAPPDATA%\Nuvio RR), so deleting a build loses nothing.

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
$exeName = 'Nuvio RR.exe'

if (-not $Zip) {
    $Zip = Get-ChildItem (Join-Path (Split-Path -Parent $repo) 'dist') -Filter 'Nuvio-RR-*.zip' |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (-not $Zip) { throw 'no Nuvio-RR-*.zip in ..\dist; run package-fork.ps1 first' }
}
$Zip = (Resolve-Path $Zip).Path
$build = [IO.Path]::GetFileNameWithoutExtension($Zip)
if ($build -like '*-dirty' -and -not $Force) { Write-Host "deploy skipped: $build has uncommitted changes (-Force to deploy anyway)"; return }

function Get-RunningExes {
    @(Get-CimInstance Win32_Process -Filter "Name='$exeName'" -ErrorAction SilentlyContinue | ForEach-Object { $_.ExecutablePath } |
        Where-Object { $_ })
}

# --- 2. unzip ---------------------------------------------------------------------------------------------------
New-Item -ItemType Directory -Force $Root | Out-Null
$target = Join-Path $Root $build
$exe = Join-Path $target "Nuvio RR\$exeName"
if (Test-Path $target) {
    if ((Get-RunningExes) -contains $exe) { throw "$exe is running; close Nuvio RR and deploy again" }
    Remove-Item $target -Recurse -Force
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$pending = "$target.part"
if (Test-Path $pending) { Remove-Item $pending -Recurse -Force }
[IO.Compression.ZipFile]::ExtractToDirectory($Zip, $pending)
Rename-Item $pending $build
if (-not (Test-Path $exe)) { throw "the zip has no 'Nuvio RR\$exeName' ($Zip)" }
Write-Host "deployed: $exe"

# --- 3. desktop shortcuts ---------------------------------------------------------------------------------------
$shell = New-Object -ComObject WScript.Shell
$rootFull = (Resolve-Path $Root).Path.TrimEnd('\') + '\'
$desktops = @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('CommonDesktopDirectory')) |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique
$links = @(foreach ($d in $desktops) {
    Get-ChildItem $d -Filter *.lnk -ErrorAction SilentlyContinue | Where-Object {
        $t = $shell.CreateShortcut($_.FullName).TargetPath
        $t -and $t.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $t -Leaf) -eq $exeName
    } | ForEach-Object FullName
})
if (-not $links) { $links = @(Join-Path ([Environment]::GetFolderPath('Desktop')) 'Nuvio RR.lnk') }
foreach ($path in $links) {
    $link = $shell.CreateShortcut($path)
    $link.TargetPath = $exe
    $link.WorkingDirectory = Split-Path $exe
    $link.IconLocation = "$exe,0"
    $link.Save()
    $check = $shell.CreateShortcut($path)
    if ($check.TargetPath -ne $exe -or $check.WorkingDirectory -ne (Split-Path $exe) -or -not (Test-Path $check.TargetPath)) {
        throw "shortcut $path did not take the new target (reads back: $($check.TargetPath))"
    }
    Write-Host "shortcut OK: $path -> $($check.TargetPath)"
}

# --- 4. .cmd launchers ------------------------------------------------------------------------------------------
$pattern = '(?i)' + [regex]::Escape($rootFull) + 'Nuvio-RR-[^\\"]+\\Nuvio RR\\Nuvio RR\.exe'
Get-ChildItem $Root -Recurse -Depth 2 -Filter *.cmd -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notlike "$target\*" } | ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName)
        $new = [regex]::Replace($text, $pattern, { param($m) $exe })
        if ($new -ne $text) {
            [IO.File]::WriteAllText($_.FullName, $new)
            Write-Host "launcher updated: $($_.FullName)"
        }
    }

# --- 5. old builds ----------------------------------------------------------------------------------------------
if ($Keep -lt 1) { return }
$running = Get-RunningExes
Get-ChildItem $Root -Directory -Filter 'Nuvio-RR-*' | Sort-Object CreationTime -Descending |
    Where-Object { $_.FullName -ne $target } | Select-Object -Skip ([Math]::Max(0, $Keep - 1)) | ForEach-Object {
        $old = Join-Path $_.FullName "Nuvio RR\$exeName"
        if ($running -contains $old) { Write-Host "kept (running): $($_.FullName)"; return }
        Remove-Item $_.FullName -Recurse -Force
        Write-Host "removed old build: $($_.FullName)"
    }
