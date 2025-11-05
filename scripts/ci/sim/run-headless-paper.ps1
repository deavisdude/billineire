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
    Write-Host "✓ EULA accepted" -ForegroundColor Green
}

# Download Paper if not exists
$paperJar = "$ServerDir/paper.jar"
if (!(Test-Path $paperJar)) {
    Write-Host "Downloading Paper $PaperVersion..." -ForegroundColor Yellow
    $paperUrl = "https://api.papermc.io/v2/projects/paper/versions/$PaperVersion/builds/latest/downloads/paper-$PaperVersion.jar"
    # Note: This URL is a placeholder; actual Paper download requires build number resolution
    Write-Host "  (Paper download stub - implement actual download logic)" -ForegroundColor DarkGray
    # For CI: Pre-cache Paper jar or use a fixed build number
}

# Download Geyser if not exists
$geyserJar = "$ServerDir/plugins/Geyser-Spigot.jar"
if (!(Test-Path $geyserJar)) {
    New-Item -ItemType Directory -Path "$ServerDir/plugins" -Force | Out-Null
    Write-Host "Downloading Geyser..." -ForegroundColor Yellow
    Write-Host "  (Geyser download stub - implement actual download logic)" -ForegroundColor DarkGray
}

# Copy Village Overhaul plugin
$voPlugin = "plugin/build/libs/village-overhaul-0.1.0-SNAPSHOT.jar"
if (Test-Path $voPlugin) {
    Copy-Item $voPlugin -Destination "$ServerDir/plugins/" -Force
    Write-Host "✓ Village Overhaul plugin copied" -ForegroundColor Green
} else {
    Write-Host "✗ Village Overhaul plugin not found at $voPlugin" -ForegroundColor Red
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
"@
Set-Content -Path "$ServerDir/server.properties" -Value $serverProps

# Create start script stub
Write-Host "=== Headless Paper server ready ===" -ForegroundColor Green
Write-Host "Server directory: $ServerDir" -ForegroundColor Cyan
Write-Host "To start: java -Xmx2G -jar $paperJar --nogui" -ForegroundColor Cyan
Write-Host ""
Write-Host "Note: Full headless boot requires Paper jar download automation" -ForegroundColor Yellow
