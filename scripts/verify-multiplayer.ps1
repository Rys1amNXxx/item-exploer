param([switch]$PrepareOnly, [string]$RunDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$allowedRoot = [IO.Path]::GetFullPath((Join-Path $projectRoot 'work\acceptance-multiplayer')) + [IO.Path]::DirectorySeparatorChar
$probeRoot = if ($RunDirectory) { [IO.Path]::GetFullPath($RunDirectory) } else { Join-Path $allowedRoot (Get-Date -Format 'yyyyMMdd-HHmmss') }
if (-not $probeRoot.StartsWith($allowedRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Probe path escaped its isolated directory.' }
$roles = @('server', 'DevA', 'DevB')
$processes = @{}
$summary = Join-Path $probeRoot 'verification-summary.txt'
function Record([string]$message) {
    $line = "$(Get-Date -Format o) $message"
    Write-Host $line
    Add-Content -LiteralPath $summary -Value $line -Encoding utf8
}
if (-not $RunDirectory) {
    if (Test-Path -LiteralPath $probeRoot) { throw 'Probe directory already exists.' }
    [IO.Directory]::CreateDirectory($probeRoot) | Out-Null
    foreach ($role in $roles) {
        $directory = Join-Path $probeRoot $role
        [IO.Directory]::CreateDirectory((Join-Path $directory 'config')) | Out-Null
        Set-Content -LiteralPath (Join-Path $directory 'config\fml.toml') -Value 'earlyWindowControl=false' -Encoding ascii
        if ($role -ne 'server') {
            @'
lang:zh_cn
renderDistance:2
simulationDistance:2
maxFps:30
pauseOnLostFocus:false
onboardAccessibility:false
skipMultiplayerWarning:true
soundCategory_master:0.0
'@ | Set-Content -LiteralPath (Join-Path $directory 'options.txt') -Encoding utf8
        }
    }
    Set-Content -LiteralPath (Join-Path $probeRoot 'server\eula.txt') -Value 'eula=true' -Encoding ascii
    @'
server-ip=127.0.0.1
server-port=25589
online-mode=false
enforce-secure-profile=false
level-name=world
level-seed=170029
level-type=minecraft:flat
generate-structures=false
spawn-protection=0
view-distance=2
simulation-distance=2
max-tick-time=120000
sync-chunk-writes=true
max-players=2
'@ | Set-Content -LiteralPath (Join-Path $probeRoot 'server\server.properties') -Encoding ascii
    foreach ($role in $roles) {
        & (Join-Path $projectRoot 'dev.ps1') --init-script scripts/multiplayer-test.init.gradle exportMultiplayerLaunch "-PmultiplayerProbeDirectory=$probeRoot" "-PmultiplayerProbeRole=$role" --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Cannot prepare multiplayer $role" }
    }
    Record 'PREPARED real server and two real client launches; no JVM launched yet.'
}
if ($PrepareOnly) { Write-Output "PREPARED_DIRECTORY=$probeRoot"; exit 0 }
if (Test-Path -LiteralPath (Join-Path $probeRoot 'server.pid')) { throw 'This prepared run was already started. Prepare a fresh directory instead.' }
function Start-Probe([string]$role) {
    $directory = Join-Path $probeRoot $role
    $launch = Get-Content -LiteralPath (Join-Path $directory 'launch.json') -Raw | ConvertFrom-Json
    $argumentFile = Join-Path $directory 'java-arguments.txt'
    $escaped = foreach ($argument in $launch.arguments) { '"' + ([string]$argument).Replace('\', '\\').Replace('"', '\"') + '"' }
    [IO.File]::WriteAllLines($argumentFile, $escaped, [Text.UTF8Encoding]::new($false))
    $previous = @{}
    try {
        foreach ($entry in $launch.environment.PSObject.Properties) {
            $previous[$entry.Name] = [Environment]::GetEnvironmentVariable($entry.Name, 'Process')
            [Environment]::SetEnvironmentVariable($entry.Name, [string]$entry.Value, 'Process')
        }
        $process = Start-Process -FilePath $launch.executable -ArgumentList ('@"' + $argumentFile + '"') -WorkingDirectory $launch.directory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $directory 'stdout.log') -RedirectStandardError (Join-Path $directory 'stderr.log')
        $processes[$role] = $process
        Record "START $role pid=$($process.Id)"
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}
try {
    Start-Probe 'server'
    $deadline = [DateTime]::UtcNow.AddMinutes(5)
    while (-not (Test-Path -LiteralPath (Join-Path $probeRoot 'server-ready'))) {
        $processes.server.Refresh()
        if ($processes.server.HasExited) { throw 'Server exited before its ready marker.' }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Server startup timed out.' }
        Start-Sleep -Milliseconds 500
    }
    Start-Probe 'DevA'
    Start-Probe 'DevB'
    $deadline = [DateTime]::UtcNow.AddMinutes(9)
    while ($true) {
        $alive = @($processes.Values | Where-Object { $_.Refresh(); -not $_.HasExited })
        if ($alive.Count -eq 0) { break }
        if ([DateTime]::UtcNow -gt $deadline) { throw 'Multiplayer probe timed out.' }
        Start-Sleep -Milliseconds 500
    }
    foreach ($role in $roles) {
        $processes[$role].WaitForExit()
        Record "EXIT $role pid=$($processes[$role].Id) code=$($processes[$role].ExitCode)"
        if ($processes[$role].ExitCode -ne 0) { throw "$role did not exit normally." }
    }
    if ((Get-Content -LiteralPath (Join-Path $probeRoot 'result') -Raw).Trim() -ne 'PASS') { throw 'Server assertions did not pass.' }
    foreach ($role in @('DevA', 'DevB')) {
        if (-not (Test-Path -LiteralPath (Join-Path $probeRoot "$role-exit"))) { throw "$role did not request a normal shutdown." }
    }
    Record 'PASS actual dedicated server and DevA/DevB Forge clients used TCP for contested withdrawal, stale replay, fresh retry and closed-menu rejection.'
    Record 'LIMIT: local loopback only, without injected delay; not a test of WAN latency or network quality.'
} catch {
    Record "FAIL $($_.Exception.Message)"
    Set-Content -LiteralPath (Join-Path $probeRoot 'abort') -Value 'Launcher requested normal shutdown' -Encoding ascii
    foreach ($process in $processes.Values) {
        $process.Refresh()
        if (-not $process.HasExited) {
            if (-not $process.WaitForExit(60000)) {
                $ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
                if ($ownedProcess -and $ownedProcess.CommandLine.Contains($probeRoot)) {
                    Record "FALLBACK terminating unresponsive owned probe pid=$($process.Id), after normal shutdown timeout."
                    Stop-Process -Id $process.Id -Force
                } else { Record "Cannot verify unresponsive PID $($process.Id); did not terminate an unverified process." }
            }
        }
    }
    throw
}
