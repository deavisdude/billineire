<#
.SYNOPSIS
    Test path generation determinism (T026d full implementation)

.DESCRIPTION
    Runs path generation twice with the same seed and once with a different seed.
    Compares path determinism hashes to verify reproducibility and variance.

.PARAMETER ServerDir
    Server directory (default: test-server)

.PARAMETER Ticks
    Number of ticks to simulate per run (default: 3000)

.PARAMETER SeedA
    First seed for determinism test (default: 12345)

.PARAMETER SeedB
    Second seed for variance test (default: 67890)

.EXAMPLE
    .\test-path-determinism.ps1
    
.EXAMPLE
    .\test-path-determinism.ps1 -Ticks 6000 -SeedA 99999 -SeedB 11111
#>

param(
    [string]$ServerDir = "test-server",
    [int]$Ticks = 600,
    [long]$SeedA = 12345,
    [long]$SeedB = 67890
)

$ErrorActionPreference = "Stop"

Write-Host "=== Path Determinism Test (T026d Full) ===" -ForegroundColor Cyan
Write-Host "Testing path generation reproducibility and variance" -ForegroundColor White
Write-Host "Seed A: $SeedA (run twice for determinism)" -ForegroundColor White
Write-Host "Seed B: $SeedB (run once for variance)" -ForegroundColor White
Write-Host ""

# Function to extract path hashes from log file
function Get-PathHashes {
    param([string]$logFile)
    
    if (-not (Test-Path $logFile)) {
        Write-Host "X Log file not found: $logFile" -ForegroundColor Red
        return @()
    }
    
    # Accept either canonical determinism logs or A* success hash logs
    $hashPattern = '\[PATH\] Determinism hash: ([a-f0-9]+) \(nodes=([0-9]+)\)|\[PATH\] A\* success: .* hash=([a-f0-9]+)'
    $hashMatches = Select-String -Path $logFile -Pattern $hashPattern
    
    $hashes = @()
    foreach ($match in $hashMatches) {
        # If the determinism pattern matched, group 1=hash, group 2=nodes
        $g1 = $match.Matches[0].Groups[1].Value
        if ($g1 -and $g1 -ne '') {
            $h = $g1
            $n = [int]$match.Matches[0].Groups[2].Value
        } else {
            # Otherwise fallback to the A* success hash in group 3
            $h = $match.Matches[0].Groups[3].Value
            $n = 0
        }

        $hashes += @{
            hash = $h
            nodes = $n
            line = $match.LineNumber
        }
    }
    
    return $hashes
}

# Function to compare hash sequences
function Compare-HashSequences {
    param(
        [array]$hashes1,
        [array]$hashes2,
        [string]$label1,
        [string]$label2
    )
    
    if ($hashes1.Count -eq 0 -or $hashes2.Count -eq 0) {
        Write-Host "X Cannot compare: one or both runs have no path hashes" -ForegroundColor Red
        return $false
    }
    
    if ($hashes1.Count -ne $hashes2.Count) {
        Write-Host "X Hash count mismatch: $label1 has $($hashes1.Count) paths, $label2 has $($hashes2.Count) paths" -ForegroundColor Red
        return $false
    }
    
    $allMatch = $true
    for ($i = 0; $i -lt $hashes1.Count; $i++) {
        $h1 = $hashes1[$i].hash
        $h2 = $hashes2[$i].hash
        $n1 = $hashes1[$i].nodes
        $n2 = $hashes2[$i].nodes
        
        if ($h1 -ne $h2) {
            Write-Host "  X Path $($i+1): Hash mismatch" -ForegroundColor Red
            Write-Host "    ${label1}: $h1 (nodes=$n1)" -ForegroundColor Gray
            Write-Host "    ${label2}: $h2 (nodes=$n2)" -ForegroundColor Gray
            $allMatch = $false
        } else {
            Write-Host "  OK Path $($i+1): Identical hash (nodes=$n1)" -ForegroundColor Green
        }
    }
    
    return $allMatch
}

