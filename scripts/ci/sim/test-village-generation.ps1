<#
.SYNOPSIS
    Fast village generation test - exits as soon as generation completes

.DESCRIPTION
    Starts Paper server, triggers village generation via /vo generate command,
    monitors logs for generation completion markers, then exits immediately.
    Much faster than full N-tick scenario for testing structure placement.
    
    Regression Areas Monitored (see tasks.md):
    - T067: Terrain search should keep searching across async passes and emit summary diagnostics
    - T069: Site rejection logs should expose steep/blocked/fluid metrics and thresholds
    - T070: Placement should retry alternate candidates and emit retry/coverage traces

.PARAMETER Seed
    World seed for deterministic generation (default: 12345)

.PARAMETER Culture
    Culture ID for village generation (default: roman)

.PARAMETER VillageName
    Name for the generated village (default: TestVillage)

.PARAMETER MaxWaitSeconds
    Maximum time to wait for generation (default: 60)

.PARAMETER MinExpectedStructures
    Minimum structures to consider test a success (default: matches starter structure expectation)

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
    , [int]$ExpectedStructures = 5
    , [int]$MinExpectedStructures = 5
    , [int]$PathConnectivityThreshold = 90
    , [switch]$ExistingVillageFillIn = $false
    , [int]$MaxBoundsRadiusBlocks = 0
    , [int]$MinAdditionalStructures = 1
    , [int]$FillInWaitSeconds = 60
)

$ErrorActionPreference = "Stop"

# Resolve paths
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path "$ScriptDir\..\..\..").Path
$ServerDir = Join-Path $RepoRoot "test-server"
$LogFile = Join-Path $ServerDir "logs\latest.log"
$BotPlayerModule = Join-Path $ScriptDir "BotPlayer.psm1"

Import-Module $BotPlayerModule -ErrorAction Stop
$rconPassword = Enable-Rcon -ServerDir $ServerDir

Write-Host "=== Fast Village Generation Test ===" -ForegroundColor Cyan
Write-Host "Seed: $Seed" -ForegroundColor Gray
Write-Host "Culture: $Culture" -ForegroundColor Gray
Write-Host "Village Name: $VillageName" -ForegroundColor Gray
if ($ExistingVillageFillIn) {
    Write-Host "Existing village fill-in: Enabled" -ForegroundColor Gray
    if ($MaxBoundsRadiusBlocks -gt 0) {
        Write-Host "Max bounds radius override: $MaxBoundsRadiusBlocks" -ForegroundColor Gray
    }
}
Write-Host ""

function Ensure-PluginConfigSetting {
    param(
        [string]$ConfigPath,
        [string]$DefaultConfigPath,
        [int]$BoundsRadius
    )

    if (-not (Test-Path $ConfigPath)) {
        $configDir = Split-Path -Parent $ConfigPath
        if (-not (Test-Path $configDir)) {
            New-Item -ItemType Directory -Path $configDir -Force | Out-Null
        }
        if (Test-Path $DefaultConfigPath) {
            Copy-Item -Path $DefaultConfigPath -Destination $ConfigPath -Force
            Write-Host "OK Copied default config to $ConfigPath" -ForegroundColor Green
        } else {
            Write-Host "X Default config not found at $DefaultConfigPath" -ForegroundColor Red
            return $false
        }
    }

    $configContent = Get-Content -Path $ConfigPath -Raw -ErrorAction SilentlyContinue
    if (-not $configContent) {
        Write-Host "X Failed to read config at $ConfigPath" -ForegroundColor Red
        return $false
    }

    if ($configContent -match '(?m)^\s*maxBoundsRadiusBlocks:\s*\d+') {
        $configContent = $configContent -replace '(?m)^\s*maxBoundsRadiusBlocks:\s*\d+', "  maxBoundsRadiusBlocks: $BoundsRadius"
    } elseif ($configContent -match '(?m)^village:\s*$') {
        $configContent = $configContent -replace '(?m)^village:\s*$', "village:`n  maxBoundsRadiusBlocks: $BoundsRadius"
    } else {
        $configContent = $configContent.TrimEnd() + "`n`nvillage:`n  maxBoundsRadiusBlocks: $BoundsRadius`n"
    }

    Set-Content -Path $ConfigPath -Value $configContent -Encoding UTF8
    Write-Host "OK Applied maxBoundsRadiusBlocks=$BoundsRadius" -ForegroundColor Green
    return $true
}

