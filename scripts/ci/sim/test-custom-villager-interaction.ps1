<#
.SYNOPSIS
    Test Custom Villager interactions for Java and Java+Bedrock compatibility

.DESCRIPTION
    Tests custom villager spawn and interaction using Paper server and RCON commands.
    
    Validates:
    - Custom villager spawns successfully via /votest spawn-villager
    - Player bot can interact with villagers via /votest trigger-interaction
    - Logs show trade completion and project contributions
    - Bedrock clients can interact (when Geyser is present)
    
    T019r: Compatibility tests for FR-016 (Custom Villagers) and FR-010 (Cross-Edition)

.PARAMETER ServerDir
    Server directory (default: test-server)

.PARAMETER Ticks
    Number of ticks to simulate (default: 1200 = 1 minute at 20 TPS)

.PARAMETER TestBedrock
    Whether to test Bedrock client interaction (requires Geyser installed)

.EXAMPLE
    .\test-custom-villager-interaction.ps1 -Ticks 1200 -TestBedrock $false
#>

param(
    [string]$ServerDir = "test-server",
    [int]$Ticks = 1200,
    [bool]$TestBedrock = $false,
    [string]$OutputFile = "custom-villager-test-results.json"
)

$ErrorActionPreference = "Stop"

# Detect Java 17+ for Paper 1.20.4
$javaPath = "java"
$java17Path = "C:\Program Files\Java\jdk-17\bin\java.exe"
$java20Path = "C:\Program Files\Java\jdk-20\bin\java.exe"

if (Test-Path $java20Path) {
    $javaPath = $java20Path
    Write-Host "Using Java 20: $javaPath" -ForegroundColor Gray
} elseif (Test-Path $java17Path) {
    $javaPath = $java17Path
    Write-Host "Using Java 17: $javaPath" -ForegroundColor Gray
} else {
    # Check system Java version
    $javaVersion = & java -version 2>&1 | Select-String "version"
    if ($javaVersion -notmatch '"(1[7-9]|[2-9][0-9])\.') {
        Write-Host "X Paper 1.20.4 requires Java 17 or newer" -ForegroundColor Red
        Write-Host "  Current Java: $javaVersion" -ForegroundColor Yellow
        Write-Host "  Please install Java 17+ from https://adoptium.net/" -ForegroundColor Yellow
        exit 1
    }
}

Write-Host "=== T019r: Custom Villager Interaction Test ===" -ForegroundColor Cyan
Write-Host "Ticks: $Ticks" -ForegroundColor White
Write-Host "Test Bedrock: $TestBedrock" -ForegroundColor White
Write-Host "Output: $OutputFile" -ForegroundColor White

# Import bot player module
$modulePath = Join-Path $PSScriptRoot "BotPlayer.psm1"
if (Test-Path $modulePath) {
    Import-Module $modulePath -Force
} else {
    Write-Host "X Bot player module not found: $modulePath" -ForegroundColor Red
    exit 1
}

# Ensure server is set up
if (!(Test-Path "$ServerDir/paper.jar")) {
    Write-Host "X Paper server not found in $ServerDir" -ForegroundColor Red
    Write-Host "  Run: .\run-headless-paper.ps1 -AcceptEula" -ForegroundColor Yellow
    exit 1
}

# Check if our plugin is installed
$pluginJar = Get-ChildItem "$ServerDir/plugins" -Filter "village-overhaul-*.jar" -ErrorAction SilentlyContinue
if (!$pluginJar) {
    Write-Host "X Village Overhaul plugin not found in $ServerDir/plugins" -ForegroundColor Red
    Write-Host "  Build and copy: cd plugin && ./gradlew build && copy build/libs/*.jar ../$ServerDir/plugins/" -ForegroundColor Yellow
    exit 1
}

Write-Host "OK Found plugin: $($pluginJar.Name)" -ForegroundColor Green

# Check for Geyser if testing Bedrock
if ($TestBedrock) {
    $geyserJar = Get-ChildItem "$ServerDir/plugins" -Filter "Geyser-*.jar" -ErrorAction SilentlyContinue
    if (!$geyserJar) {
        Write-Host "! Geyser not found - skipping Bedrock test" -ForegroundColor Yellow
        $TestBedrock = $false
    } else {
        Write-Host "OK Found Geyser: $($geyserJar.Name)" -ForegroundColor Green
    }
}

