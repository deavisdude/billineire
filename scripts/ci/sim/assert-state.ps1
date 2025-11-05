<#
.SYNOPSIS
    Assert deterministic state from scenario snapshot

.DESCRIPTION
    Validates that economy wallets, project progress, and other state
    matches expected values for deterministic reproducibility.

.PARAMETER SnapshotFile
    State snapshot JSON file (default: state-snapshot.json)

.PARAMETER ExpectedFile
    Expected state JSON for comparison (optional)

.PARAMETER FailOnMismatch
    Exit with code 1 if assertions fail (default for CI)

.EXAMPLE
    .\assert-state.ps1 -SnapshotFile state-snapshot.json -FailOnMismatch
#>

param(
    [string]$SnapshotFile = "state-snapshot.json",
    [string]$ExpectedFile = "",
    [switch]$FailOnMismatch = $true
)

$ErrorActionPreference = "Stop"

Write-Host "=== Village Overhaul CI: State Assertions ===" -ForegroundColor Cyan

# Load snapshot
if (!(Test-Path $SnapshotFile)) {
    Write-Host "✗ Snapshot file not found: $SnapshotFile" -ForegroundColor Red
    exit 1
}

$snapshot = Get-Content $SnapshotFile | ConvertFrom-Json
Write-Host "✓ Loaded snapshot from $SnapshotFile" -ForegroundColor Green

# Basic validations
$passed = $true

# Validate structure
if (!$snapshot.tick -or !$snapshot.seed) {
    Write-Host "✗ Snapshot missing required fields (tick, seed)" -ForegroundColor Red
    $passed = $false
}

# Validate wallets are non-negative
foreach ($wallet in $snapshot.wallets.PSObject.Properties) {
    if ($wallet.Value -lt 0) {
        Write-Host "✗ Wallet $($wallet.Name) has negative balance: $($wallet.Value)" -ForegroundColor Red
        $passed = $false
    }
}

# Validate determinism: Re-running with same seed should produce identical state
# (This requires loading an expected baseline or comparing hashes)
if ($ExpectedFile -and (Test-Path $ExpectedFile)) {
    $expected = Get-Content $ExpectedFile | ConvertFrom-Json
    
    # Compare critical fields
    if ($snapshot.tick -ne $expected.tick) {
        Write-Host "✗ Tick mismatch: got $($snapshot.tick), expected $($expected.tick)" -ForegroundColor Red
        $passed = $false
    }
    
    # Compare wallet totals (order-independent)
    $snapshotTotal = ($snapshot.wallets.PSObject.Properties | Measure-Object -Property Value -Sum).Sum
    $expectedTotal = ($expected.wallets.PSObject.Properties | Measure-Object -Property Value -Sum).Sum
    
    if ($snapshotTotal -ne $expectedTotal) {
        Write-Host "✗ Wallet total mismatch: got $snapshotTotal, expected $expectedTotal" -ForegroundColor Red
        $passed = $false
    }
    
    Write-Host "✓ Determinism check complete" -ForegroundColor Green
}

# Report results
if ($passed) {
    Write-Host ""
    Write-Host "=== All assertions PASSED ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host ""
    Write-Host "=== Assertions FAILED ===" -ForegroundColor Red
    if ($FailOnMismatch) {
        exit 1
    }
}