# Function to check hash variance
function Test-HashVariance {
    param(
        [array]$hashesA,
        [array]$hashesB
    )
    
    if ($hashesA.Count -eq 0 -or $hashesB.Count -eq 0) {
        Write-Host "X Cannot test variance: one or both runs have no path hashes" -ForegroundColor Red
        return $false
    }
    
    # Extract just the hash strings for comparison
    $setA = $hashesA | ForEach-Object { $_.hash }
    $setB = $hashesB | ForEach-Object { $_.hash }
    
    # Check if any hashes are identical (shouldn't be with different seeds)
    $duplicates = 0
    foreach ($hashA in $setA) {
        if ($setB -contains $hashA) {
            $duplicates++
        }
    }
    
    if ($duplicates -eq 0) {
        Write-Host "OK All hashes differ between seeds (100% variance)" -ForegroundColor Green
        return $true
    } else {
        $percentDiff = (1 - ($duplicates / [Math]::Max($setA.Count, $setB.Count))) * 100
        Write-Host "! Partial variance: $duplicates/$($setA.Count) hashes match ($([Math]::Round($percentDiff, 1))% different)" -ForegroundColor Yellow
        return ($percentDiff -ge 80) # Allow up to 20% overlap for very small path counts
    }
}

# Clean up old test worlds
Write-Host "Cleaning up old test worlds..." -ForegroundColor Yellow
Remove-Item "$ServerDir/test-worlds/test-world-$SeedA" -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item "$ServerDir/test-worlds/test-world-$SeedB" -Recurse -Force -ErrorAction SilentlyContinue

# Helper: run scenario and optionally retry when no path hashes produced
function Invoke-ScenarioWithRetries {
    param(
        [int]$ticks,
        [long]$seed,
        [string]$serverDir,
        [string]$outLog,
        [int]$maxRetries = 2,
        [string[]]$autoCommands = @()
    )

    $attempt = 0
    $currentTicks = $ticks

    while ($attempt -le $maxRetries) {
        $attempt++
        Write-Host "Running scenario (seed=$seed) attempt $attempt/$($maxRetries+1) with ticks=$currentTicks" -ForegroundColor Cyan
        # Stop when either a Determinism hash or an A* success hash is logged
        # Stop when determinism hash or A* success hash appears, or when path network success/failure is logged
        $stopPattern = '\[PATH\] Determinism hash:|\[PATH\] A\* success: .* hash=|\[STRUCT\] Path network complete:|Path network (generated successfully|generation failed)'
        & .\run-scenario.ps1 -Ticks $currentTicks -Seed $seed -ServerDir $serverDir -AutoCommands $autoCommands -StopWhen $stopPattern | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "! Scenario run exited with code $LASTEXITCODE (continuing to inspect logs)" -ForegroundColor Yellow
        }

        Copy-Item "$serverDir/logs/latest.log" $outLog -Force
        $hashes = Get-PathHashes $outLog
        if ($hashes.Count -gt 0) {
            return @{ exitCode = 0; hashes = $hashes }
        }

        # No hashes produced — increase tick budget for next attempt
        Write-Host "! No path hashes produced (attempt=$attempt). Retrying with larger tick budget..." -ForegroundColor Yellow
        $currentTicks = [Math]::Max(6000, $currentTicks * 10)
    }

    # Final attempt returned zero hashes
    Copy-Item "$serverDir/logs/latest.log" $outLog -Force
    $finalHashes = Get-PathHashes $outLog
    return @{ exitCode = 0; hashes = $finalHashes }
}


# Run 1: Seed A (first run)
Write-Host ""
Write-Host "=== Run 1: Seed A (first run) ===" -ForegroundColor Cyan
$run1Log = "$ServerDir/logs/determinism-test-seedA-run1.log"
Remove-Item $run1Log -Force -ErrorAction SilentlyContinue

