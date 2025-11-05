<#
.SYNOPSIS
    Boot headless Paper server with Geyser and Village Overhaul plugin for CI testing

.DESCRIPTION
    Downloads Paper, Geyser, and Floodgate if needed, then starts a headless server
    with the Village Overhaul plugin for deterministic simulation testing.

.PARAMETER ServerDir
    Directory to use for the test server (default: test-server)

.PARAMETER PaperVersion
    Paper version to use (default: 1.20.4)

.PARAMETER AcceptEula
    Automatically accept Minecraft EULA (required for CI)

.EXAMPLE
    .\run-headless-paper.ps1 -AcceptEula
#>

param(
    [string]$ServerDir = "test-server",
    [string]$PaperVersion = "1.20.4",
    [switch]$AcceptEula
)

$ErrorActionPreference = "Stop"

Write-Host "=== Village Overhaul CI: Headless Paper Setup ===" -ForegroundColor Cyan

# Create server directory
if (!(Test-Path $ServerDir)) {
    New-Item -ItemType Directory -Path $ServerDir | Out-Null
}

# Accept EULA
if ($AcceptEula) {
    Set-Content -Path "$ServerDir/eula.txt" -Value "eula=true"
    Write-Host "OK EULA accepted" -ForegroundColor Green
}

# Download Paper if not exists
$paperJar = "$ServerDir/paper.jar"
if (!(Test-Path $paperJar)) {
    Write-Host "Downloading Paper $PaperVersion..." -ForegroundColor Yellow
    
    # Use a known stable build for CI
    # For 1.20.4, using build 497 (a stable release)
    $buildNumber = 497
    $paperUrl = "https://api.papermc.io/v2/projects/paper/versions/$PaperVersion/builds/$buildNumber/downloads/paper-$PaperVersion-$buildNumber.jar"
    
    try {
        Invoke-WebRequest -Uri $paperUrl -OutFile $paperJar -ErrorAction Stop
        Write-Host "OK Paper downloaded successfully" -ForegroundColor Green
    } catch {
        Write-Host "X Failed to download Paper from $paperUrl" -ForegroundColor Red
        Write-Host "  Error: $_" -ForegroundColor Red
        exit 1
    }
}

# Download Geyser if not exists (optional for basic tests)
$geyserJar = "$ServerDir/plugins/Geyser-Spigot.jar"
if (!(Test-Path $geyserJar)) {
    New-Item -ItemType Directory -Path "$ServerDir/plugins" -Force | Out-Null
    Write-Host "Skipping Geyser download (not required for basic tick tests)" -ForegroundColor Yellow
    # Geyser can be added later when testing cross-platform features
}

# Copy Village Overhaul plugin
$voPlugin = "plugin/build/libs/village-overhaul-0.1.0-SNAPSHOT.jar"
if (Test-Path $voPlugin) {
    Copy-Item $voPlugin -Destination "$ServerDir/plugins/" -Force
    Write-Host "OK Village Overhaul plugin copied" -ForegroundColor Green
} else {
    Write-Host "X Village Overhaul plugin not found at $voPlugin" -ForegroundColor Red
    Write-Host "  Run 'cd plugin && ./gradlew build' first" -ForegroundColor Yellow
    exit 1
}

# Configure server for headless CI mode
$serverProps = @"
online-mode=false
spawn-protection=0
max-tick-time=-1
enable-command-block=true
gamemode=creative
difficulty=peaceful
spawn-monsters=false
enable-rcon=true
rcon.port=25575
rcon.password=test123
broadcast-rcon-to-ops=false
"@
Set-Content -Path "$ServerDir/server.properties" -Value $serverProps

Write-Host "OK RCON enabled (password: test123, port: 25575)" -ForegroundColor Green

# Verify Paper jar exists
if (!(Test-Path $paperJar)) {
    Write-Host "X Paper jar not found after setup" -ForegroundColor Red
    exit 1
}

Write-Host "=== Headless Paper server ready ===" -ForegroundColor Green
Write-Host "Server directory: $ServerDir" -ForegroundColor Cyan
Write-Host "Paper jar: $paperJar" -ForegroundColor Cyan
Write-Host "Plugins: $(if (Test-Path "$ServerDir/plugins") { (Get-ChildItem "$ServerDir/plugins" -Filter "*.jar").Count } else { 0 })" -ForegroundColor Cyan