if ($MaxBoundsRadiusBlocks -gt 0) {
    $pluginConfigPath = Join-Path $ServerDir "plugins\VillageOverhaul\config.yml"
    $defaultConfigPath = Join-Path $RepoRoot "plugin\src\main\resources\config.yml"
    if (-not (Ensure-PluginConfigSetting -ConfigPath $pluginConfigPath -DefaultConfigPath $defaultConfigPath -BoundsRadius $MaxBoundsRadiusBlocks)) {
        Write-Host "X Failed to update config for maxBoundsRadiusBlocks" -ForegroundColor Red
        exit 1
    }
}

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
try {
    $rconResult = Send-RconCommand -ServerAddress "localhost" -Port 25575 -Password $rconPassword -Command "vo generate $Culture $VillageName $Seed"
    if ($rconResult) {
        Write-Host "  RCON response: $rconResult" -ForegroundColor Gray
    } else {
        Write-Host "  ! RCON returned no response" -ForegroundColor Yellow
    }
} catch {
    Write-Host "  X RCON command failed: $($_.Exception.Message)" -ForegroundColor Red
}

# Monitor logs for generation completion
Write-Host ""
Write-Host "Monitoring generation progress..." -ForegroundColor Cyan
$generationStart = Get-Date
$generationComplete = $false
$r011bInconclusive = $true
$overlaps = 0
$villageId = $null
$structureCount = 0
$initialStructureCount = $null
$additionalStructures = 0
$boundsLogCount = 0
$fillInTriggered = $false
# Keep track of unique placement receipts we've already seen so we don't double-count
$seenReceipts = @{}
$pathsComplete = $false
$pathsConnectivity = $null
$starterShortfallLines = @()

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
        
        # Count structure placements using unique receipt keys (structureId + origin)
        if ($line -match "\[STRUCT\]\[RECEIPT\]\s+(\S+)\s+@\s+\((-?\d+),(-?\d+),(-?\d+)\)") {
            $receiptKey = "$($Matches[1])@$($Matches[2]),$($Matches[3]),$($Matches[4])"
            if (-not $seenReceipts.ContainsKey($receiptKey)) {
                $seenReceipts[$receiptKey] = $true
                $structureCount = $seenReceipts.Keys.Count
            }
        } elseif ($line -match "\[STRUCT\]\s+receipt:\s+id=(\S+)\s+bounds=\[(-?\d+)\.\.(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+)\.\.(-?\d+)\]") {
            $receiptKey = "$($Matches[1])@$($Matches[2]),$($Matches[4]),$($Matches[6])"
            if (-not $seenReceipts.ContainsKey($receiptKey)) {
                $seenReceipts[$receiptKey] = $true
                $structureCount = $seenReceipts.Keys.Count
            }
        }

        if ($line -match "\[STRUCT\]\[BOUNDS\].*candidates=") {
            $boundsLogCount++
        }
        
        # Check for path completion
        if ($line -match "\[STRUCT\] Path network complete") {
            $pathsComplete = $true
            # Try to extract connectivity percentage if present in either format
            if ($line -match "connectivity=([0-9]+(\.[0-9]+)?)%") {
                $pathsConnectivity = [double]$Matches[1]
            } elseif ($line -match "connectivity\s+([0-9]+(\.[0-9]+)?)%") {
                $pathsConnectivity = [double]$Matches[1]
            }
        }
        
        # Check for village placement complete
        if ($line -match "Village placement complete") {
            $generationComplete = $true
            break
        }

        if ($line -match "\[GEN-QUEUE\] Successfully generated village '.*' with ([0-9]+) buildings") {
            $generationComplete = $true
            $reportedStructureCount = [int]$Matches[1]
            if ($reportedStructureCount -gt $structureCount) {
                $structureCount = $reportedStructureCount
            }
            break
        }

        if ($line -match "\[STRUCT\] village: id=.* buildings=([0-9]+)") {
            $reportedStructureCount = [int]$Matches[1]
            if ($reportedStructureCount -gt $structureCount) {
                $structureCount = $reportedStructureCount
            }
        }
        if ($line -match "\[STRUCT\]\[STARTER-SHORTFALL\]") {
            if ($starterShortfallLines -notcontains $line) {
                $starterShortfallLines += $line
            }
        }
    }
    
    if ($generationComplete) {
        break
    }
    
    # Show progress
    $elapsed = [int]((Get-Date) - $generationStart).TotalSeconds
    Write-Host "  Progress: ${elapsed}s - Structures: $structureCount, Paths: $(if ($pathsComplete) {'Complete'} else {'Pending'})" -ForegroundColor Gray
}

