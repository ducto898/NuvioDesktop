# Claude Code PostToolUse hook: runs verify.ps1 -Fast after an edit to Kotlin/C++/Gradle sources.
# Reads the hook JSON from stdin. Exit 2 = feed the failure back to Claude; 0 = silent success.
$ErrorActionPreference = 'Stop'
$payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
$path = $payload.tool_input.file_path
if (-not $path -or $path -notmatch '\.(kt|kts|cpp|h|hpp)$') { exit 0 }

$out = & pwsh -NoProfile -File (Join-Path $PSScriptRoot 'verify.ps1') -Fast 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) {
    # Keep feedback short: the tail holds the failure summary.
    [Console]::Error.WriteLine("verify.ps1 -Fast FAILED after editing $path`n" + (($out -split "`n") | Select-Object -Last 60 | Out-String))
    exit 2
}
exit 0
