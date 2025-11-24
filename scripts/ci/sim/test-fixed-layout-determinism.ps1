<#
.SYNOPSIS
    Run two headless fixed-layout runs and assert persisted village artifacts match exactly.

.DESCRIPTION
    This script invokes the existing `run-scenario.ps1` twice using distinct server
    directories and identical `-Seed` and `-FixedLayout` parameters. After both
    runs complete it copies the `plugins/VillageOverhaul/villages/*.json` artifacts
    into per-run folders and compares file hashes to ensure deterministic placement
    artifacts across runs.

.EXAMPLE
    ./test-fixed-layout-determinism.ps1 -Seed 12345 -FixedLayoutCount 3
#>

param(
    [long]$Seed = 12345,
    [int]$FixedLayoutCount = 3,
    [int]$Ticks = 400,
    [string]$RunnerScript = "$PSScriptRoot/run-scenario.ps1",
    [string]$ServerDirBase = "test-server-fixed",
    [int]$Runs = 2
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Run-FixedLayoutRun($id) {
    $serverDir = "${ServerDirBase}-$id"
    Write-Host "== Run #$id -> server dir: $serverDir ==" -ForegroundColor Cyan

    # Ensure server dir is clean and bootstrap from baseline `test-server` if available
    if (Test-Path $serverDir) { Remove-Item -LiteralPath $serverDir -Recurse -Force -ErrorAction SilentlyContinue }
    # If baseline test-server exists, copy it to create isolated run dirs
    $baseline = Join-Path (Resolve-Path .).Path 'test-server'
    if (Test-Path $baseline) {
        Write-Host "Bootstrapping $serverDir from baseline test-server" -ForegroundColor DarkGray
        New-Item -ItemType Directory -Path $serverDir -Force | Out-Null
        Get-ChildItem -Path $baseline -Force | ForEach-Object {
            Copy-Item -Path $_.FullName -Destination $serverDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    } else {
        New-Item -ItemType Directory -Path $serverDir | Out-Null
    }

    # Remove any pre-existing persisted village artifacts so runs are isolated
    $villageDataPath = Join-Path $serverDir 'plugins\VillageOverhaul\villages'
    if (Test-Path $villageDataPath) {
        Remove-Item -Path $villageDataPath -Recurse -Force -ErrorAction SilentlyContinue
    }

    # Run the main scenario harness in fixed-layout mode
    $runnerArgs = @(
        "-ServerDir", $serverDir,
        "-Ticks", $Ticks,
        "-Seed", $Seed,
        "-FixedLayout",
        "-FixedLayoutCount", $FixedLayoutCount,
        "-SnapshotFile", "snapshot_run${id}.json"
    )

    Write-Host "Starting run-scenario.ps1 for run #$id (seed=$Seed, count=$FixedLayoutCount)" -ForegroundColor Cyan
    Write-Host "DEBUG: Invoking run-scenario.ps1 via new PowerShell process" -ForegroundColor DarkGray
    # Capture child run output to avoid polluting our output pipeline
    $runOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $RunnerScript -ServerDir $serverDir -Ticks $Ticks -Seed $Seed -FixedLayout -FixedLayoutCount $FixedLayoutCount -SnapshotFile "snapshot_run${id}.json" 2>&1 | Out-String
    # Save run output for later inspection
    $logPath = Join-Path $serverDir "logs\run_${id}.log"
    New-Item -ItemType Directory -Path (Split-Path $logPath) -Force | Out-Null
    Set-Content -Path $logPath -Value $runOutput -Encoding UTF8

    # Collect artifacts from plugin data directory
    $artifactsDir = Join-Path $serverDir 'artifacts'
    if (!(Test-Path $artifactsDir)) { New-Item -ItemType Directory -Path $artifactsDir | Out-Null }

    $villageDir = Join-Path $serverDir 'plugins\VillageOverhaul\villages'
    if (Test-Path $villageDir) {
        Copy-Item -Path (Join-Path $villageDir '*.json') -Destination $artifactsDir -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "Warning: no persisted village artifacts found in $villageDir" -ForegroundColor Yellow
    }

    return [PSCustomObject]@{ ServerDir = $serverDir; ArtifactsDir = $artifactsDir }
}

Write-Host "Running fixed-layout determinism test: runs=$Runs seed=$Seed count=$FixedLayoutCount" -ForegroundColor Cyan
$runsInfo = @()
for ($i = 1; $i -le $Runs; $i++) {
    $runsInfo += Run-FixedLayoutRun $i
}

# Compare artifacts across runs
$runsInfo | ForEach-Object { Write-Host "DEBUG RUNSINFO ENTRY: $($_ | Out-String)" -ForegroundColor DarkGray }
Write-Host "Comparing artifacts across runs..." -ForegroundColor Cyan
$baseArtifacts = @(Get-ChildItem -Path $runsInfo[0].ArtifactsDir -Filter '*.json' -File) | Sort-Object Name
$mismatch = $false

for ($i = 1; $i -lt $Runs; $i++) {
    $nextArtifacts = @(Get-ChildItem -Path $runsInfo[$i].ArtifactsDir -Filter '*.json' -File) | Sort-Object Name

    # Quick name set comparison
    if ((@($baseArtifacts).Count -eq 0) -or (@($nextArtifacts).Count -eq 0)) {
        Write-Host "Warning: no artifacts found for run comparison (one or both runs produced no persisted artifacts)" -ForegroundColor Yellow
        $mismatch = $true
        continue
    }

    $namesA = @($baseArtifacts | ForEach-Object { $_.Name })
    $namesB = @($nextArtifacts | ForEach-Object { $_.Name })
    if ($namesA.Count -ne $namesB.Count -or -not ($namesA -ceq $namesB)) {
        Write-Host "Artifact filename set differs between run 1 and run $($i+1)" -ForegroundColor Red
        Write-Host "Run1 names: $($namesA -join ', ')" -ForegroundColor DarkGray
        Write-Host "Run$($i+1) names: $($namesB -join ', ')" -ForegroundColor DarkGray
        $mismatch = $true
        continue
    }

    # Compare content hashes for each file
    # T026d14: Normalize JSON by removing non-deterministic fields (timestamps) before comparison
    for ($j = 0; $j -lt $namesA.Count; $j++) {
        $fileA = Join-Path $baseArtifacts[$j].DirectoryName $namesA[$j]
        $fileB = Join-Path $nextArtifacts[$j].DirectoryName $namesB[$j]

        # Read and normalize JSON: remove recordedTimestamp field for deterministic comparison
        $contentA = Get-Content -Path $fileA -Raw
        $contentB = Get-Content -Path $fileB -Raw
        
        # Remove timestamp fields using regex (handles various JSON formats)
        $normalizedA = $contentA -replace '"recordedTimestamp"\s*:\s*[0-9]+\s*,?\s*', ''
        $normalizedB = $contentB -replace '"recordedTimestamp"\s*:\s*[0-9]+\s*,?\s*', ''
        
        # Compute hash of normalized content
        $bytesA = [System.Text.Encoding]::UTF8.GetBytes($normalizedA)
        $bytesB = [System.Text.Encoding]::UTF8.GetBytes($normalizedB)
        $sha = [System.Security.Cryptography.MD5]::Create()
        $hashA = [BitConverter]::ToString($sha.ComputeHash($bytesA)) -replace '-', ''
        $hashB = [BitConverter]::ToString($sha.ComputeHash($bytesB)) -replace '-', ''

        if ($hashA -ne $hashB) {
            Write-Host "Mismatch: $($namesA[$j]) differs between runs (md5A=$hashA md5B=$hashB)" -ForegroundColor Red
            Write-Host "  Normalized content differs - check for other non-deterministic fields" -ForegroundColor Yellow
            $mismatch = $true
        } else {
            Write-Host "Match: $($namesA[$j]) md5=$hashA (timestamp excluded)" -ForegroundColor Green
        }
    }
}

if ($mismatch) {
    Write-Host "FAILED: Determinism check failed - artifacts differ across runs" -ForegroundColor Red
    exit 2
} else {
    Write-Host "PASS: All persisted village artifacts are identical across runs" -ForegroundColor Green
    exit 0
}