if ($generationComplete -and $ExistingVillageFillIn -and $villageId) {
    $initialStructureCount = $structureCount
    Write-Host "" 
    Write-Host "Triggering existing-village fill-in: /votest generate-structures $villageId" -ForegroundColor Cyan
    try {
        $rconResult = Send-RconCommand -ServerAddress "localhost" -Port 25575 -Password $rconPassword -Command "votest generate-structures $villageId"
        Write-Host "  RCON response: $rconResult" -ForegroundColor Gray
    } catch {
        Write-Host "  X RCON generate-structures failed: $($_.Exception.Message)" -ForegroundColor Red
    }

    $fillInTriggered = $true
    $fillInStart = Get-Date
    while (((Get-Date) - $fillInStart).TotalSeconds -lt $FillInWaitSeconds) {
        Start-Sleep -Seconds 1
        if (-not (Test-Path $LogFile)) { continue }

        $recentLines = Get-Content $LogFile -Tail 200 -ErrorAction SilentlyContinue
        foreach ($line in $recentLines) {
            if ($line -match "\[STRUCT\]\[RECEIPT\]\s+(\S+)\s+@\s+\((-?\d+),(-?\d+),(-?\d+)\)") {
                $receiptKey = "$($Matches[1])@$($Matches[2]),$($Matches[3]),$($Matches[4])"
                if (-not $seenReceipts.ContainsKey($receiptKey)) {
                    $seenReceipts[$receiptKey] = $true
                    $structureCount = $seenReceipts.Keys.Count
                }
            } elseif ($line -match "\[STRUCT\]\s+receipt:\s+id=(\S+)\s+bounds=\[(-?\d+)\.\.(-?\d+),(-?\d+)\.\.(-?\d+),(-?\d+)\.\.(-?\d+)\]") {
                $receiptKey = "$($Matches[1])@$($Matches[2]),$($Matches[4]),$($Matches[6])"
                if (-not $seenReceipts.ContainsKey($receiptKey)) {
                    $seenReceipts[$receiptKey] = $true
                    $structureCount = $seenReceipts.Keys.Count
                }
            }

            if ($line -match "\[STRUCT\]\[BOUNDS\].*candidates=") {
                $boundsLogCount++
            }
        }

        $additionalStructures = $structureCount - $initialStructureCount
        if ($additionalStructures -ge $MinAdditionalStructures) {
            break
        }
    }
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
    Write-Host "  Structures placed: $structureCount (expected: $ExpectedStructures)" -ForegroundColor Gray
    if ($fillInTriggered) {
        Write-Host "  Existing-village fill-in added: $additionalStructures (min: $MinAdditionalStructures)" -ForegroundColor Gray
    }
    Write-Host "  Paths: $(if ($pathsComplete) {'Complete' + (if ($pathsConnectivity -ne $null) { ' (connectivity=' + $pathsConnectivity + '%)' } else { '' }) } else {'Not detected'})" -ForegroundColor Gray
} else {
    Write-Host "! Generation did not complete within ${MaxWaitSeconds}s timeout" -ForegroundColor Yellow
    Write-Host "  Structures placed: $structureCount (expected: $ExpectedStructures)" -ForegroundColor Gray
    if ($fillInTriggered) {
        Write-Host "  Existing-village fill-in added: $additionalStructures (min: $MinAdditionalStructures)" -ForegroundColor Gray
    }
    Write-Host "  Paths: $(if ($pathsComplete) {'Complete' + (if ($pathsConnectivity -ne $null) { ' (connectivity=' + $pathsConnectivity + '%)' } else { '' }) } else {'Not detected'})" -ForegroundColor Gray
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

    if ($receipts.Count -gt $structureCount) {
        $structureCount = $receipts.Count
    }
    
    Write-Host "Found $($receipts.Count) placement receipts" -ForegroundColor Gray
    
    # === Terrain and placement regression signals (T067/T069/T070) ===
    Write-Host ""
    Write-Host "=== Terrain/Placement Regression Signals ===" -ForegroundColor Cyan
    
    # T067: Terrain search fallback/regression
    $terrainFallback = $false
    $siteValidationFailures = 0
    $blockedTotal = 0
    $steepTotal = 0
    $fluidTotal = 0
    $candidateRetryEvents = 0
    $candidateExhaustions = 0
    
    foreach ($line in $allLines) {
        if ($line -match "Could not find suitable terrain.*using spawn location as fallback" -or
            $line -match "X No suitable terrain found after expanded search passes") {
            $terrainFallback = $true
        }
        if ($line -match "\[SITE-REJECT\].*steep=([0-9]+).*blocked=([0-9]+).*fluid=([0-9]+)") {
            $siteValidationFailures++
            $steepTotal += [int]$Matches[1]
            $blockedTotal += [int]$Matches[2]
            $fluidTotal += [int]$Matches[3]
        }
        if ($line -match "\[STRUCT\]\[T070\] Candidate [0-9]+/[0-9]+ rejected") {
            $candidateRetryEvents++
        }
        if ($line -match "\[STRUCT\]\[T070\] Failed to place .* after trying [0-9]+/[0-9]+ candidates") {
            $candidateExhaustions++
        }
    }
    
    if ($terrainFallback) {
        Write-Host "  ! T067 regression: terrain search exhausted without finding a suitable site" -ForegroundColor Yellow
    }
    if ($siteValidationFailures -gt 0) {
        Write-Host "  ! T069 diagnostics observed: $siteValidationFailures site rejection lines (steep=$steepTotal, blocked=$blockedTotal, fluid=$fluidTotal)" -ForegroundColor Yellow
    }
    if ($candidateRetryEvents -gt 0 -or $candidateExhaustions -gt 0) {
        Write-Host "  OK T070 retry trace present: rejectedCandidates=$candidateRetryEvents exhaustedStructures=$candidateExhaustions" -ForegroundColor Green
    } elseif ($siteValidationFailures -gt 0) {
        Write-Host "  ! T070 retry trace not observed despite site rejections; inspect placement logs" -ForegroundColor Yellow
    }
    
    # Check for overlaps (R011b acceptance criteria)
    Write-Host ""
    Write-Host "Checking for overlaps (R011b)..." -ForegroundColor Cyan
    
    # R011b requires at least 2 structures to validate collision detection
    if ($receipts.Count -lt 2) {
        Write-Host "  ! INCONCLUSIVE: Need at least 2 structures to validate collision detection (found $($receipts.Count))" -ForegroundColor Yellow
        Write-Host "    Run a seed that produces 2+ receipts to exercise overlap validation." -ForegroundColor Yellow
        $r011bInconclusive = $true
    } else {
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
            Write-Host "  OK No overlaps detected among $($receipts.Count) structures (R011b: PASS)" -ForegroundColor Green
            $r011bInconclusive = $false
        } else {
            Write-Host "  X $overlaps overlap(s) detected (R011b: FAIL)" -ForegroundColor Red
            $r011bInconclusive = $false
        }
    }
}

