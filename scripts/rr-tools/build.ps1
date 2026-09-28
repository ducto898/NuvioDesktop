<#
.SYNOPSIS
  Build the refresh-rate kill-test tools (observer, switcher, restore) with MSVC.
  Output: <NuvioRate>\tools\rr-tools\*.exe (outside the repo). Fork tooling, SPEC P2-12.
#>
param([string]$OutDir = (Join-Path (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))) 'tools\rr-tools'))
$ErrorActionPreference = 'Stop'
$vswhere = 'C:\Program Files (x86)\Microsoft Visual Studio\Installer\vswhere.exe'
$vcvars = $null
if (Test-Path $vswhere) {
    $vcvars = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
        -find 'VC\Auxiliary\Build\vcvars64.bat' | Select-Object -First 1
}
if (-not $vcvars) {
    $vcvars = Get-ChildItem 'C:\Program Files\Microsoft Visual Studio\*\*\VC\Auxiliary\Build\vcvars64.bat' -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $vcvars) { throw 'vcvars64.bat not found (see FORK.md toolchain)' }
New-Item -ItemType Directory -Force $OutDir | Out-Null
$src = $PSScriptRoot
$cmds = foreach ($tool in 'observer', 'switcher', 'restore') {
    "cl /nologo /EHsc /O2 /W4 /DUNICODE /D_UNICODE /Fe:`"$OutDir\$tool.exe`" /Fo:`"$OutDir\\`" `"$src\$tool.cpp`" user32.lib || exit /b 1"
}
$bat = Join-Path $OutDir 'build.bat'
Set-Content -Encoding ascii $bat (@("@echo off", "call `"$vcvars`" >nul || exit /b 1") + $cmds)
& cmd /c $bat
if ($LASTEXITCODE -ne 0) { throw "build failed ($LASTEXITCODE)" }
Get-ChildItem $OutDir -Filter *.exe | ForEach-Object { Write-Host "built $($_.FullName)" }
