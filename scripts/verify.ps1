<#
.SYNOPSIS
  Deterministic checks for the refresh-rate-matching patch. Exits non-zero on any failure.

.DESCRIPTION
  -Fast : incremental compile (Kotlin desktop + native bridge) and the patch's unit tests.
          Meant to run automatically after edits (Claude Code hook). Target: under ~1 minute warm.
  -Full : clean build of the native bridge and the desktop app, ALL desktop unit tests,
          and a diff-size report against upstream (merge-base with upstream/Dev).

.PARAMETER Tests
  Gradle --tests filter for -Fast. Defaults to the patch's test package; falls back to the
  whole desktopTest suite if the package has no tests yet.

.EXAMPLE
  pwsh -File scripts/verify.ps1 -Fast
  pwsh -File scripts/verify.ps1 -Full
#>
[CmdletBinding(DefaultParameterSetName = 'Fast')]
param(
    [Parameter(ParameterSetName = 'Fast')][switch]$Fast,
    [Parameter(ParameterSetName = 'Full')][switch]$Full,
    [string]$Tests = 'com.nuvio.app.features.player.desktop.refreshrate.*,com.nuvio.app.fork.*',
    [string]$UpstreamRef = 'upstream/Dev'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

# --- Toolchain: JDK 17 (portable Temurin, see FORK.md) ---------------------------------------
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
    $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { Write-Error 'JDK 17 not found. Set JAVA_HOME or see FORK.md (toolchain).'; exit 2 }
    $env:JAVA_HOME = $jdk.FullName
}
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

$failures = [System.Collections.Generic.List[string]]::new()

# --- Isolate the tests from the OFFICIAL Nuvio profile ------------------------------------------
# Upstream storage resolves %APPDATA% / %LOCALAPPDATA% at runtime (DesktopStorage.kt,
# ContinueWatchingEnrichmentStorage.desktop.kt), and some desktopTests write through it. Found
# 2026-09-28: a -Full run rewrote %APPDATA%\Nuvio\nuvio_continue_watching_enrichment.properties.
# Gradle hands the client's environment to its daemon and test workers, so redirecting here
# covers every Gradle call below. The official folders are then checked for writes at the end.
$officialDirs = @((Join-Path $env:APPDATA 'Nuvio'), (Join-Path $env:LOCALAPPDATA 'Nuvio'))
$verifyStart = Get-Date
$testProfile = Join-Path (Split-Path -Parent $repo) 'testprofile'
$savedAppData = $env:APPDATA; $savedLocalAppData = $env:LOCALAPPDATA   # restored before exit (caller's shell)
$env:APPDATA = Join-Path $testProfile 'Roaming'
$env:LOCALAPPDATA = Join-Path $testProfile 'Local'
New-Item -ItemType Directory -Force $env:APPDATA, $env:LOCALAPPDATA | Out-Null

function Invoke-Gradle([string[]]$GradleArgs, [string]$Label) {
    Write-Host "==> $Label" -ForegroundColor Cyan
    Write-Host "    gradlew $($GradleArgs -join ' ')"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & "$repo\gradlew.bat" @GradleArgs --console=plain -q --warning-mode=none --no-configuration-cache
    $code = $LASTEXITCODE
    $sw.Stop()
    if ($code -ne 0) {
        $failures.Add("$Label (exit $code)")
        Write-Host "    FAIL ($code) in $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Red
    } else {
        Write-Host "    OK in $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Green
    }
}

