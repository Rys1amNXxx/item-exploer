$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runName = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$probeRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot "work\chunk-loading-restart\$runName"))
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'work\chunk-loading-restart')) + [IO.Path]::DirectorySeparatorChar
if (-not $probeRoot.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Probe escaped its dedicated directory.' }
if (Test-Path -LiteralPath $probeRoot) { throw "Probe directory already exists: $probeRoot" }
[IO.Directory]::CreateDirectory($probeRoot) | Out-Null
$summary = Join-Path $probeRoot 'verification-summary.txt'
function Record([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Write-Host $line
    Add-Content -LiteralPath $summary -Value $line -Encoding utf8
}
Set-Content -LiteralPath (Join-Path $probeRoot 'eula.txt') -Value 'eula=true' -Encoding ascii
@'
server-ip=127.0.0.1
server-port=25588
online-mode=false
level-name=world
level-seed=182026
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-tick-time=120000
sync-chunk-writes=true
'@ | Set-Content -LiteralPath (Join-Path $probeRoot 'server.properties') -Encoding ascii
function Run-Phase([string]$phase) {
    $relative = [IO.Path]::GetRelativePath($projectRoot, $probeRoot).Replace('\', '/')
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $projectRoot 'dev.ps1'), '--init-script', 'scripts/chunk-restart-test.init.gradle', 'runServer', "-PchunkRestartDirectory=$relative", "-PchunkRestartPhase=$phase", '--console=plain')
    $stdout = Join-Path $probeRoot "$phase-gradle.stdout.log"
    $stderr = Join-Path $probeRoot "$phase-gradle.stderr.log"
    $process = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Record "Started phase=$phase launcherPid=$($process.Id) world=$probeRoot"
    $deadline = [DateTime]::UtcNow.AddMinutes(8)
    while (-not $process.HasExited) {
        if ([DateTime]::UtcNow -gt $deadline) { throw "Probe timed out; inspect launcher PID $($process.Id). No unrelated process was terminated." }
        Start-Sleep -Milliseconds 500
        $process.Refresh()
    }
    $process.WaitForExit()
    Record "Exited phase=$phase exitCode=$($process.ExitCode)"
    $reportPath = Join-Path $probeRoot "chunk-restart-$phase.txt"
    if (-not (Test-Path -LiteralPath $reportPath)) { throw "Missing $phase report; inspect $stdout and $stderr" }
    $report = Get-Content -LiteralPath $reportPath -Raw
    $marker = if ($phase -eq 'seed') { 'SEED_PASS' } else { 'VERIFY_PASS' }
    if ($process.ExitCode -ne 0 -or $report -match '(?m)^FAIL ' -or $report -notmatch $marker) { throw "Probe phase $phase failed; inspect $reportPath" }
}
try {
    Run-Phase 'seed'
    Run-Phase 'verify'
    Record 'PASS separate JVM restored exactly nine ticking tickets across a chunk boundary with no players.'
    Record 'PASS vanilla furnace produced items away from spawn before and after restart; persisted portable pairing accessed local and NAS inventory from over 4,000 blocks away.'
    Record 'PASS boot restored one missing corner and removed absent-owner, out-of-range and non-ticking stale tickets; disabling released all live owner tickets.'
    Record 'ALL CHUNK RESTART PROBES PASSED'
} catch {
    Record "FAIL $($_.Exception.Message)"
    throw
}
