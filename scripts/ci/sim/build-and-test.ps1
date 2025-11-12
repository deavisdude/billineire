<#
.SYNOPSIS
    Build plugin and run headless scenario test

.DESCRIPTION
    Builds the Village Overhaul plugin with Gradle, copies the JAR to test-server,
    and runs the scenario test harness. This ensures the test always uses the
    latest code changes and avoids JAR copy mismatches.

.PARAMETER Ticks
    Number of ticks to simulate (default: 3000 = 2.5 minutes at 20 TPS)

.PARAMETER Seed
    World seed for deterministic generation (default: 12345)

.PARAMETER SkipBuild
    Skip the Gradle build and just copy existing JAR + run test

.PARAMETER SkipTests
    Skip running Gradle tests during build (pass -x test to Gradle)

.EXAMPLE
    .\build-and-test.ps1
    Build plugin (with tests) and run 3000-tick scenario

.EXAMPLE
    .\build-and-test.ps1 -Ticks 6000 -SkipTests
    Build plugin (skip unit tests) and run 6000-tick scenario

.EXAMPLE
    .\build-and-test.ps1 -SkipBuild
    Use existing JAR and run test (useful for quick re-runs)
#>

param(
    [int]$Ticks = 3000,
    [long]$Seed = 12345,
    [switch]$SkipBuild = $false,
    [switch]$SkipTests = $false
)

$ErrorActionPreference = "Stop"

# Resolve paths relative to script location
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path "$ScriptDir\..\..\..").Path
$PluginDir = Join-Path $RepoRoot "plugin"
$TestServerPluginsDir = Join-Path $RepoRoot "test-server\plugins"
$HarnessScript = Join-Path $ScriptDir "run-scenario.ps1"

Write-Host "=== Build and Test: Village Overhaul ===" -ForegroundColor Cyan
Write-Host "Ticks: $Ticks" -ForegroundColor Gray
Write-Host "Seed: $Seed" -ForegroundColor Gray
Write-Host "Skip Build: $SkipBuild" -ForegroundColor Gray
Write-Host "Skip Tests: $SkipTests" -ForegroundColor Gray
Write-Host ""

# Step 1: Build plugin (unless skipped)
if (-not $SkipBuild) {
    Write-Host "=== Building Plugin ===" -ForegroundColor Cyan
    Push-Location $PluginDir
    try {
        if ($SkipTests) {
            Write-Host "Running: .\gradlew build -x test" -ForegroundColor Gray
            .\gradlew build -x test
        } else {
            Write-Host "Running: .\gradlew build" -ForegroundColor Gray
            .\gradlew build
        }
        
        if ($LASTEXITCODE -ne 0) {
            Write-Host "X Gradle build failed with exit code $LASTEXITCODE" -ForegroundColor Red
            exit $LASTEXITCODE
        }
        
        Write-Host "OK Plugin built successfully" -ForegroundColor Green
    } finally {
        Pop-Location
    }
    Write-Host ""
} else {
    Write-Host "Skipping build (using existing JAR)" -ForegroundColor Yellow
    Write-Host ""
}

# Step 2: Copy JAR to test-server
Write-Host "=== Copying Plugin JAR ===" -ForegroundColor Cyan

$BuildLibsDir = Join-Path $PluginDir "build\libs"
$JarFile = Get-ChildItem "$BuildLibsDir\*.jar" | Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } | Select-Object -First 1

if (-not $JarFile) {
    Write-Host "X No plugin JAR found in $BuildLibsDir" -ForegroundColor Red
    Write-Host "  Build the plugin first or check build output" -ForegroundColor Red
    exit 1
}

$DestJar = Join-Path $TestServerPluginsDir "VillageOverhaul.jar"
Write-Host "Source: $($JarFile.FullName)" -ForegroundColor Gray
Write-Host "Dest:   $DestJar" -ForegroundColor Gray

Copy-Item $JarFile.FullName $DestJar -Force
Write-Host "OK JAR copied successfully" -ForegroundColor Green

# Clean up remapped JARs to avoid ambiguous plugin warnings
$RemappedDir = Join-Path $TestServerPluginsDir ".paper-remapped"
if (Test-Path $RemappedDir) {
    Write-Host "Cleaning remapped plugin cache..." -ForegroundColor Gray
    Remove-Item "$RemappedDir\*" -Force -Recurse -ErrorAction SilentlyContinue
}

Write-Host ""

# Step 3: Run scenario test
Write-Host "=== Running Scenario Test ===" -ForegroundColor Cyan
& $HarnessScript -Ticks $Ticks -Seed $Seed

$TestExitCode = $LASTEXITCODE

# Summary
Write-Host ""
Write-Host "=== Build and Test Complete ===" -ForegroundColor Cyan
if ($TestExitCode -eq 0) {
    Write-Host "OK All steps passed" -ForegroundColor Green
} else {
    Write-Host "X Test failed with exit code $TestExitCode" -ForegroundColor Red
}

exit $TestExitCode
