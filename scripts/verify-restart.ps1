param([switch]$CrashRecovery, [switch]$EnergyCapabilities)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$runName = Get-Date -Format 'yyyyMMdd-HHmmss'
$probeRoot = Join-Path $projectRoot "work\acceptance-restart\$runName"
$probeRoot = [IO.Path]::GetFullPath($probeRoot)
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'work\acceptance-restart')) + [IO.Path]::DirectorySeparatorChar
if (-not $probeRoot.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Probe path escaped its dedicated directory.' }
if (Test-Path -LiteralPath $probeRoot) { throw "Probe directory already exists: $probeRoot" }
[IO.Directory]::CreateDirectory($probeRoot) | Out-Null
$summary = Join-Path $probeRoot 'verification-summary.txt'
function Record([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Write-Host $line
    Add-Content -LiteralPath $summary -Value $line -Encoding utf8
}
function Prepare-World([string]$directory) {
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    Set-Content -LiteralPath (Join-Path $directory 'eula.txt') -Value 'eula=true' -Encoding ascii
    @'
server-ip=127.0.0.1
server-port=25587
online-mode=false
level-name=world
level-seed=170029
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-tick-time=120000
sync-chunk-writes=true
'@ | Set-Content -LiteralPath (Join-Path $directory 'server.properties') -Encoding ascii
}
function Run-Phase([string]$directory, [string]$phase, [bool]$killAfterFlush = $false) {
    $relative = [IO.Path]::GetRelativePath($projectRoot, $directory).Replace('\', '/')
    $stdout = Join-Path $directory "$phase-gradle.stdout.log"
    $stderr = Join-Path $directory "$phase-gradle.stderr.log"
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $projectRoot 'dev.ps1'), '--init-script', 'scripts/restart-test.init.gradle', 'runServer', "-PrestartProbeDirectory=$relative", "-PrestartProbePhase=$phase", '--console=plain')
    if ($EnergyCapabilities) { $arguments += '-PrestartProbeEnergy=true' }
    $process = Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    Record "Started phase=$phase launcherPid=$($process.Id) world=$directory"
    $deadline = [DateTime]::UtcNow.AddMinutes(8)
    $killed = $false
    while (-not $process.HasExited) {
        if ([DateTime]::UtcNow -gt $deadline) { throw "Probe timed out; inspect launcher PID $($process.Id). No unrelated process was terminated." }
        if ($killAfterFlush -and -not $killed) {
            $marker = Join-Path $directory 'restart-probe-crash-ready.pid'
            if (Test-Path -LiteralPath $marker) {
                $probePid = [int](Get-Content -LiteralPath $marker -Raw)
                $probeProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$probePid"
                if ($null -eq $probeProcess -or $probeProcess.Name -ne 'java.exe' -or $probeProcess.CommandLine -notmatch '-Ditemexplorer\.restartProbe=seed-crash') {
                    throw "PID $probePid is not the explicitly identified crash probe JVM; refusing termination."
                }
                $probeProcess.CommandLine | Set-Content -LiteralPath (Join-Path $directory 'terminated-process-command.txt') -Encoding utf8
                Stop-Process -Id $probePid -Force
                $killed = $true
                Record "Terminated only verified probe JVM pid=$probePid after its explicit flush-complete marker"
            }
        }
        Start-Sleep -Milliseconds 500
        $process.Refresh()
    }
    $process.WaitForExit()
    Record "Exited phase=$phase launcherPid=$($process.Id) exitCode=$($process.ExitCode)"
    $report = Get-Content -LiteralPath (Join-Path $directory "restart-probe-$phase.txt") -Raw
    if ($report -match '(?m)^FAIL ') { throw "Probe phase $phase reported failure." }
    if ($killAfterFlush) {
        if (-not $killed -or $report -notmatch 'CRASH_READY') { throw 'Crash phase did not terminate at the flush checkpoint.' }
    } else {
        $marker = if ($phase -eq 'seed') { 'SEED_PASS' } else { 'VERIFY_PASS' }
        if ($process.ExitCode -ne 0 -or $report -notmatch $marker) { throw "Probe phase $phase did not complete successfully." }
    }
}
try {
    $normal = Join-Path $probeRoot 'normal'
    Prepare-World $normal
    Run-Phase $normal 'seed'
    Run-Phase $normal 'verify'
    Record 'PASS normal shutdown and independent-JVM restart restored terminal, four disks and logistics configuration.'
    if ($EnergyCapabilities) { Record 'PASS native MEK ENERGY capabilities retained 1000/1100/1200/1300/1400 FE in the terminal and four disks after restart.' }
    if ($CrashRecovery) {
        $crash = Join-Path $probeRoot 'flushed-crash'
        Prepare-World $crash
        Run-Phase $crash 'seed-crash' $true
        Run-Phase $crash 'verify'
        Record 'PASS forced termination after completed save flush restored the committed snapshot in an independent JVM.'
        Record 'LIMIT: This does not verify writes after the last flush, a crash during flush, or power-loss durability.'
    }
    Record 'ALL REQUESTED RESTART PROBES PASSED'
} catch {
    Record "FAIL $($_.Exception.Message)"
    throw
}