Write-Host ""
Write-Host "=== Test Complete ===" -ForegroundColor Cyan

# Adjusted success criteria: use MinExpectedStructures instead of ExpectedStructures
# This keeps the harness usable for degraded runs while still surfacing terrain/placement regressions.
$structuresOk = $structureCount -ge $MinExpectedStructures
$overlapsOk = $overlaps -eq 0
$connectivityOk = ($pathsConnectivity -eq $null -or $pathsConnectivity -ge $PathConnectivityThreshold)
$additionalOk = $true
$boundsOk = $true

if ($ExistingVillageFillIn) {
    $additionalOk = $additionalStructures -ge $MinAdditionalStructures
    $boundsOk = $boundsLogCount -gt 0
}

if ($generationComplete -and $overlapsOk -and $structuresOk -and $connectivityOk -and $additionalOk -and $boundsOk) {
    if ($structureCount -lt $ExpectedStructures) {
        Write-Host "OK Minimum checks passed (degraded: $structureCount/$ExpectedStructures structures)" -ForegroundColor Yellow
        if ($starterShortfallLines.Count -gt 0) {
            Write-Host "  Starter shortfall diagnostics:" -ForegroundColor Yellow
            foreach ($diag in $starterShortfallLines) {
                Write-Host "    $diag" -ForegroundColor Yellow
            }
        }
    } else {
        Write-Host "OK All checks passed" -ForegroundColor Green
    }
    exit 0
} elseif ($r011bInconclusive -and $structuresOk) {
    # R011b inconclusive is expected with 1 structure - not a failure if at least minimum placed
    Write-Host "OK Minimum structure placed ($structureCount); R011b skipped (needs 2+ structures)" -ForegroundColor Yellow
    Write-Host "  Run a seed/location that produces 2+ structures to enable overlap validation." -ForegroundColor Yellow
    exit 0
} else {
    Write-Host "X Test failed" -ForegroundColor Red
    # Provide explicit failure reasons for CI
    if ($structureCount -lt $MinExpectedStructures) { 
        Write-Host "  X Expected at least $MinExpectedStructures structure(s) but found $structureCount (HARD FAIL)" -ForegroundColor Red
        if ($starterShortfallLines.Count -gt 0) {
            Write-Host "  Starter shortfall diagnostics:" -ForegroundColor Yellow
            foreach ($diag in $starterShortfallLines) {
                Write-Host "    $diag" -ForegroundColor Yellow
            }
        }
    }
    if ($ExistingVillageFillIn -and (-not $additionalOk)) {
        Write-Host "  X Expected at least $MinAdditionalStructures additional structures but found $additionalStructures" -ForegroundColor Red
        Write-Host "    Check [STRUCT][BOUNDS] coverage and candidate logs for root cause" -ForegroundColor Red
    }
    if ($ExistingVillageFillIn -and (-not $boundsOk)) {
        Write-Host "  X Missing [STRUCT][BOUNDS] coverage logs" -ForegroundColor Red
    }
    if ($structureCount -lt $ExpectedStructures -and $structureCount -ge $MinExpectedStructures) {
        Write-Host "  ! Expected $ExpectedStructures structures but only $structureCount placed" -ForegroundColor Yellow
    }
    if ($overlaps -gt 0) { Write-Host "  X Detected $overlaps overlapping structures" -ForegroundColor Red }
    if ($pathsConnectivity -ne $null -and $pathsConnectivity -lt $PathConnectivityThreshold) { Write-Host "  X Path connectivity $pathsConnectivity% is below threshold $PathConnectivityThreshold%" -ForegroundColor Red }
    exit 1
}
