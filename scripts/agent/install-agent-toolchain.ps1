<#
.SYNOPSIS
    Install or refresh the local agent tooling used in this workspace.

.DESCRIPTION
    Installs fast shell utilities plus a small set of agent-centric CLI tools on
    Windows so both humans and AI agents have a stronger baseline environment.

.PARAMETER SkipWinget
    Skip installing winget-managed packages.

.PARAMETER SkipNpm
    Skip installing npm global packages.

.PARAMETER SkipUvTools
    Skip installing uv-managed tools.
#>

param(
    [switch]$SkipWinget = $false,
    [switch]$SkipNpm = $false,
    [switch]$SkipUvTools = $false
)

$ErrorActionPreference = 'Stop'

function Reset-PathEnvironment {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($machinePath -and $userPath) {
        $env:Path = $machinePath + ';' + $userPath
    }
}

function Test-CommandAvailable {
    param([string]$Name)

    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Install-WingetPackage {
    param(
        [string]$Id,
        [string]$CommandName
    )

    if (Test-CommandAvailable $CommandName) {
        Write-Host "OK Found $CommandName" -ForegroundColor Green
        return
    }

    Write-Host "Installing $Id via winget..." -ForegroundColor Cyan
    $wingetOutput = winget install --id $Id --exact --source winget --accept-source-agreements --accept-package-agreements 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        $wingetListOutput = winget list --id $Id --exact --source winget 2>&1 | Out-String
        if ($wingetListOutput -match [regex]::Escape($Id) -or $wingetOutput -match 'No newer package versions are available') {
            Write-Host "OK $Id is already installed" -ForegroundColor Green
        } else {
            throw "winget install failed for $Id"
        }
    }

    Reset-PathEnvironment

    if (Test-CommandAvailable $CommandName) {
        Write-Host "OK Installed $CommandName" -ForegroundColor Green
    } else {
        Write-Host "! Installed $Id but $CommandName is not visible in the current session yet" -ForegroundColor Yellow
    }
}

function Install-NpmPackage {
    param(
        [string]$PackageName,
        [string]$CommandName
    )

    if (Test-CommandAvailable $CommandName) {
        Write-Host "OK Found $CommandName" -ForegroundColor Green
        return
    }

    Write-Host "Installing $PackageName via npm..." -ForegroundColor Cyan
    npm install -g $PackageName
    if ($LASTEXITCODE -ne 0) {
        throw "npm install failed for $PackageName"
    }

    Reset-PathEnvironment

    if (Test-CommandAvailable $CommandName) {
        Write-Host "OK Installed $CommandName" -ForegroundColor Green
    } else {
        Write-Host "! Installed $PackageName but $CommandName is not visible in the current session yet" -ForegroundColor Yellow
    }
}

function Install-UvTool {
    param(
        [string[]]$InstallArgs,
        [string]$DisplayName,
        [string]$CommandName
    )

    if (Test-CommandAvailable $CommandName) {
        Write-Host "OK Found $CommandName" -ForegroundColor Green
        return
    }

    Write-Host "Installing $DisplayName via uv tool install..." -ForegroundColor Cyan
    uv tool install --force @InstallArgs
    if ($LASTEXITCODE -ne 0) {
        throw "uv tool install failed for $DisplayName"
    }

    Reset-PathEnvironment

    if (Test-CommandAvailable $CommandName) {
        Write-Host "OK Installed $CommandName" -ForegroundColor Green
    } else {
        Write-Host "! Installed $DisplayName but $CommandName is not visible in the current session yet" -ForegroundColor Yellow
    }
}

Write-Host '=== spec-billineire Agent Toolchain Bootstrap ===' -ForegroundColor Cyan
Reset-PathEnvironment

if (-not $SkipWinget) {
    if (-not (Test-CommandAvailable 'winget')) {
        throw 'winget is required for the winget package install phase.'
    }

    Install-WingetPackage -Id 'BurntSushi.ripgrep.MSVC' -CommandName 'rg'
    Install-WingetPackage -Id 'sharkdp.fd' -CommandName 'fd'
    Install-WingetPackage -Id 'jqlang.jq' -CommandName 'jq'
    Install-WingetPackage -Id 'dandavison.delta' -CommandName 'delta'
}

if (-not $SkipNpm) {
    if (-not (Test-CommandAvailable 'npm')) {
        throw 'npm is required for the npm package install phase.'
    }

    Install-NpmPackage -PackageName '@google/gemini-cli' -CommandName 'gemini'
    Install-NpmPackage -PackageName '@modelcontextprotocol/inspector' -CommandName 'mcp-inspector'
}

if (-not $SkipUvTools) {
    if (-not (Test-CommandAvailable 'uv')) {
        throw 'uv is required for the uv tool install phase.'
    }

    Install-UvTool -InstallArgs @('aider-chat') -DisplayName 'aider-chat' -CommandName 'aider'
    Install-UvTool -InstallArgs @('--from', 'git+https://github.com/oraios/serena', 'serena-agent') -DisplayName 'serena-agent' -CommandName 'serena'
}

Write-Host ''
Write-Host '=== Verification ===' -ForegroundColor Cyan
foreach ($name in @('rg', 'fd', 'jq', 'delta', 'gemini', 'mcp-inspector', 'aider', 'serena')) {
    if (Test-CommandAvailable $name) {
        Write-Host "OK $name" -ForegroundColor Green
    } else {
        Write-Host "! $name not found in current session" -ForegroundColor Yellow
    }
}