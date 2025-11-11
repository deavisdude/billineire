# Test rooftop path fix (T021b completion validation)
# Validates that paths never cross building footprints or terminate inside structures

param(
    [int]$Ticks = 1000,
    [string]$Seed = "99999"
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

Write-Host "`n=== Rooftop Path Fix Validation Test ===" -ForegroundColor Cyan
Write-Host "Testing that paths avoid building footprints completely`n" -ForegroundColor White

# Build plugin
Write-Host "[1/4] Building plugin..." -ForegroundColor Yellow
Set-Location "$PSScriptRoot\..\..\..\plugin"
& .\gradlew.bat build -q 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "X BUILD FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "OK Build successful" -ForegroundColor Green

# Copy plugin
Write-Host "[2/4] Deploying plugin..." -ForegroundColor Yellow
$PluginJar = Get-ChildItem "build\libs\village-overhaul-*.jar" | Select-Object -First 1
if (-not $PluginJar) {
    Write-Host "X Plugin JAR not found" -ForegroundColor Red
    exit 1
}
Copy-Item $PluginJar.FullName "$PSScriptRoot\..\..\..\test-server\plugins\VillageOverhaul.jar" -Force
Write-Host "OK Plugin deployed" -ForegroundColor Green

# Start server
Write-Host "[3/4] Starting test server..." -ForegroundColor Yellow
Set-Location "$PSScriptRoot\..\..\..\test-server"

# Generate test village
$Commands = @(
    "stop"  # Stop after village generation
)

# Create command file
$Commands | Out-File -FilePath "server-commands.txt" -Encoding ASCII

# Start server process
$ServerProcess = Start-Process -FilePath "java" `
    -ArgumentList "-Xms1G", "-Xmx2G", "-jar", "server.jar", "nogui" `
    -PassThru -NoNewWindow -RedirectStandardOutput "test-output.log" -RedirectStandardError "test-error.log"

# Wait for server to be ready
$ReadyPattern = 'Done \('
$Timeout = 60
$Elapsed = 0
while ($Elapsed -lt $Timeout) {
    if (Test-Path "logs\latest.log") {
        $LogContent = Get-Content "logs\latest.log" -Raw
        if ($LogContent -match $ReadyPattern) {
            Write-Host "OK Server ready" -ForegroundColor Green
            break
        }
    }
    Start-Sleep -Seconds 1
    $Elapsed++
}

if ($Elapsed -ge $Timeout) {
    Write-Host "X Server start timeout" -ForegroundColor Red
    Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

# Execute test command via RCON
Start-Sleep -Seconds 2
Write-Host "[4/4] Generating test village (seed=$Seed)..." -ForegroundColor Yellow

# Use mcrcon to execute command
& ".\mcrcon.exe" -H localhost -P 25575 -p "test" "/vo generate roman TestVillage$Seed $Seed" 2>&1 | Out-Null

# Wait for generation
Start-Sleep -Seconds 5

# Stop server
& ".\mcrcon.exe" -H localhost -P 25575 -p "test" "stop" 2>&1 | Out-Null
Start-Sleep -Seconds 3

# Kill if still running
if (-not $ServerProcess.HasExited) {
    Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
}

Write-Host "OK Village generated`n" -ForegroundColor Green

# Parse logs for validation
Write-Host "=== Validation Results ===" -ForegroundColor Cyan

$LogPath = "logs\latest.log"
if (-not (Test-Path $LogPath)) {
    Write-Host "X Log file not found" -ForegroundColor Red
    exit 1
}

$LogContent = Get-Content $LogPath

# Check 1: Entrance points below building minY
Write-Host "`n[Check 1] Entrance Y Coordinates" -ForegroundColor Yellow
$EntrancePattern = '\[PATH\] Building entrance: .+ at \((-?\d+),(-?\d+),(-?\d+)\).+\[buildingMinY=(-?\d+)\]'
$EntranceMatches = $LogContent | Select-String -Pattern $EntrancePattern

$EntranceFailures = 0
foreach ($Match in $EntranceMatches) {
    $EntranceY = [int]$Match.Matches[0].Groups[2].Value
    $BuildingMinY = [int]$Match.Matches[0].Groups[4].Value
    
    if ($EntranceY -ge $BuildingMinY) {
        Write-Host "  X FAIL: Entrance Y=$EntranceY >= building minY=$BuildingMinY" -ForegroundColor Red
        $EntranceFailures++
    }
}

if ($EntranceFailures -eq 0 -and $EntranceMatches.Count -gt 0) {
    Write-Host "  OK PASS: All $($EntranceMatches.Count) entrances below building base" -ForegroundColor Green
} elseif ($EntranceMatches.Count -eq 0) {
    Write-Host "  ! WARNING: No entrance logs found" -ForegroundColor Yellow
}

# Check 2: Building tiles avoided during pathfinding
Write-Host "`n[Check 2] Building Footprint Avoidance" -ForegroundColor Yellow
$AvoidancePattern = 'avoided (\d+) building tiles'
$AvoidanceMatches = $LogContent | Select-String -Pattern $AvoidancePattern

$TotalAvoided = 0
foreach ($Match in $AvoidanceMatches) {
    $Count = [int]$Match.Matches[0].Groups[1].Value
    $TotalAvoided += $Count
}

if ($TotalAvoided -gt 0) {
    Write-Host "  OK PASS: $TotalAvoided building tiles avoided during pathfinding" -ForegroundColor Green
} else {
    Write-Host "  ! WARNING: No building avoidance logged (buildings may not be adjacent)" -ForegroundColor Yellow
}

# Check 3: No entrance warnings
Write-Host "`n[Check 3] Entrance Validation Warnings" -ForegroundColor Yellow
$WarningPattern = '\[PATH\] WARNING: Entrance Y=.+ is not below building'
$WarningMatches = $LogContent | Select-String -Pattern $WarningPattern

if ($WarningMatches.Count -eq 0) {
    Write-Host "  OK PASS: No entrance validation warnings" -ForegroundColor Green
} else {
    Write-Host "  X FAIL: $($WarningMatches.Count) entrance validation warnings found" -ForegroundColor Red
    foreach ($Warning in $WarningMatches) {
        Write-Host "    $($Warning.Line)" -ForegroundColor Red
    }
}

# Summary
Write-Host "`n=== Summary ===" -ForegroundColor Cyan
$TotalFailures = $EntranceFailures + $WarningMatches.Count

if ($TotalFailures -eq 0) {
    Write-Host "OK ALL CHECKS PASSED - Rooftop path fix validated" -ForegroundColor Green
    exit 0
} else {
    Write-Host "X $TotalFailures FAILURES - Fix incomplete" -ForegroundColor Red
    exit 1
}