# Runs desktopTest. A red Gradle run is tolerated ONLY when every failing test is listed in
# scripts/known-upstream-test-failures.txt (pre-existing upstream failures). Anything else fails.
function Invoke-Tests([string[]]$GradleArgs, [string]$Label) {
    Write-Host "==> $Label" -ForegroundColor Cyan
    Write-Host "    gradlew $($GradleArgs -join ' ')"
    $start = Get-Date
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $out = & "$repo\gradlew.bat" @GradleArgs --console=plain -q --warning-mode=none --no-configuration-cache 2>&1
    $code = $LASTEXITCODE
    $sw.Stop()
    $summary = $out | Select-String 'tests completed' | Select-Object -First 1
    if ($summary) { Write-Host "    $summary" }
    if ($code -eq 0) { Write-Host "    OK in $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Green; return }

    $known = Get-Content (Join-Path $PSScriptRoot 'known-upstream-test-failures.txt') |
        Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object { $_.Trim() }
    $resultsDir = Join-Path $repo 'composeApp/build/test-results/desktopTest'
    $failed = @()
    Get-ChildItem $resultsDir -Filter 'TEST-*.xml' -ErrorAction SilentlyContinue |
        Where-Object { $_.LastWriteTime -ge $start.AddSeconds(-2) } | ForEach-Object {
            foreach ($tc in ([xml](Get-Content -Raw -LiteralPath $_.FullName)).SelectNodes('//testcase')) {
                if ($tc.failure -or $tc.error) { $failed += "$($tc.classname)#$($tc.name)" }
            }
        }
    if ($failed.Count -eq 0) {
        # Red Gradle run but no failed test cases: compile error or infrastructure failure.
        $out | Select-Object -Last 25 | ForEach-Object { Write-Host "    $_" }
        $failures.Add("$Label (exit $code, no test results: build error?)")
        Write-Host "    FAIL ($code)" -ForegroundColor Red
        return
    }
    $new = $failed | Where-Object { $_ -notin $known }
    $failed | Where-Object { $_ -in $known } | ForEach-Object { Write-Host "    known upstream failure: $_" -ForegroundColor DarkYellow }
    if ($new) {
        $new | ForEach-Object { Write-Host "    NEW FAILURE: $_" -ForegroundColor Red }
        $failures.Add("$Label ($(@($new).Count) new failing test(s))")
        Write-Host "    FAIL in $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Red
    } else {
        Write-Host "    OK (only known upstream failures) in $([int]$sw.Elapsed.TotalSeconds)s" -ForegroundColor Green
    }
}

function Test-PatchTestsExist {
    $dir = Join-Path $repo 'composeApp/src/desktopTest/kotlin/com/nuvio/app/features/player/desktop/refreshrate'
    return (Test-Path $dir) -and (Get-ChildItem $dir -Recurse -Filter '*.kt' | Measure-Object).Count -gt 0
}

function Write-DiffReport {
    Write-Host '==> Diff vs upstream' -ForegroundColor Cyan
    $base = (git merge-base HEAD $UpstreamRef 2>$null)
    if (-not $base) { $failures.Add("diff report: cannot find merge-base with $UpstreamRef"); return }
    Write-Host "    base: $UpstreamRef @ $($base.Substring(0, 10))"

    # Paths that are patch infrastructure (docs, tooling), reported separately from product code.
    $infra = '^(SPEC\.md|PROGRESS\.md|FORK\.md|docs/|scripts/|\.claude/|measurements/|patches/)'

    $rows = @()
    # Tracked changes (committed + staged + unstaged) vs base.
    foreach ($line in (git diff --numstat $base -- . 2>$null)) {
        $p = $line -split "`t"
        if ($p.Count -lt 3) { continue }
        git cat-file -e "${base}:$($p[2])" 2>$null
        $existsUpstream = ($LASTEXITCODE -eq 0)
        $rows += [pscustomobject]@{ Path = $p[2]; Added = [int]($p[0] -replace '-', '0'); Removed = [int]($p[1] -replace '-', '0'); Upstream = $existsUpstream }
    }
    # Untracked, non-ignored new files.
    foreach ($f in (git ls-files --others --exclude-standard 2>$null)) {
        $n = (Get-Content -LiteralPath $f -ErrorAction SilentlyContinue | Measure-Object -Line).Lines
        $rows += [pscustomobject]@{ Path = $f; Added = $n; Removed = 0; Upstream = $false }
    }

    $up = $rows | Where-Object { $_.Upstream }
    $newProduct = $rows | Where-Object { -not $_.Upstream -and $_.Path -notmatch $infra }
    $newInfra = $rows | Where-Object { -not $_.Upstream -and $_.Path -match $infra }
    $sum = { param($r) [int](($r | Measure-Object -Property Added -Sum).Sum) + [int](($r | Measure-Object -Property Removed -Sum).Sum) }

    Write-Host ("    upstream files touched : {0,3}   lines changed: {1}" -f @($up).Count, (& $sum $up))
    foreach ($r in $up) { Write-Host ("      M {0}  (+{1} -{2})" -f $r.Path, $r.Added, $r.Removed) }
    Write-Host ("    new product files      : {0,3}   lines: {1}" -f @($newProduct).Count, (& $sum $newProduct))
    foreach ($r in $newProduct) { Write-Host ("      A {0}  (+{1})" -f $r.Path, $r.Added) }
    Write-Host ("    new infra/doc files    : {0,3}   lines: {1}" -f @($newInfra).Count, (& $sum $newInfra))

    # Phase 8 (SPEC P8-1, owner Q42): every line added to an upstream file carries its hook tag, and the tagged lines
    # stay within the budget: 12 code lines (Q44: +H21), 5 strings.xml lines (badge switch), 6 build.gradle.kts lines.
    $budget = @{ code = 12; strings = 5; gradle = 6 }
    $count = @{ code = 0; strings = 0; gradle = 0 }
    $untagged = @()
    # Phase 9 (owner 2026-10-01: audit fixes in the fork only): upstream files listed in scripts/fork-fixes.txt
    # ("<path> | <audit ids>") may carry untagged fix lines; they are counted per file instead of against the budget.
    $fixList = @{}
    $fixFile = Join-Path $repo 'scripts/fork-fixes.txt'
    if (Test-Path $fixFile) {
        foreach ($fl in Get-Content $fixFile) {
            if ($fl -match '^\s*#' -or $fl -notmatch '\|') { continue }
            $parts = $fl -split '\|', 2
            $fixList[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    $fixLines = 0
    foreach ($r in $up) {
        $added = @(git diff -U0 $base -- $r.Path 2>$null | Where-Object { $_ -match '^\+' -and $_ -notmatch '^\+\+\+' })
        $isFix = $fixList.ContainsKey($r.Path)
        foreach ($l in $added) {
            if ($isFix -and $l -notmatch 'nuvio-rr fork hook H\d+') { $fixLines++; continue }
            if ($l -notmatch 'nuvio-rr fork hook H\d+') { $untagged += "$($r.Path): $($l.Trim())"; continue }
            $kind = if ($r.Path -like '*strings.xml') { 'strings' } elseif ($r.Path -like '*build.gradle.kts') { 'gradle' } else { 'code' }
            $count[$kind]++
        }
    }
    Write-Host ("    hook lines (budget)    : code {0}/{1}, strings {2}/{3}, gradle {4}/{5}" -f
        $count.code, $budget.code, $count.strings, $budget.strings, $count.gradle, $budget.gradle)
    Write-Host ("    audit fix lines        : {0} added in {1} listed file(s) (scripts/fork-fixes.txt)" -f $fixLines, @($up | Where-Object { $fixList.ContainsKey($_.Path) }).Count)
    foreach ($u in $untagged) { Write-Host "      untagged: $u" -ForegroundColor Red }
    if ($untagged) { $failures.Add("upstream diff: $($untagged.Count) added line(s) without a 'nuvio-rr fork hook Hn' tag") }
    foreach ($k in $budget.Keys) {
        if ($count[$k] -gt $budget[$k]) { $failures.Add("upstream diff: $k hook lines $($count[$k]) > budget $($budget[$k])") }
    }
}

$total = [Diagnostics.Stopwatch]::StartNew()

if ($Full) {
    Write-Host '### verify.ps1 -Full' -ForegroundColor Yellow
    # Clean native bridge + app outputs, then rebuild everything from scratch (no build cache).
    Invoke-Gradle @(':composeApp:clean') 'clean'
    Invoke-Gradle @(':composeApp:buildWindowsPlayerBridge', ':composeApp:compileKotlinDesktop',
        ':composeApp:desktopJar', '--no-build-cache', '--rerun-tasks') 'clean build: native bridge + desktop app'
    if ($failures.Count -eq 0) {
        Invoke-Tests @(':composeApp:desktopTest', '--no-build-cache') 'all desktop unit tests'
    }
    Write-DiffReport
} else {
    Write-Host '### verify.ps1 -Fast' -ForegroundColor Yellow
    # Upstream's buildWindowsPlayerBridge only runs when the DLL is missing (onlyIf !exists), so a
    # native edit would silently keep a stale DLL. Force a rebuild when any native source is newer.
    $dll = Join-Path $repo 'composeApp/build/native/windows/player_bridge.dll'
    if (Test-Path $dll) {
        $dllTime = (Get-Item $dll).LastWriteTime
        $newer = Get-ChildItem (Join-Path $repo 'composeApp/src/desktopMain/native/windows') -File |
            Where-Object { $_.Extension -in '.cpp', '.h', '.hpp' -and $_.LastWriteTime -gt $dllTime }
        if ($newer) {
            Write-Host "    native sources changed ($($newer.Name -join ', ')); forcing bridge rebuild"
            Remove-Item $dll -Force
        }
    }
    Invoke-Gradle @(':composeApp:buildWindowsPlayerBridge', ':composeApp:compileKotlinDesktop') 'incremental compile'
    if ($failures.Count -eq 0) {
        if (Test-PatchTestsExist) {
            $filters = @($Tests -split ',' | Where-Object { $_ } | ForEach-Object { '--tests', $_.Trim() })  # Phase 8: + com.nuvio.app.fork
            Invoke-Tests (@(':composeApp:desktopTest') + $filters) "unit tests ($Tests)"
        } else {
            Write-Host '    (no patch tests yet; running the whole desktopTest suite)'
            Invoke-Tests @(':composeApp:desktopTest') 'unit tests (all desktop)'
        }
    }
}

$total.Stop()
Write-Host ''
$touched = @($officialDirs | Where-Object { Test-Path $_ } | ForEach-Object { Get-ChildItem $_ -Recurse -File -ErrorAction SilentlyContinue } |
    Where-Object { $_.LastWriteTime -gt $verifyStart })
if ($touched.Count) {
    $failures.Add("official Nuvio profile written during verify: $($touched.FullName -join ', ')")
}

$env:APPDATA = $savedAppData; $env:LOCALAPPDATA = $savedLocalAppData

if ($failures.Count -gt 0) {
    Write-Host "VERIFY FAILED in $([int]$total.Elapsed.TotalSeconds)s:" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 1
}
Write-Host "VERIFY OK in $([int]$total.Elapsed.TotalSeconds)s" -ForegroundColor Green
exit 0