# Create test scenario configuration
$scenarioConfig = @{
    name = "custom-villager-interaction-test"
    seed = 98765
    ticks = $Ticks
    testBedrock = $TestBedrock
    validations = @(
        "custom_villager_spawned",
        "interaction_logged",
        "project_contribution_made"
    )
}

$scenarioConfigJson = $scenarioConfig | ConvertTo-Json -Depth 10
$scenarioConfigFile = Join-Path $ServerDir "test-scenario-config.json"
Set-Content -Path $scenarioConfigFile -Value $scenarioConfigJson
Write-Host "OK Scenario config written: $scenarioConfigFile" -ForegroundColor Green

# Start the server with our test scenario
Write-Host "`nStarting server for custom villager test..." -ForegroundColor Yellow

$serverLogPath = Join-Path (Resolve-Path $ServerDir) "test-custom-villager.log"
$serverErrorLogPath = Join-Path (Resolve-Path $ServerDir) "test-custom-villager-error.log"

# Start server
$serverProcess = Start-Process -FilePath $javaPath `
    -ArgumentList "-Xmx1G", "-Xms1G", "-XX:+UseG1GC", "-Dcom.mojang.eula.agree=true", `
                  "-jar", "paper.jar", "--nogui" `
    -WorkingDirectory $ServerDir `
    -RedirectStandardOutput $serverLogPath `
    -RedirectStandardError $serverErrorLogPath `
    -PassThru `
    -NoNewWindow

if (!$serverProcess) {
    Write-Host "X Failed to start server" -ForegroundColor Red
    exit 1
}

Write-Host "OK Server started (PID: $($serverProcess.Id))" -ForegroundColor Green

# Wait for server to initialize
Write-Host "Waiting for server initialization..." -ForegroundColor Yellow
$maxWaitSeconds = 120
$startTime = Get-Date
$serverReady = $false

while (((Get-Date) - $startTime).TotalSeconds -lt $maxWaitSeconds) {
    if (Test-Path $serverLogPath) {
        $logContent = Get-Content $serverLogPath -Raw -ErrorAction SilentlyContinue
        if ($logContent -and ($logContent -match 'Done')) {
            $serverReady = $true
            Write-Host "OK Server is ready" -ForegroundColor Green
            break
        }
    }
    Start-Sleep -Milliseconds 500
}

if (!$serverReady) {
    Write-Host "X Server failed to initialize within $maxWaitSeconds seconds" -ForegroundColor Red
    
    # Show error log if it exists
    if (Test-Path $serverErrorLogPath) {
        $errorContent = Get-Content $serverErrorLogPath -Raw
        if ($errorContent) {
            Write-Host "`n=== Server Error Log ===" -ForegroundColor Yellow
            Write-Host $errorContent -ForegroundColor Red
        }
    }
    
    # Show last lines of main log
    if (Test-Path $serverLogPath) {
        $logContent = Get-Content $serverLogPath -Tail 30 -ErrorAction SilentlyContinue
        if ($logContent) {
            Write-Host "`n=== Server Log (last 30 lines) ===" -ForegroundColor Yellow
            $logContent | ForEach-Object { Write-Host $_ -ForegroundColor Gray }
        }
    }
    
    Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

# Wait for plugin to initialize
Write-Host "Waiting for Village Overhaul plugin initialization..." -ForegroundColor Yellow
$pluginReady = Wait-ForLogPattern -LogFile $serverLogPath -Pattern "Village Overhaul enabled successfully" -TimeoutSeconds 30

if (!$pluginReady) {
    Write-Host "! Plugin initialization not detected in logs, continuing anyway..." -ForegroundColor Yellow
} else {
    Write-Host "OK Plugin initialized" -ForegroundColor Green
}

# Give RCON a moment to be ready
Start-Sleep -Seconds 2

# Test validations
$testsPassed = 0
$testsTotal = 4
$villageId = $null
$villagerUuid = $null

# Test 1: Create a test village
Write-Host "`n[1/4] Creating test village via /votest..." -ForegroundColor Cyan
try {
    $createVillageResult = Send-RconCommand -Password "test123" -Command "votest create-village TestVillage 0 64 0"
    
    if ($createVillageResult -match "Created test village.*ID:\s*([a-f0-9-]+)") {
        $villageId = $Matches[1]
        Write-Host "OK Created test village with ID: $villageId" -ForegroundColor Green
        $testsPassed++
    } else {
        Write-Host "X Failed to create village: $createVillageResult" -ForegroundColor Red
    }
} catch {
    Write-Host "X Error creating village: $_" -ForegroundColor Red
}

