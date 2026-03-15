<#
.SYNOPSIS
    Run the fixed-layout headless scenario and enforce per-village path coverage.

.DESCRIPTION
    Wrapper around run-scenario.ps1 that enables deterministic fixed-layout mode,
    stops once PATH-COVERAGE lines appear, and enforces a minimum coverage
    threshold suitable for CI.
#>

param(
    [string]$ServerDir = 'test-server',
    [int]$Ticks = 1000,
    [long]$Seed = 12345,
    [int]$FixedLayoutCount = 3,
    [int]$PathCoverageThresholdPct = 50,
    [string]$SnapshotFile = 'state-snapshot.json'
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..\..\..')).Path
$runScenarioPath = Join-Path $ScriptDir 'run-scenario.ps1'

$resolvedServerDir = if ([System.IO.Path]::IsPathRooted($ServerDir)) {
    $ServerDir
} else {
    Join-Path $RepoRoot $ServerDir
}

$resolvedSnapshotFile = if ([System.IO.Path]::IsPathRooted($SnapshotFile)) {
    $SnapshotFile
} else {
    Join-Path $RepoRoot $SnapshotFile
}

$previousCi = $env:CI
$env:CI = 'true'

try {
    Write-Host '=== Path Coverage Harness ===' -ForegroundColor Cyan
    Write-Host "Seed: $Seed" -ForegroundColor Gray
    Write-Host "Ticks: $Ticks" -ForegroundColor Gray
    Write-Host "FixedLayoutCount: $FixedLayoutCount" -ForegroundColor Gray
    Write-Host "PathCoverageThresholdPct: $PathCoverageThresholdPct" -ForegroundColor Gray

    $scenarioArgs = @{
        ServerDir = $resolvedServerDir
        Ticks = $Ticks
        Seed = $Seed
        SnapshotFile = $resolvedSnapshotFile
        FixedLayout = $true
        FixedLayoutCount = $FixedLayoutCount
        PathCoverageThresholdPct = $PathCoverageThresholdPct
        StopWhen = 'PATH-COVERAGE village='
    }

    & $runScenarioPath @scenarioArgs

    exit $LASTEXITCODE
} finally {
    if ($null -eq $previousCi) {
        Remove-Item Env:CI -ErrorAction SilentlyContinue
    } else {
        $env:CI = $previousCi
    }
}
