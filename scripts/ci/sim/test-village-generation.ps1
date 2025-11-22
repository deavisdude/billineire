<#
.SYNOPSIS
    Fast village generation test - exits as soon as generation completes

.DESCRIPTION
    Starts Paper server, triggers village generation via /vo generate command,
    monitors logs for generation completion markers, then exits immediately.
    Much faster than full N-tick scenario for testing structure placement.

.PARAMETER Seed
    World seed for deterministic generation (default: 12345)

.PARAMETER Culture
    Culture ID for village generation (default: roman)

.PARAMETER VillageName
    Name for the generated village (default: TestVillage)

.PARAMETER MaxWaitSeconds
    Maximum time to wait for generation (default: 60)

.EXAMPLE
    .\test-village-generation.ps1
    Generate Roman village with seed 12345

.EXAMPLE
    .\test-village-generation.ps1 -Seed 67890 -Culture roman -VillageName "MyTest"
#>

param(
    [long]$Seed = 12345,
    [string]$Culture = "roman",
    [string]$VillageName = "TestVillage",
    [int]$MaxWaitSeconds = 60
)

$ErrorActionPreference = "Stop"

# Resolve paths
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path "$ScriptDir\..\..\..").Path
$ServerDir = Join-Path $RepoRoot "test-server"
$LogFile = Join-Path $ServerDir "logs\latest.log"

Write-Host "=== Fast Village Generation Test ===" -ForegroundColor Cyan
Write-Host "Seed: $Seed" -ForegroundColor Gray
Write-Host "Culture: $Culture" -ForegroundColor Gray
Write-Host "Village Name: $VillageName" -ForegroundColor Gray
Write-Host ""

