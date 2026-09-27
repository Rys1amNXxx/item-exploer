# Use JDK 17 for this invocation without changing global settings.
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @('build')
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$candidates = @($env:ITEM_EXPLORER_JAVA_HOME, $env:JAVA_HOME)
$searchRoots = @(
    (Join-Path $projectRoot '.tools'),
    (Join-Path $env:ProgramFiles 'Microsoft'),
    (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
    (Join-Path $env:ProgramFiles 'Java'),
    (Join-Path $env:USERPROFILE '.jdks')
)
foreach ($searchRoot in $searchRoots) {
    if (Test-Path -LiteralPath $searchRoot) {
        $candidates += Get-ChildItem -LiteralPath $searchRoot -Directory |
            Select-Object -ExpandProperty FullName
    }
}

$jdkPath = $null
foreach ($candidate in $candidates) {
    if (-not $candidate) { continue }
    $releaseFile = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $releaseFile) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe')) -and
        ((Get-Content -LiteralPath $releaseFile -Raw) -match '(?m)^JAVA_VERSION="17(?:\.|\")')) {
        $jdkPath = $candidate
        break
    }
}
if (-not $jdkPath) {
    throw 'JDK 17 was not found. Install JDK 17 or set ITEM_EXPLORER_JAVA_HOME to its directory.'
}

$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
$previousPath = $env:PATH
$gradleExitCode = 1
try {
    $env:JAVA_HOME = $jdkPath
    $env:PATH = (Join-Path $jdkPath 'bin') + [IO.Path]::PathSeparator + $previousPath
    $env:GRADLE_USER_HOME = Join-Path $projectRoot '.gradle-user-home'
    Write-Host "JDK: $jdkPath"
    Push-Location -LiteralPath $projectRoot
    try {
        & (Join-Path $projectRoot 'gradlew.bat') @GradleArguments
        $gradleExitCode = $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
    $env:PATH = $previousPath
}
exit $gradleExitCode