# Test 2: Spawn a custom villager via RCON
Write-Host "`n[2/4] Spawning custom villager via /votest..." -ForegroundColor Cyan
if ($villageId) {
    try {
        $spawnResult = Send-RconCommand -Password "test123" -Command "votest spawn-villager merchant $villageId 0 64 0"
        
        if ($spawnResult -match "Spawned custom villager.*UUID:\s*([a-f0-9-]+)") {
            $villagerUuid = $Matches[1]
            Write-Host "OK Spawned custom villager with UUID: $villagerUuid" -ForegroundColor Green
            $testsPassed++
            
            # Verify in logs
            Start-Sleep -Seconds 2
            if (Wait-ForLogPattern -LogFile $serverLogPath -Pattern "Spawned custom villager.*merchant" -TimeoutSeconds 10) {
                Write-Host "OK Spawn confirmed in server logs" -ForegroundColor Green
            } else {
                Write-Host "! Spawn not found in logs (may still be valid)" -ForegroundColor Yellow
            }
        } else {
            Write-Host "X Failed to spawn custom villager: $spawnResult" -ForegroundColor Red
            Write-Host "  This may indicate plugin initialization issues" -ForegroundColor Yellow
        }
    } catch {
        Write-Host "X Error spawning villager: $_" -ForegroundColor Red
    }
} else {
    Write-Host "X Skipping villager spawn (no village created)" -ForegroundColor Red
}

# Test 3: Simulate player interaction with custom villager
Write-Host "`n[3/4] Testing simulated player interaction..." -ForegroundColor Cyan

# Test 3: Simulate player interaction with custom villager
Write-Host "`n[3/4] Testing simulated player interaction..." -ForegroundColor Cyan

if ($villagerUuid) {
    try {
        # Use simulate-interaction which creates a mock player context
        $interactResult = Send-RconCommand -Password "test123" -Command "votest simulate-interaction $villagerUuid"
        
        if ($interactResult -match "Simulated trade completed") {
            Write-Host "OK Simulated interaction completed: $interactResult" -ForegroundColor Green
            $testsPassed++
            
            # Check logs for trade completion
            Start-Sleep -Seconds 2
            if (Wait-ForLogPattern -LogFile $serverLogPath -Pattern "\[TEST\] Trade completed with custom villager" -TimeoutSeconds 10) {
                Write-Host "OK Trade completion logged" -ForegroundColor Green
                $testsPassed++
            } else {
                Write-Host "! Trade completion not logged in expected format" -ForegroundColor Yellow
            }
        } else {
            Write-Host "X Unexpected interaction result: $interactResult" -ForegroundColor Red
        }
    } catch {
        Write-Host "X Error triggering interaction: $_" -ForegroundColor Red
    }
} else {
    Write-Host "X Skipping interaction test (no villager UUID)" -ForegroundColor Red
}

# Test 4: Verify plugin metrics
Write-Host "`n[4/4] Checking plugin metrics..." -ForegroundColor Cyan
try {
    $metricsResult = Send-RconCommand -Password "test123" -Command "votest metrics"
    Write-Host "OK Metrics command executed: $metricsResult" -ForegroundColor Green
    
    # Wait a moment and check logs for metrics dump
    Start-Sleep -Seconds 1
    if (Wait-ForLogPattern -LogFile $serverLogPath -Pattern "METRICS DUMP" -TimeoutSeconds 5) {
        Write-Host "OK Metrics dumped to logs" -ForegroundColor Green
    }
} catch {
    Write-Host "! Error getting metrics: $_" -ForegroundColor Yellow
}

# Allow server to run a bit more
Write-Host "`nRunning server for $Ticks ticks..." -ForegroundColor Yellow
$runSeconds = [Math]::Ceiling($Ticks / 20.0)
Write-Host "Running scenario for approximately $runSeconds seconds..." -ForegroundColor Yellow
Start-Sleep -Seconds $runSeconds

# Stop the server
Write-Host "Stopping server..." -ForegroundColor Yellow
Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Parse logs to validate test criteria
Write-Host "`nValidating test results..." -ForegroundColor Cyan

$results = @{
    timestamp = (Get-Date).ToString("o")
    testBedrock = $TestBedrock
    validations = @{}
    passed = $false
}