$generateCmdA = "votest fixed-layout $SeedA 3"
$result1 = Invoke-ScenarioWithRetries -ticks $Ticks -seed $SeedA -serverDir $ServerDir -outLog $run1Log -maxRetries 2 -autoCommands @($generateCmdA)
if ($result1 -is [int] -and $result1 -ne 0) { exit $result1 }
$hashesRun1 = $result1.hashes
Write-Host "Run 1 complete: $($hashesRun1.Count) path hashes captured" -ForegroundColor Green

# Run 2: Seed A (second run - should match Run 1)
Write-Host ""
Write-Host "=== Run 2: Seed A (second run - testing determinism) ===" -ForegroundColor Cyan
Remove-Item "$ServerDir/test-worlds/test-world-$SeedA" -Recurse -Force -ErrorAction SilentlyContinue
$run2Log = "$ServerDir/logs/determinism-test-seedA-run2.log"
Remove-Item $run2Log -Force -ErrorAction SilentlyContinue

$generateCmdA2 = "votest fixed-layout $SeedA 3"
$result2 = Invoke-ScenarioWithRetries -ticks $Ticks -seed $SeedA -serverDir $ServerDir -outLog $run2Log -maxRetries 2 -autoCommands @($generateCmdA2)
if ($result2 -is [int] -and $result2 -ne 0) { exit $result2 }
$hashesRun2 = $result2.hashes
Write-Host "Run 2 complete: $($hashesRun2.Count) path hashes captured" -ForegroundColor Green

# Run 3: Seed B (different seed - should differ from Seed A)
Write-Host ""
Write-Host "=== Run 3: Seed B (different seed - testing variance) ===" -ForegroundColor Cyan
$run3Log = "$ServerDir/logs/determinism-test-seedB-run1.log"
Remove-Item $run3Log -Force -ErrorAction SilentlyContinue

$generateCmdB = "votest fixed-layout $SeedB 3"
$result3 = Invoke-ScenarioWithRetries -ticks $Ticks -seed $SeedB -serverDir $ServerDir -outLog $run3Log -maxRetries 2 -autoCommands @($generateCmdB)
if ($result3 -is [int] -and $result3 -ne 0) { exit $result3 }
$hashesRun3 = $result3.hashes
Write-Host "Run 3 complete: $($hashesRun3.Count) path hashes captured" -ForegroundColor Green

# Analyze results
Write-Host ""
Write-Host "=== Determinism Analysis ===" -ForegroundColor Cyan
Write-Host "Comparing Seed A Run 1 vs Run 2 (should be identical):" -ForegroundColor White
$deterministicPass = Compare-HashSequences $hashesRun1 $hashesRun2 "Run 1" "Run 2"

Write-Host ""
Write-Host "=== Variance Analysis ===" -ForegroundColor Cyan
Write-Host "Comparing Seed A vs Seed B (should be different):" -ForegroundColor White
$variancePass = Test-HashVariance $hashesRun1 $hashesRun3

# Final summary
Write-Host ""
Write-Host "=== Test Summary ===" -ForegroundColor Cyan
Write-Host "Determinism Test (Seed A, Run 1 vs Run 2): $(if ($deterministicPass) { 'PASS' } else { 'FAIL' })" -ForegroundColor $(if ($deterministicPass) { 'Green' } else { 'Red' })
Write-Host "Variance Test (Seed A vs Seed B): $(if ($variancePass) { 'PASS' } else { 'FAIL' })" -ForegroundColor $(if ($variancePass) { 'Green' } else { 'Red' })

Write-Host ""
Write-Host "Test artifacts saved:" -ForegroundColor White
Write-Host "  Run 1 (Seed A): $run1Log" -ForegroundColor Gray
Write-Host "  Run 2 (Seed A): $run2Log" -ForegroundColor Gray
Write-Host "  Run 3 (Seed B): $run3Log" -ForegroundColor Gray

if ($deterministicPass -and $variancePass) {
    Write-Host ""
    Write-Host "OK All T026d acceptance criteria met!" -ForegroundColor Green
    exit 0
} else {
    Write-Host ""
    Write-Host "X Some T026d criteria failed - review logs above" -ForegroundColor Red
    exit 1
}