# Find Java
$JavaPath = $null
$javaHome = $env:JAVA_HOME
if ($javaHome -and (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    $JavaPath = Join-Path $javaHome "bin\java.exe"
} else {
    # Try common locations
    $commonPaths = @(
        "C:\Program Files\Eclipse Adoptium\jdk-21\bin\java.exe",
        "C:\Program Files\Java\jdk-21\bin\java.exe",
        "C:\Program Files\Microsoft\jdk-21\bin\java.exe"
    )
    foreach ($path in $commonPaths) {
        if (Test-Path $path) {
            $JavaPath = $path
            break
        }
    }
}

if (-not $JavaPath) {
    Write-Host "X Java 21+ not found" -ForegroundColor Red
    exit 1
}

Write-Host "Starting Paper server..." -ForegroundColor Cyan
Write-Host "Using java executable: $JavaPath" -ForegroundColor Gray

# Clear old logs
if (Test-Path $LogFile) {
    Remove-Item $LogFile -Force
}

# Start server process
Push-Location $ServerDir
$ServerProcess = Start-Process -FilePath $JavaPath `
    -ArgumentList @(
        "-Xms2G", "-Xmx2G",
        "-XX:+UseG1GC",
        "-Dcom.mojang.eula.agree=true",
        "-jar", "paper.jar",
        "--nogui",
        "--world-dir=test-worlds",
        "--level-name=test-world-$Seed"
    ) `
    -NoNewWindow -PassThru
Pop-Location

Write-Host "Server process started (PID: $($ServerProcess.Id))" -ForegroundColor Gray

# Wait for server ready
$waitStart = Get-Date
$serverReady = $false
while (((Get-Date) - $waitStart).TotalSeconds -lt 120) {
    Start-Sleep -Seconds 2
    if (Test-Path $LogFile) {
        $recentLines = Get-Content $LogFile -Tail 50 -ErrorAction SilentlyContinue
        if ($recentLines -match "Done \(.*\)! For help, type") {
            $serverReady = $true
            break
        }
    }
    Write-Host "  Waiting for server initialization... ($([int]((Get-Date) - $waitStart).TotalSeconds)/120 seconds)" -ForegroundColor Gray
}

if (-not $serverReady) {
    Write-Host "X Server failed to start within timeout" -ForegroundColor Red
    Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

Write-Host "OK Server started successfully" -ForegroundColor Green

# Wait a moment for plugin to initialize
Start-Sleep -Seconds 2

# Trigger village generation via RCON
Write-Host ""
Write-Host "Triggering village generation: /vo generate $Culture $VillageName $Seed" -ForegroundColor Cyan

# Use mcrcon if available, otherwise skip RCON trigger (server will auto-generate)
$rconPath = Join-Path $ServerDir "plugins\mcrcon.exe"
if (Test-Path $rconPath) {
    $rconResult = & $rconPath -H localhost -p 25575 -P admin "vo generate $Culture $VillageName $Seed" 2>&1
    Write-Host "  RCON response: $rconResult" -ForegroundColor Gray
} else {
    Write-Host "  Note: RCON not available, relying on auto-generation or manual trigger" -ForegroundColor Yellow
}

# Monitor logs for generation completion
Write-Host ""
Write-Host "Monitoring generation progress..." -ForegroundColor Cyan
$generationStart = Get-Date
$generationComplete = $false
$villageId = $null
$structureCount = 0
$pathsComplete = $false

while (((Get-Date) - $generationStart).TotalSeconds -lt $MaxWaitSeconds) {
    Start-Sleep -Seconds 1
    
    if (-not (Test-Path $LogFile)) { continue }
    
    $recentLines = Get-Content $LogFile -Tail 200 -ErrorAction SilentlyContinue
    
    # Look for village generation markers
    foreach ($line in $recentLines) {
        # Extract village ID (auto-generated or user-triggered)
        if ($line -match "Village placement complete.*villageId=([a-f0-9-]+)") {
            if (-not $villageId) {
                $villageId = $Matches[1]
                Write-Host "  Village ID: $villageId" -ForegroundColor Gray
            }
        }
        if ($line -match "\[STRUCT\] Registered village ([a-f0-9-]+)") {
            if (-not $villageId) {
                $villageId = $Matches[1]
                Write-Host "  Village ID: $villageId" -ForegroundColor Gray
            }
        }
        
        # Count structure placements
        if ($line -match "\[STRUCT\]\[RECEIPT\]") {
            $structureCount++
        }
        
        # Check for path completion
        if ($line -match "\[STRUCT\] Path network complete") {
            $pathsComplete = $true
        }
        
        # Check for village placement complete
        if ($line -match "Village placement complete") {
            $generationComplete = $true
            break
        }
    }
    
    if ($generationComplete) {
        break
    }
    
    # Show progress
    $elapsed = [int]((Get-Date) - $generationStart).TotalSeconds
    Write-Host "  Progress: ${elapsed}s - Structures: $structureCount, Paths: $(if ($pathsComplete) {'Complete'} else {'Pending'})" -ForegroundColor Gray
}

# Stop server
Write-Host ""
Write-Host "Stopping server..." -ForegroundColor Cyan
Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Report results
Write-Host ""
Write-Host "=== Generation Results ===" -ForegroundColor Cyan

if ($generationComplete) {
    Write-Host "OK Generation completed in $([int]((Get-Date) - $generationStart).TotalSeconds) seconds" -ForegroundColor Green
    Write-Host "  Village ID: $villageId" -ForegroundColor Gray
    Write-Host "  Structures placed: $structureCount" -ForegroundColor Gray
    Write-Host "  Paths: $(if ($pathsComplete) {'Complete'} else {'Not detected'})" -ForegroundColor Gray
} else {
    Write-Host "! Generation did not complete within ${MaxWaitSeconds}s timeout" -ForegroundColor Yellow
    Write-Host "  Structures placed: $structureCount" -ForegroundColor Gray
    Write-Host "  Paths: $(if ($pathsComplete) {'Complete'} else {'Not detected'})" -ForegroundColor Gray
}

# Run R010 validation if village was created
if ($villageId) {
    Write-Host ""
    Write-Host "=== R010: Headless Proof-of-Reality Verification ===" -ForegroundColor Cyan
    
    # Parse latest.log for placement receipts
    $allLines = Get-Content $LogFile -ErrorAction SilentlyContinue
    $receipts = @()
    foreach ($line in $allLines) {
        if ($line -match "\[STRUCT\]\[RECEIPT\]\s+(\S+)\s+@\s+\((-?\d+),(-?\d+),(-?\d+)\)\s+rot=(\d+)°\s+bounds=\((-?\d+)\.\.(-?\d+),\s*(-?\d+)\.\.(-?\d+),\s*(-?\d+)\.\.(-?\d+)\)") {
            $receipts += @{
                StructureId = $Matches[1]
                OriginX = [int]$Matches[2]
                OriginY = [int]$Matches[3]
                OriginZ = [int]$Matches[4]
                Rotation = [int]$Matches[5]
                MinX = [int]$Matches[6]
                MaxX = [int]$Matches[7]
                MinY = [int]$Matches[8]
                MaxY = [int]$Matches[9]
                MinZ = [int]$Matches[10]
                MaxZ = [int]$Matches[11]
            }
        }
    }
    
    Write-Host "Found $($receipts.Count) placement receipts" -ForegroundColor Gray
    
    # Check for overlaps (R011b acceptance criteria)
    Write-Host ""
    Write-Host "Checking for overlaps (R011b)..." -ForegroundColor Cyan
    $overlaps = 0
    for ($i = 0; $i -lt $receipts.Count; $i++) {
        for ($j = $i + 1; $j -lt $receipts.Count; $j++) {
            $r1 = $receipts[$i]
            $r2 = $receipts[$j]
            
            # AABB overlap check: A overlaps B if maxA >= minB AND minA <= maxB (for all 3 axes)
            $xOverlap = $r1.MaxX -ge $r2.MinX -and $r1.MinX -le $r2.MaxX
            $yOverlap = $r1.MaxY -ge $r2.MinY -and $r1.MinY -le $r2.MaxY
            $zOverlap = $r1.MaxZ -ge $r2.MinZ -and $r1.MinZ -le $r2.MaxZ
            
            if ($xOverlap -and $yOverlap -and $zOverlap) {
                $overlaps++
                Write-Host "  X OVERLAP DETECTED:" -ForegroundColor Red
                Write-Host "    $($r1.StructureId): X=[$($r1.MinX)..$($r1.MaxX)] Y=[$($r1.MinY)..$($r1.MaxY)] Z=[$($r1.MinZ)..$($r1.MaxZ)]" -ForegroundColor Red
                Write-Host "    $($r2.StructureId): X=[$($r2.MinX)..$($r2.MaxX)] Y=[$($r2.MinY)..$($r2.MaxY)] Z=[$($r2.MinZ)..$($r2.MaxZ)]" -ForegroundColor Red
            }
        }
    }
    
    if ($overlaps -eq 0) {
        Write-Host "  OK No overlaps detected (R011b: PASS)" -ForegroundColor Green
    } else {
        Write-Host "  X $overlaps overlap(s) detected (R011b: FAIL)" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "=== Test Complete ===" -ForegroundColor Cyan
if ($generationComplete -and $overlaps -eq 0) {
    Write-Host "OK All checks passed" -ForegroundColor Green
    exit 0
} else {
    Write-Host "! Some checks failed or incomplete" -ForegroundColor Yellow
    exit 1
}
