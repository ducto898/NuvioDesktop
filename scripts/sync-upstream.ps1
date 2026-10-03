<#
.SYNOPSIS
  Sync the fork with upstream by MERGING upstream/Dev into feature/refresh-rate-matching (FORK.md section 4).

.DESCRIPTION
  Since 2026-10-03 the branch is public on origin, so upstream is merged in, never rebased (no force-push).
  Steps, each stops the script on failure:
    1. Preconditions: on the fork branch, no merge in progress, no uncommitted tracked changes.
    2. git fetch upstream; report the new upstream commits, release bumps, files both sides changed, hook files
       (lines tagged "nuvio-rr fork hook") and fork-fixes.txt files that upstream touched (check those fixes:
       if upstream fixed the same bug, drop the fork's fix).
    3. Trial merge (git merge-tree, changes nothing). Conflicts => list them and stop; resolve by hand (section 4/section 5).
       -DryRun stops here.
    4. Backup branch backup/pre-merge-<date>, then git merge --no-ff.
    5. Patch identity: the fork's +/- lines vs upstream must be the same before and after the merge.
    6. verify.ps1 -Full (skip with -SkipVerify).
    7. Two live runs (skip with -SkipLive): already-at-target at the 240 Hz default and a switch + restore under the
       measure-only cap -MaxHz 144, judged by rr-tools\p7-collect.py.
    8. package-fork.ps1 (new zip) and export-patch.ps1 (archive patch) (skip with -NoPackage).
  Never pushes. Undo a merge with: git reset --hard <backup branch>  (printed at the end).
  A report goes to measurements\sync-<stamp>.txt (gitignored with the other measure output).

.PARAMETER Target
  What to merge. Default: the upstream ref itself. Give a release commit (e.g. the "chore(store): publish x.y.z"
  one) to sync to a release instead of the tip.

.PARAMETER Trailer
  Extra lines appended to the merge commit message (e.g. Co-Authored-By).

.EXAMPLE
  pwsh -File scripts\sync-upstream.ps1 -DryRun
  pwsh -File scripts\sync-upstream.ps1
  pwsh -File scripts\sync-upstream.ps1 -Target 7be1b56c -SkipLive
#>
param(
    [string]$UpstreamRef = 'upstream/Dev',
    [string]$Target = '',
    [string]$Branch = 'feature/refresh-rate-matching',
    [switch]$DryRun,
    [switch]$SkipVerify,
    [switch]$SkipLive,
    [switch]$NoPackage,
    [string]$Clip = 'sdr-1080p-23.976',
    [int]$Seconds = 25,
    [string[]]$Trailer = @()
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$report = Join-Path $repo "measurements\sync-$stamp.txt"
New-Item -ItemType Directory -Force (Split-Path $report) | Out-Null
function Say([string]$text, [string]$color = '') {
    if ($color) { Write-Host $text -ForegroundColor $color } else { Write-Host $text }
    Add-Content -LiteralPath $report -Value $text
}
function Stop-Sync([string]$why, [int]$code = 1) { Say "SYNC STOPPED: $why" Red; Say "report: $report"; exit $code }
function Invoke-Git { $out = & git @args 2>&1; if ($LASTEXITCODE) { throw "git $($args -join ' ') failed: $out" }; $out }

# --- 1. Preconditions -------------------------------------------------------------------------------------------
$current = (Invoke-Git rev-parse --abbrev-ref HEAD).Trim()
if ($current -ne $Branch) { Stop-Sync "on '$current', expected '$Branch'" }
if (Test-Path (Join-Path $repo '.git\MERGE_HEAD')) { Stop-Sync 'a merge is already in progress (finish or git merge --abort)' }
if (git status --porcelain --untracked-files=no) { Stop-Sync 'uncommitted tracked changes: commit or stash first' }

# --- 2. Fetch and report ----------------------------------------------------------------------------------------
Say "==> fetch $UpstreamRef" Cyan
$remote = ($UpstreamRef -split '/', 2)[0]
git fetch $remote 2>&1 | Out-Null
if ($LASTEXITCODE) { Stop-Sync "git fetch $remote failed" }
if (-not $Target) { $Target = $UpstreamRef }
$targetSha = (Invoke-Git rev-parse "$Target^{commit}").Trim()
$oldHead = (Invoke-Git rev-parse HEAD).Trim()
$oldBase = (Invoke-Git merge-base HEAD $targetSha).Trim()
$new = @(git log --oneline "$oldBase..$targetSha")
if ($new.Count -eq 0) { Say "already up to date with $Target ($($targetSha.Substring(0, 8)))" Green; Remove-Item $report; exit 0 }
Say "fork HEAD $($oldHead.Substring(0, 8)), base $($oldBase.Substring(0, 8)) -> target $($targetSha.Substring(0, 8)): $($new.Count) upstream commits"
$releases = @($new | Where-Object { $_ -match 'chore\(store\): publish' })
if ($releases) { Say "releases: $(($releases | ForEach-Object { ($_ -split 'publish ', 2)[1] }) -join ', ')" }

$upFiles = @(git diff --name-only $oldBase $targetSha)
$forkFiles = @(git diff --name-only $oldBase $oldHead)
$overlap = @($upFiles | Where-Object { $forkFiles -contains $_ } | Sort-Object)
Say ("upstream changed {0} files; {1} also changed by the fork:" -f $upFiles.Count, $overlap.Count)
$overlap | ForEach-Object { Say "  $_" }

$hookFiles = @(git grep -l 'nuvio-rr fork hook' HEAD -- . ':(exclude)scripts/**' ':(exclude)docs/**' ':(exclude)*.md' |
    ForEach-Object { ($_ -split ':', 2)[1] })
$hookHit = @($hookFiles | Where-Object { $upFiles -contains $_ })
if ($hookHit) { Say "hook files upstream touched (check the hook still sits in the right place, FORK.md section 5):" Yellow; $hookHit | ForEach-Object { Say "  $_" } }

$fixFile = Join-Path $repo 'scripts\fork-fixes.txt'
$fixHit = @()
if (Test-Path $fixFile) {
    foreach ($fl in Get-Content $fixFile) {
        if ($fl -match '^\s*#' -or $fl -notmatch '\|') { continue }
        $p = $fl -split '\|', 2
        if ($upFiles -contains $p[0].Trim()) { $fixHit += "$($p[0].Trim())  [$($p[1].Trim())]" }
    }
}
if ($fixHit) { Say 'fork-fixes files upstream touched (did upstream fix the same bug? then drop the fork fix):' Yellow; $fixHit | ForEach-Object { Say "  $_" } }

# --- 3. Trial merge ---------------------------------------------------------------------------------------------
Say '==> trial merge' Cyan
$trial = @(git merge-tree --write-tree --name-only HEAD $targetSha 2>&1)
if ($LASTEXITCODE -eq 1) {
    $conflicts = @($trial | Select-Object -Skip 1 | Where-Object { $_ -and $_ -notmatch '^(Auto-merging|CONFLICT)' } | Sort-Object -Unique)
    Say 'conflicts:' Red; $conflicts | ForEach-Object { Say "  $_" }
    Stop-Sync 'trial merge has conflicts; nothing changed. Merge by hand (FORK.md section 4), then run verify.ps1 -Full.' 3
} elseif ($LASTEXITCODE) { Stop-Sync "git merge-tree failed: $trial" }
Say 'no conflicts' Green
if ($DryRun) { Say "dry run: nothing changed. report: $report"; exit 0 }

# --- 4. Backup + merge ------------------------------------------------------------------------------------------
$backup = "backup/pre-merge-$(Get-Date -Format 'yyyy-MM-dd')"
$n = 1
while (git rev-parse --verify --quiet "refs/heads/$backup" 2>$null) { $n++; $backup = "backup/pre-merge-$(Get-Date -Format 'yyyy-MM-dd')-$n" }
Invoke-Git branch $backup $oldHead | Out-Null
Say "backup branch: $backup"
$version = if ($releases) { " ($((($releases[0]) -split 'publish ', 2)[1]))" } else { '' }
$msg = @("Merge $Target $($targetSha.Substring(0, 8))$version into the fork", '',
    "$($new.Count) upstream commits; $($overlap.Count) overlapping files, 0 conflicts (scripts/sync-upstream.ps1).")
if ($Trailer) { $msg += ''; $msg += $Trailer }
$msgFile = Join-Path ([IO.Path]::GetTempPath()) "nuvio-sync-msg-$stamp.txt"
[IO.File]::WriteAllText($msgFile, ($msg -join "`n") + "`n")
git merge --no-ff --no-edit -F $msgFile $targetSha 2>&1 | Out-Null
$mergeExit = $LASTEXITCODE
Remove-Item $msgFile
if ($mergeExit) { git merge --abort 2>$null; Stop-Sync "git merge failed (aborted; HEAD is still $($oldHead.Substring(0, 8)))" }
$newHead = (Invoke-Git rev-parse HEAD).Trim()
Say "merged: $($newHead.Substring(0, 8))"
$undo = "undo: git reset --hard $backup"

# --- 5. Patch identity ------------------------------------------------------------------------------------------
Say '==> patch identity' Cyan
function Get-PatchLines($from, $to) {
    @(git diff $from $to | Where-Object { $_ -match '^[+-]' -and $_ -notmatch '^(\+\+\+|---) ' })
}
$before = Get-PatchLines $oldBase $oldHead
$after = Get-PatchLines $targetSha $newHead
$delta = @(Compare-Object $before $after -SyncWindow 0 -CaseSensitive)
if ($delta.Count) {
    $delta | Select-Object -First 20 | ForEach-Object { Say "  $($_.SideIndicator) $($_.InputObject)" }
    Stop-Sync "the fork's changes differ after the merge ($($delta.Count) lines). $undo"
}
Say "identical: $($before.Count) changed lines" Green

# --- 6. Verify --------------------------------------------------------------------------------------------------
if (-not $SkipVerify) {
    Say '==> verify.ps1 -Full' Cyan
    $vlog = Join-Path $repo "measurements\sync-$stamp-verify.txt"
    & (Join-Path $PSScriptRoot 'verify.ps1') -Full *> $vlog
    $vexit = $LASTEXITCODE
    Get-Content $vlog -Tail 15 | ForEach-Object { Say "  $_" }
    if ($vexit) { Stop-Sync "verify -Full failed (log $vlog). Fix, or $undo" }
    Say 'verify green' Green
}

# --- 7. Live runs -----------------------------------------------------------------------------------------------
if (-not $SkipLive) {
    Say '==> live runs' Cyan
    $measure = Join-Path $PSScriptRoot 'measure.ps1'
    $runs = @()
    foreach ($r in @(@{ Label = 'sync-240'; Max = 0 }, @{ Label = 'sync-cap144'; Max = 144 })) {
        $splat = @{ Clip = $Clip; Seconds = $Seconds; Feature = $true; Label = $r.Label }
        if ($r.Max) { $splat.MaxHz = $r.Max }
        & $measure @splat *> (Join-Path ([IO.Path]::GetTempPath()) "nuvio-sync-$($r.Label).txt")
        $dir = Get-ChildItem (Join-Path $repo 'measurements') -Directory -Filter "*-$Clip-$($r.Label)" |
            Sort-Object Name -Descending | Select-Object -First 1
        if (-not $dir) { Stop-Sync "live run $($r.Label) left no folder. $undo" }
        $runs += $dir.FullName
    }
    $judge = @(python (Join-Path $PSScriptRoot 'rr-tools\p7-collect.py') judge @runs 2>&1)
    $jexit = $LASTEXITCODE
    $judge | ForEach-Object { Say "  $_" }
    if ($jexit) { Stop-Sync "live runs failed the judge. $undo" }
    Say 'live runs PASS' Green
}

# --- 8. Package -------------------------------------------------------------------------------------------------
if (-not $NoPackage) {
    Say '==> package + patch archive' Cyan
    foreach ($s in 'package-fork.ps1', 'export-patch.ps1') {
        $out = @(& (Join-Path $PSScriptRoot $s) 2>&1)
        $sexit = $LASTEXITCODE
        $out | Where-Object { $_ -match '^(zip|patch|app folder|OK|apply)' -or $_ -match '\.(zip|patch)\b' } | ForEach-Object { Say "  $_" }
        if ($sexit) { Stop-Sync "$s failed. The merge is fine; re-run $s by hand." }
    }
}

Say ''
Say "SYNC DONE: $($oldHead.Substring(0, 8)) -> $($newHead.Substring(0, 8)) ($($new.Count) upstream commits). Not pushed." Green
Say $undo
Say "Next: one line in PROGRESS.md Log; check the fork-fixes/hook lists above. Report: $report"
