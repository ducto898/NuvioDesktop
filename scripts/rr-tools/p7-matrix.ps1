<#
.SYNOPSIS
  Phase 7 unattended matrix (SPEC P7-7..P7-12): runs scripts\measure.ps1 once per entry, one after the other,
  and appends "<exit> <run folder> <args>" to measurements\phase7-batch-<set>.txt. Judge afterwards with
  scripts\rr-tools\p7-collect.py judge <folders>.

.PARAMETER Set
  matrix | off | switch | lifecycle | faults | bad | soak | soak60 | all (everything except soak60)
#>
param([string]$Set = 'all')
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$measure = Join-Path $repo 'scripts\measure.ps1'
$rates = '23.976', '24', '25', '29.97', '50', '59.94', '60', 'vfr'

$sets = [ordered]@{}
# P7-7: every clip windowed with the setting on; the 2160p HDR clips also fullscreen.
$sets.matrix = @(foreach ($k in 'sdr-1080p', 'hdr-1080p', 'sdr-2160p', 'hdr-2160p') { foreach ($r in $rates) {
            @{ Clip = "$k-$r"; Setting = 'on'; Seconds = 60; Label = 'p7-mx-win' } } }) +
    @(foreach ($r in $rates) { @{ Clip = "hdr-2160p-$r"; Setting = 'on'; Seconds = 60; Fullscreen = $true; Label = 'p7-mx-fs' } })
# P7-8: feature off (env 0).
$sets.off = @('sdr-1080p-23.976', 'sdr-1080p-59.94', 'hdr-2160p-23.976' | ForEach-Object { @{ Clip = $_; Seconds = 60; Label = 'p7-off' } })
# P7-9: switch path under the measure-only cap (Q30).
$sets.switch = @('sdr-1080p-23.976', 'sdr-1080p-24', 'sdr-1080p-29.97', 'sdr-1080p-59.94', 'sdr-1080p-60' |
        ForEach-Object { @{ Clip = $_; Setting = 'on'; MaxHz = 144; Seconds = 60; Label = 'p7-sw-win' } }) +
    @(@{ Clip = 'hdr-2160p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 60; Fullscreen = $true; Label = 'p7-sw-fs' })
# P7-10: lifecycle at the capped switch.
$sets.lifecycle = @(foreach ($ms in 0, 50, 150, 300) { foreach ($i in 1, 2) {
            @{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 20; CloseAfterSwitchMs = $ms; Label = "p7-close$ms-r$i" } } }) +
    @(@{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 30; Kill = $true; Label = 'p7-kill' }) +
    @(@{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 90; Fault = 'drop-mode'; Label = 'p7-dropmode' }) +
    @(@{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 120; Label = 'p7-actions'
            Actions = 'space@20,space@40,right@50,left@60,mouse@70,f11@80,f11@90,alttab@100,alttab@108' })
# P7-11: every fault kind (under the cap so the switch-side kinds fire).
$sets.faults = @('enumerate', 'display-not-found', 'switch-api', 'settle-timeout', 'slow-settle', 'verify-mismatch', 'restore-failed',
    'unexpected', 'timing-set', 'enable-upcall' | ForEach-Object { @{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 20; Fault = $_; Label = "p7-fault-$_" } })
# P7-14: known-bad display-resample (Phase 5 set) at 239.901 and at the capped 143.973.
$sets.bad = @(
    @{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; Seconds = 40; Opts = 'd3d11-sync-interval=0'; Label = 'p7-badsync-240' },
    @{ Clip = 'sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 40; Opts = 'd3d11-sync-interval=0'; Label = 'p7-badsync-cap' })
# P7-12: soaks (stream-copied, scripts\gen-soakclips.ps1).
$sets.soak = @(
    @{ Clip = 'soak10-hdr-2160p-23.976'; Setting = 'on'; Seconds = 590; Fullscreen = $true; Power = $true; Label = 'p7-soak' },
    @{ Clip = 'soak10-sdr-1080p-59.94'; Setting = 'on'; Seconds = 590; Power = $true; Label = 'p7-soak' },
    @{ Clip = 'soak10-sdr-1080p-25'; Setting = 'on'; Seconds = 590; Power = $true; Label = 'p7-soak' },
    @{ Clip = 'soak10-sdr-1080p-23.976'; Setting = 'on'; MaxHz = 144; Seconds = 590; Power = $true; Label = 'p7-soak-cap' })
$sets.soak60 = @(@{ Clip = 'soak60-hdr-2160p-23.976'; Setting = 'on'; Seconds = 3590; Fullscreen = $true; Power = $true; Label = 'p7-soak60' })

$names = if ($Set -eq 'all') { @($sets.Keys | Where-Object { $_ -ne 'soak60' }) } else { @($Set -split ',') }
foreach ($name in $names) {
    $batchLog = Join-Path $repo "measurements\phase7-batch-$name.txt"
    foreach ($entry in $sets[$name]) {
        $splat = @{} + $entry
        $before = @(Get-ChildItem (Join-Path $repo 'measurements') -Directory | ForEach-Object Name)
        $code = 99
        try {
            & $measure @splat *> (Join-Path $env:TEMP 'p7-matrix-last.txt')   # measure.ps1's exit ends only itself
            $code = $LASTEXITCODE
        } catch {
            Add-Content $batchLog "$(Get-Date -Format 'HH:mm:ss') runner error: $($_.Exception.Message)"
        }
        $new = @(Get-ChildItem (Join-Path $repo 'measurements') -Directory | Where-Object { $before -notcontains $_.Name } | ForEach-Object Name)
        $text = ($entry.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ' '
        Add-Content $batchLog "$(Get-Date -Format 'HH:mm:ss') exit=$code $($new -join ',') $text"
        Start-Sleep -Seconds 3
    }
}
