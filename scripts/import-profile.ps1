<#
.SYNOPSIS
  Copy the official Nuvio profile into the fork's own profile folder, once (SPEC P8-6, Q41).

.DESCRIPTION
  Copies %APPDATA%\Nuvio (settings, profiles, watch progress) to %APPDATA%\<fork name> (fork-identity.properties).
  The official folder is only READ. Refuses when the fork profile already has files (so it never overwrites what the
  fork saved), and when a Nuvio or fork process is running (a half-written file would be copied).
  The cache (%LOCALAPPDATA%\...\Cache) and WebView2 folders are not copied; they rebuild themselves. Also skipped:
  updates\ (official installers), *.part (unfinished downloads), nuvio_sync_client_identity.properties.

.PARAMETER Source
  Default %APPDATA%\Nuvio.

.PARAMETER Target
  Default %APPDATA%\<fork name>.

.PARAMETER WhatIf
  List what would be copied.
#>
param([string]$Source, [string]$Target, [switch]$WhatIf)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$idFile = Join-Path $repo 'fork-identity.properties'
$line = if (Test-Path $idFile) { Get-Content $idFile | Where-Object { $_ -match '^\s*name\s*=' } | Select-Object -First 1 }
if (-not $line) { throw "no name in $idFile" }
$name = ($line -split '=', 2)[1].Trim()
if (-not $Source) { $Source = Join-Path $env:APPDATA 'Nuvio' }
if (-not $Target) { $Target = Join-Path $env:APPDATA $name }
$Source = [IO.Path]::GetFullPath($Source); $Target = [IO.Path]::GetFullPath($Target)
if ($Source -eq $Target) { throw "source and target are the same folder: $Source" }
if (-not (Test-Path $Source)) { throw "no official profile at $Source" }
if ((Test-Path $Target) -and (Get-ChildItem $Target -Recurse -File -ErrorAction SilentlyContinue | Select-Object -First 1)) {
    throw "the fork profile already has files: $Target (delete it first if you really want a fresh copy)"
}
$running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -in 'Nuvio', $name })
if ($running) { throw "close Nuvio first (running: $(($running | ForEach-Object { "$($_.ProcessName) $($_.Id)" }) -join ', '))" }

# Not copied: the official updater's downloaded installers, unfinished downloads, and the sync client id (the fork
# must not look like the same device to Nuvio sync; it makes its own on first start).
$skip = { param($rel) $rel -like 'updates\*' -or $rel -like '*.part' -or $rel -eq 'nuvio_sync_client_identity.properties' }
$files = @(Get-ChildItem $Source -Recurse -File | Where-Object { -not (& $skip $_.FullName.Substring($Source.Length).TrimStart('\')) })
Write-Host ("{0} files, {1:n1} MB: {2} -> {3}" -f $files.Count, (($files | Measure-Object Length -Sum).Sum / 1MB), $Source, $Target)
foreach ($f in $files) {
    $rel = $f.FullName.Substring($Source.Length).TrimStart('\')
    $dest = Join-Path $Target $rel
    if ($WhatIf) { Write-Host "  would copy $rel"; continue }
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item -LiteralPath $f.FullName -Destination $dest
    Write-Host "  copied $rel"
}
if (-not $WhatIf) {
    $copied = @(Get-ChildItem $Target -Recurse -File).Count
    if ($copied -ne $files.Count) { throw "copied $copied of $($files.Count) files" }
    Write-Host "done: $copied files. The official profile was not changed."
}
