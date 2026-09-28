<#
.SYNOPSIS
  Build long soak clips by STREAM COPY (SPEC P7-12, D10): N copies of a test clip joined with ffmpeg's concat
  demuxer (-c copy), so timestamps run on and mpv never hits EOF (no loop-file restart hitch).

.EXAMPLE
  scripts\gen-soakclips.ps1                     # the Phase 7 set: 4 x 10 min + 1 x 60 min
  scripts\gen-soakclips.ps1 -Clip sdr-1080p-24 -Copies 4 -Prefix soak10
#>
param(
    [string[]]$Clip,
    [int]$Copies = 4,
    [string]$Prefix = 'soak10'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$data = Join-Path $repo 'testdata'
$jobs = if ($Clip) { $Clip | ForEach-Object { @{ Clip = $_; Copies = $Copies; Prefix = $Prefix } } } else {
    @('hdr-2160p-23.976', 'sdr-1080p-59.94', 'sdr-1080p-25', 'sdr-1080p-23.976' | ForEach-Object { @{ Clip = $_; Copies = 4; Prefix = 'soak10' } }) +
    @(@{ Clip = 'hdr-2160p-23.976'; Copies = 24; Prefix = 'soak60' })
}
foreach ($j in $jobs) {
    $src = Join-Path $data "$($j.Clip).mkv"
    if (-not (Test-Path $src)) { throw "missing $src (run scripts\gen-testclips.ps1)" }
    $dst = Join-Path $data "$($j.Prefix)-$($j.Clip).mkv"
    $list = Join-Path $data "_work\concat-$($j.Prefix)-$($j.Clip).txt"
    New-Item -ItemType Directory -Force (Split-Path $list) | Out-Null
    Set-Content -Encoding ascii $list ((1..$j.Copies) | ForEach-Object { "file '$($src -replace '\\', '/')'" })
    & ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i $list -map 0 -c copy $dst
    if ($LASTEXITCODE) { throw "ffmpeg failed for $dst" }
    $dur = [double](& ffprobe -v error -show_entries format=duration -of csv=p=0 $dst)
    $one = [double](& ffprobe -v error -show_entries format=duration -of csv=p=0 $src)
    "{0}: {1:n1} s ({2} x {3:n1} s)" -f (Split-Path $dst -Leaf), $dur, $j.Copies, $one
    if ([math]::Abs($dur - $j.Copies * $one) -gt 1) { throw "duration mismatch for $dst" }
}