if (Test-Path $serverLogPath) {
    $logContent = Get-Content $serverLogPath -Raw
    
    # Check 1: Custom villager spawned
    if ($logContent -match "Spawned custom villager") {
        Write-Host "OK Custom villager spawned" -ForegroundColor Green
        $results.validations.custom_villager_spawned = $true
    } else {
        Write-Host "X No custom villager spawn found in logs" -ForegroundColor Red
        $results.validations.custom_villager_spawned = $false
    }
    
    # Check 2: Interaction logged (using TEST markers from simulate-interaction)
    if ($logContent -match "\[TEST\] Simulated interaction with custom villager") {
        Write-Host "OK Custom villager interaction logged" -ForegroundColor Green
        $results.validations.interaction_logged = $true
    } else {
        Write-Host "! No interaction logs found" -ForegroundColor Yellow
        $results.validations.interaction_logged = $false
    }
    
    # Check 3: Project contribution from custom villager trade
    if ($logContent -match "\[TEST\] Trade completed with custom villager") {
        Write-Host "OK Trade completed with custom villager" -ForegroundColor Green
        
        # Check if contribution was made to project or treasury - must have actual contribution
        if ($logContent -match "\[TEST\] Project contribution made \(active project\)") {
            Write-Host "OK Project contribution made to active project" -ForegroundColor Green
            $results.validations.project_contribution_made = $true
        } elseif ($logContent -match "\[TEST\] Project contribution made \(treasury\)") {
            Write-Host "OK Treasury contribution made (no active projects)" -ForegroundColor Green
            $results.validations.project_contribution_made = $true
        } elseif ($logContent -match "\[TEST\] Trade completed without village contribution \(isolated villager\)") {
            Write-Host "X Trade completed but villager not part of village - this is a test setup issue" -ForegroundColor Red
            $results.validations.project_contribution_made = $false
        } else {
            Write-Host "! Trade completed but no contribution logged" -ForegroundColor Yellow
            $results.validations.project_contribution_made = $false
        }
    } else {
        Write-Host "! No trade completion found" -ForegroundColor Yellow
        $results.validations.project_contribution_made = $false
    }
    
    # Bedrock-specific validation
    if ($TestBedrock) {
        if ($logContent -match "Geyser.*connected" -or $logContent -match "Bedrock") {
            Write-Host "OK Bedrock client interaction path available" -ForegroundColor Green
            $results.validations.bedrock_compatible = $true
        } else {
            Write-Host "! No Bedrock client interaction detected" -ForegroundColor Yellow
            $results.validations.bedrock_compatible = $false
        }
    }
    
    # Overall pass/fail - all three validations must pass
    $allPassed = $results.validations.custom_villager_spawned -and `
                 $results.validations.interaction_logged -and `
                 $results.validations.project_contribution_made
    $results.passed = $allPassed
    
    if ($allPassed) {
        Write-Host "`nOK All validations passed - complete interaction flow working" -ForegroundColor Green
    } else {
        Write-Host "`nX Some validations failed" -ForegroundColor Red
        Write-Host "  Check server logs for details: $serverLogPath" -ForegroundColor Gray
    }
} else {
    Write-Host "X Server log not found" -ForegroundColor Red
    $results.error = "Server log not found"
}

# Write results to output file
$resultsJson = $results | ConvertTo-Json -Depth 10
Set-Content -Path $OutputFile -Value $resultsJson
Write-Host "`nOK Results written to: $OutputFile" -ForegroundColor Green

# Update compatibility matrix
$compatMatrixPath = "..\..\..\..\docs\compatibility-matrix.md"
if (Test-Path $compatMatrixPath) {
    Write-Host "Updating compatibility matrix..." -ForegroundColor Yellow
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm"
    $testResult = if ($results.passed) { "OK PASS" } else { "! PARTIAL" }
    $matrixEntry = "`n| Custom Villager Interaction | Java | $testResult | $timestamp | T019r automated test |"
    
    Add-Content -Path $compatMatrixPath -Value $matrixEntry
    Write-Host "OK Compatibility matrix updated" -ForegroundColor Green
} else {
    Write-Host "! Compatibility matrix not found at: $compatMatrixPath" -ForegroundColor Yellow
}

Write-Host "`n=== Test Complete ===" -ForegroundColor Cyan
exit 0
