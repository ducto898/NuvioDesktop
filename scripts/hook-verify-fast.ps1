# Claude Code PostToolUse hook: runs verify.ps1 -Fast after an edit to Kotlin/C++/Gradle sources.
# Reads the hook JSON from stdin. Exit 2 = feed the failure back to Claude; 0 = silent success.
#
# verify runs in a ShellExecute'd process that inherits none of this hook's handles, output to a temp file.
# Reason (2026-09-28): when no Gradle daemon is running, gradlew starts one that inherits the hook's stdout pipe and
# outlives the hook, so Claude Code waits for EOF forever (a session hung 2 h). Hard limit below the 300 s hook timeout.
$ErrorActionPreference = 'Stop'
$payload = [Console]::In.ReadToEnd() | ConvertFrom-Json
$path = $payload.tool_input.file_path
if (-not $path -or $path -notmatch '\.(kt|kts|cpp|h|hpp)$') { exit 0 }

$log = Join-Path ([IO.Path]::GetTempPath()) "nuvio-rr-hook-$PID.log"
$verify = Join-Path $PSScriptRoot 'verify.ps1'
$psi = [Diagnostics.ProcessStartInfo]::new((Get-Process -Id $PID).Path)
$psi.Arguments = "-NoProfile -NonInteractive -Command `"& '$verify' -Fast *> '$log'; exit `$LASTEXITCODE`""
$psi.UseShellExecute = $true
$psi.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
$proc = [Diagnostics.Process]::Start($psi)

if (-not $proc.WaitForExit(270000)) {
    & taskkill /T /F /PID $proc.Id 2>&1 | Out-Null
    [Console]::Error.WriteLine("verify.ps1 -Fast TIMED OUT (270 s) after editing $path; killed. Run it by hand: scripts\verify.ps1 -Fast")
    exit 2
}
$out = if (Test-Path $log) { Get-Content $log -Raw } else { '' }
Remove-Item $log -ErrorAction SilentlyContinue
if ($proc.ExitCode -ne 0) {
    # Keep feedback short: the tail holds the failure summary.
    [Console]::Error.WriteLine("verify.ps1 -Fast FAILED after editing $path`n" + (($out -split "`n") | Select-Object -Last 60 | Out-String))
    exit 2
}
exit 0
