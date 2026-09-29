<#
.SYNOPSIS
  Export the whole fork as one patch against its upstream base and prove it applies (SPEC P8-12).

.DESCRIPTION
  Writes <repo>\..\patches\nuvio-rr-<base8>-<head8>.patch = git diff <merge-base with upstream/Dev>..HEAD, product files,
  tests, fork scripts and docs; never measurements/ or testdata/ (evidence and generated clips). Then checks it with
  `git apply --check` in a temporary worktree at the base, and removes that worktree.
  Re-apply on a new upstream: see FORK.md §4 (rebase is the normal way; the patch is the fallback and an archive).

.PARAMETER UpstreamRef
  Default upstream/Dev.
#>
param([string]$UpstreamRef = 'upstream/Dev')
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $repo
Push-Location $repo
try {
    if (git status --porcelain -- composeApp scripts SPEC.md FORK.md fork-identity.properties) {
        throw 'uncommitted fork changes: commit first (the patch is HEAD only)'
    }
    $base = (git merge-base HEAD $UpstreamRef).Trim()
    if (-not $base) { throw "no merge-base with $UpstreamRef" }
    $head = (git rev-parse HEAD).Trim()
    $outDir = Join-Path $root 'patches'
    New-Item -ItemType Directory -Force $outDir | Out-Null
    $patch = Join-Path $outDir ("nuvio-rr-{0}-{1}.patch" -f $base.Substring(0, 8), $head.Substring(0, 8))
    # --output writes git's bytes as they are (a PowerShell pipe would re-encode and re-join the lines)
    git diff --binary --full-index --output=$patch $base $head -- . ':(exclude)measurements/**' ':(exclude)testdata/**'
    $files = @(git diff --name-only $base $head -- . ':(exclude)measurements/**' ':(exclude)testdata/**')
    Write-Host ("patch: {0} ({1} files, {2:n0} KB)" -f $patch, $files.Count, ((Get-Item $patch).Length / 1KB))

    $wt = Join-Path ([IO.Path]::GetTempPath()) ("nuvio-rr-apply-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    git worktree add --detach --quiet $wt $base
    try {
        git -C $wt apply --check $patch
        if ($LASTEXITCODE) { throw "git apply --check FAILED on $base" }
        Write-Host "git apply --check on $($base.Substring(0, 8)): OK"
    } finally {
        git worktree remove --force $wt
    }
} finally { Pop-Location }
