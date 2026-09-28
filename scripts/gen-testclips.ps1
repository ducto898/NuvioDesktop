<#
.SYNOPSIS
  Generate the refresh-rate test-clip set into <repo>\testdata (git-ignored). Fork tooling (SPEC P2-6).

.DESCRIPTION
  Per clip: a constant-speed horizontal pan of a high-contrast pattern (stripes on top, checker below),
  with the frame number, timestamp and nominal rate burned in, plus a quiet 440 Hz blip every second.
  Speed is an integer number of pixels per frame and the clip is a whole number of pattern periods
  long, so the last frame flows into the first one and the clip loops seamlessly.

  Matrix: 23.976 24 25 29.97 50 59.94 60 fps + VFR (60/48 fps alternating every 5 s),
          SDR (BT.709) and HDR10 (PQ, BT.2020, mastering display + MaxCLL/MaxFALL),
          1080p and 2160p, HEVC Main 10 (libx265), yuv420p10le, Matroska.
  HDR clips use a 203-nit white (PQ 0.58), not 10 000 nits.

  After encoding, every clip is checked with ffprobe (fps, codec/profile, pix_fmt, colour tags,
  HDR side data, duration, VFR frame durations). Exit code 1 if any check fails.

.PARAMETER Only
  Wildcard filter on clip names, e.g. 'sdr-1080p-*' or '*23.976*'.
.PARAMETER Seconds
  Target length (default 150). Rounded so the loop stays seamless.
.PARAMETER Force
  Re-encode clips that already exist (otherwise they are only re-checked).
.PARAMETER CheckOnly
  Do not encode; only run the ffprobe checks on existing clips.
#>
param(
    [string]$Only = '*',
    [double]$Seconds = 150,
    [switch]$Force,
    [switch]$CheckOnly
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$out = Join-Path $repo 'testdata'
$work = Join-Path $out '_work'
New-Item -ItemType Directory -Force $out, $work | Out-Null
$ignore = Join-Path $out '.gitignore'
if (-not (Test-Path $ignore)) { Set-Content -Encoding ascii $ignore "*`n!.gitignore" }

foreach ($tool in 'ffmpeg', 'ffprobe') {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) { throw "$tool not found on PATH" }
}

$rates = @(
    @{ Name = '23.976'; Rate = '24000/1001' }, @{ Name = '24'; Rate = '24/1' },
    @{ Name = '25'; Rate = '25/1' }, @{ Name = '29.97'; Rate = '30000/1001' },
    @{ Name = '50'; Rate = '50/1' }, @{ Name = '59.94'; Rate = '60000/1001' },
    @{ Name = '60'; Rate = '60/1' }, @{ Name = 'vfr'; Rate = '60/1' }
)
$sizes = @(@{ Name = '1080p'; W = 1920; H = 1080; Scale = 1 }, @{ Name = '2160p'; W = 3840; H = 2160; Scale = 2 })
$ranges = @('sdr', 'hdr')

function RateValue([string]$r) { $p = $r.Split('/'); [double]$p[0] / [double]$p[1] }
function FfPath([string]$p) { ($p -replace '\\', '/') -replace ':', '\:' }

# Pattern image (gray16 PNG, (W+P) x H), generated once per size/range/period.
function Get-Pattern([int]$w, [int]$h, [int]$period, [string]$range) {
    $file = Join-Path $work "pattern-$range-${w}x$h-p$period.png"
    if (Test-Path $file) { return $file }
    $white = if ($range -eq 'hdr') { 38011 } else { 65535 }   # HDR: PQ 0.58 ~ 203 nits
    $half = $period / 2
    $expr = "if(lt(Y,H/2),if(lt(mod(X,$period),$half),$white,0),if(eq(lt(mod(X,$period),$half),lt(mod(Y,$period),$half)),$white,0))"
    & ffmpeg -hide_banner -loglevel error -y -f lavfi -i "color=black:s=$($w + $period)x${h},format=gray16le" `
        -vf "geq=lum='$expr'" -frames:v 1 $file
    if ($LASTEXITCODE -ne 0) { throw "pattern generation failed: $file" }
    return $file
}

function New-Clip($rate, $size, [string]$range, [string]$file) {
    $fps = RateValue $rate.Rate
    $vfr = $rate.Name -eq 'vfr'
    # Integer px/frame (~480 px/s at 1080p), period = 16 steps, frame count a multiple of 16 (300 for VFR).
    $step = [int][math]::Round(480 / $fps) * $size.Scale
    $period = 16 * $step
    # VFR: whole 60/48 blocks (300 source frames) AND whole pattern periods (16 steps) => lcm = 1200.
    $unit = if ($vfr) { 1200 } else { 16 }
    $frames = $unit * [int][math]::Round($fps * $Seconds / $unit)
    $duration = $frames / $fps
    $pattern = Get-Pattern $size.W $size.H $period $range

    $font = FfPath 'C:\Windows\Fonts\consola.ttf'
    $fontSize = 40 * $size.Scale
    $textColor = if ($range -eq 'hdr') { '0x949494' } else { 'white' }
    $label = "$($rate.Name) fps  $range  $($size.Name)"
    $matrix = if ($range -eq 'hdr') { 'bt2020nc' } else { 'bt709' }

    $graph = @(
        "[0:v]crop=$($size.W):$($size.H):x='mod(n*$step,$period)':y=0,"
        "drawtext=fontfile='$font':fontsize=${fontSize}:fontcolor=${textColor}:box=1:boxcolor=black:boxborderw=$(10 * $size.Scale):x=40*$($size.Scale):y=h/2-th/2:text='F %{frame_num}   %{pts\:hms}   $label',"
        "format=yuv420p10le,"
        $(if ($range -eq 'hdr') { 'setparams=range=tv:color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc' }
          else { 'setparams=range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709' })
    ) -join ''
    if ($vfr) {
        # Drop every 5th frame in every other 5 s block: 60 fps / 48 fps alternating.
        $graph += ",select='not(eq(mod(floor(n/300),2),1)*eq(mod(n,5),0))'"
    }
    $graph += "[v]"
    $graphFile = Join-Path $work "graph-$([IO.Path]::GetFileNameWithoutExtension($file)).txt"
    Set-Content -Encoding ascii $graphFile $graph

    $audio = "aevalsrc='if(lt(mod(t,1),0.05),0.05*sin(2*PI*440*t),0)|if(lt(mod(t,1),0.05),0.05*sin(2*PI*440*t),0)':s=48000:d=$duration"
    $x265 = 'repeat-headers=1:log-level=error'
    $colorArgs = @('-color_range', 'tv', '-colorspace', $matrix)
    if ($range -eq 'hdr') {
        $x265 += ':hdr10=1:hdr10-opt=1:colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc' +
            ':master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,1)' +
            ':max-cll=203,203'
        $colorArgs += @('-color_primaries', 'bt2020', '-color_trc', 'smpte2084')
    } else {
        $x265 += ':colorprim=bt709:transfer=bt709:colormatrix=bt709'
        $colorArgs += @('-color_primaries', 'bt709', '-color_trc', 'bt709')
    }
    $ffArgs = @('-hide_banner', '-loglevel', 'error', '-stats', '-y',
        '-loop', '1', '-framerate', $rate.Rate, '-i', $pattern,
        '-f', 'lavfi', '-i', $audio,
        '-/filter_complex', $graphFile, '-map', '[v]', '-map', '1:a',
        '-frames:v', $frames, '-c:v', 'libx265', '-preset', 'ultrafast', '-crf', '16',
        '-profile:v', 'main10', '-x265-params', $x265) + $colorArgs +
        @('-c:a', 'aac', '-b:a', '128k', '-t', $duration)
    if ($vfr) { $ffArgs += @('-fps_mode', 'vfr') } else { $ffArgs += @('-r', $rate.Rate) }
    $ffArgs += $file
    $tmp = "$file.part.mkv"
    $ffArgs[-1] = $tmp
    & ffmpeg @ffArgs
    if ($LASTEXITCODE -ne 0) { throw "encode failed: $file" }
    Move-Item -Force $tmp $file
}

function Test-Clip($rate, $size, [string]$range, [string]$file) {
    $problems = @()
    $json = & ffprobe -v error -select_streams v:0 -count_packets -show_streams -show_format -of json $file | ConvertFrom-Json
    # Seamless loop: the pan moves 1 step per SOURCE frame and a period is 16 steps, so the source frame count
    # must be a multiple of 16. CFR: source frames = packets. VFR: source is 60 fps => duration x 60.
    $s0 = $json.streams[0]
    $sourceFrames = if ($rate.Name -eq 'vfr') {
        # last frame's source index = round(pts x 60) (ms-rounded pts); the design keeps the last source frame.
        $lastPts = & ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -of csv=p=0 -read_intervals '99999%' $file |
            ForEach-Object { [double]$_ } | Sort-Object | Select-Object -Last 1
        [int][math]::Round($lastPts * 60) + 1
    } else { [int]$s0.nb_read_packets }
    if ($sourceFrames % 16 -ne 0) { $problems += "loop not seamless: $sourceFrames source frames (not a multiple of 16)" }
    $s = $json.streams[0]
    if ($s.codec_name -ne 'hevc') { $problems += "codec $($s.codec_name)" }
    if ($s.profile -ne 'Main 10') { $problems += "profile $($s.profile)" }
    if ($s.pix_fmt -ne 'yuv420p10le') { $problems += "pix_fmt $($s.pix_fmt)" }
    if ($s.width -ne $size.W -or $s.height -ne $size.H) { $problems += "size $($s.width)x$($s.height)" }
    $wantPrim = if ($range -eq 'hdr') { 'bt2020' } else { 'bt709' }
    $wantTrc = if ($range -eq 'hdr') { 'smpte2084' } else { 'bt709' }
    if ($s.color_primaries -ne $wantPrim) { $problems += "primaries $($s.color_primaries)" }
    if ($s.color_transfer -ne $wantTrc) { $problems += "transfer $($s.color_transfer)" }
    $dur = [double]$json.format.duration
    if ($dur -lt 120 -or $dur -gt 180) { $problems += "duration $dur" }
    # Matroska stores frame duration in whole ns, so 1000/1001 rates can't be exact (59.94 -> 19001/317):
    # accept within 0.01 % of the nominal rate.
    if ($rate.Name -ne 'vfr' -and [math]::Abs((RateValue $s.r_frame_rate) / (RateValue $rate.Rate) - 1) -gt 1e-4) {
        $problems += "r_frame_rate $($s.r_frame_rate)"
    }
    if ($range -eq 'hdr') {
        $sd = (& ffprobe -v error -select_streams v:0 -read_intervals '%+#1' -show_frames -show_entries frame=side_data_list -of json $file |
            ConvertFrom-Json).frames[0].side_data_list.side_data_type
        foreach ($t in 'Mastering display metadata', 'Content light level metadata') {
            if ($sd -notcontains $t) { $problems += "missing side data '$t'" }
        }
    }
    if ($rate.Name -eq 'vfr') {
        $pts = & ffprobe -v error -select_streams v:0 -read_intervals '%+12' -show_entries packet=pts_time -of csv=p=0 $file |
            ForEach-Object { [double]$_ } | Sort-Object
        $d = for ($i = 1; $i -lt $pts.Count; $i++) { [math]::Round(($pts[$i] - $pts[$i - 1]) * 1000) }
        # 60 fps -> 16/17 ms, 48 fps pattern -> 33/34 ms gaps; need both families.
        if (-not (($d | Where-Object { $_ -le 18 }) -and ($d | Where-Object { $_ -ge 30 }))) {
            $problems += "VFR clip has no mixed frame durations ($(( $d | Sort-Object -Unique) -join ','))"
        }
    }
    return $problems
}

$failed = 0
$sw = [Diagnostics.Stopwatch]::StartNew()
foreach ($size in $sizes) { foreach ($range in $ranges) { foreach ($rate in $rates) {
    $name = "$range-$($size.Name)-$($rate.Name)"
    if ($name -notlike $Only) { continue }
    $file = Join-Path $out "$name.mkv"
    if (-not $CheckOnly -and ($Force -or -not (Test-Path $file))) {
        Write-Host "==> encoding $name"
        New-Clip $rate $size $range $file
    }
    if (-not (Test-Path $file)) { Write-Host "MISSING $name"; $failed++; continue }
    $p = Test-Clip $rate $size $range $file
    if ($p.Count) { Write-Host "FAIL $name : $($p -join '; ')"; $failed++ } else { Write-Host "OK   $name" }
}}}
Write-Host ("done in {0:n0} s, {1} failed" -f $sw.Elapsed.TotalSeconds, $failed)
exit ([int]($failed -gt 0))
