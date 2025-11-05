<#
.SYNOPSIS
    Bot player simulation module for automated testing of Village Overhaul plugin

.DESCRIPTION
    Provides functions to simulate player interactions with custom villagers via RCON.
    Uses Minecraft commands to spawn entities, teleport players, and trigger interactions.
    
    This module enables headless testing without requiring actual Minecraft clients.
#>

<#
.SYNOPSIS
    Enable RCON on a Paper server

.DESCRIPTION
    Configures server.properties to enable RCON with a random password for testing

.PARAMETER ServerDir
    Server directory containing server.properties

.PARAMETER Port
    RCON port (default: 25575)

.OUTPUTS
    Returns RCON password for use with Send-RconCommand
#>
function Enable-Rcon {
    param(
        [Parameter(Mandatory=$true)]
        [string]$ServerDir,
        
        [int]$Port = 25575
    )
    
    $propsFile = Join-Path $ServerDir "server.properties"
    if (!(Test-Path $propsFile)) {
        throw "server.properties not found in $ServerDir"
    }
    
    # Generate random password
    $password = -join ((65..90) + (97..122) + (48..57) | Get-Random -Count 16 | ForEach-Object {[char]$_})
    
    # Read current properties
    $props = Get-Content $propsFile
    
    # Update RCON settings
    $props = $props | ForEach-Object {
        if ($_ -match '^enable-rcon=') {
            "enable-rcon=true"
        }
        elseif ($_ -match '^rcon\.port=') {
            "rcon.port=$Port"
        }
        elseif ($_ -match '^rcon\.password=') {
            "rcon.password=$password"
        }
        else {
            $_
        }
    }
    
    # Add properties if they don't exist
    if ($props -notmatch 'enable-rcon=') {
        $props += "enable-rcon=true"
    }
    if ($props -notmatch 'rcon\.port=') {
        $props += "rcon.port=$Port"
    }
    if ($props -notmatch 'rcon\.password=') {
        $props += "rcon.password=$password"
    }
    
    Set-Content -Path $propsFile -Value $props
    
    Write-Host "OK RCON enabled on port $Port" -ForegroundColor Green
    
    return $password
}

<#
.SYNOPSIS
    Send RCON command to Paper server

.DESCRIPTION
    Sends a command via RCON protocol using full binary implementation.
    PowerShell 5.1 compatible.

.PARAMETER ServerAddress
    Server address (default: localhost)

.PARAMETER Port
    RCON port (default: 25575)

.PARAMETER Password
    RCON password

.PARAMETER Command
    Command to execute (without leading slash)

.OUTPUTS
    Command response from server
#>
function Send-RconCommand {
    param(
        [string]$ServerAddress = "localhost",
        [int]$Port = 25575,
        [Parameter(Mandatory=$true)]
        [string]$Password,
        [Parameter(Mandatory=$true)]
        [string]$Command
    )
    
    # Full RCON protocol implementation for PowerShell 5.1
    try {
        $tcpClient = New-Object System.Net.Sockets.TcpClient
        $tcpClient.Connect($ServerAddress, $Port)
        
        if (!$tcpClient.Connected) {
            Write-Error "Failed to connect to RCON at ${ServerAddress}:${Port}"
            return $null
        }
        
        $stream = $tcpClient.GetStream()
        
        # RCON packet helper functions
        function Send-Packet {
            param($RequestId, $Type, $Body)
            
            $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($Body)
            $packetSize = 10 + $bodyBytes.Length  # 4(id) + 4(type) + body + 2(nulls)
            
            $packet = New-Object byte[] ($packetSize + 4)  # +4 for size field
            
            # Write size (little-endian)
            [BitConverter]::GetBytes([int32]$packetSize).CopyTo($packet, 0)
            # Write request ID
            [BitConverter]::GetBytes([int32]$RequestId).CopyTo($packet, 4)
            # Write type
            [BitConverter]::GetBytes([int32]$Type).CopyTo($packet, 8)
            # Write body
            $bodyBytes.CopyTo($packet, 12)
            # Null terminators are already 0
            
            $stream.Write($packet, 0, $packet.Length)
            $stream.Flush()
        }
        
        function Read-Packet {
            $sizeBytes = New-Object byte[] 4
            $stream.Read($sizeBytes, 0, 4) | Out-Null
            $size = [BitConverter]::ToInt32($sizeBytes, 0)
            
            $payloadBytes = New-Object byte[] $size
            $stream.Read($payloadBytes, 0, $size) | Out-Null
            
            $requestId = [BitConverter]::ToInt32($payloadBytes, 0)
            $type = [BitConverter]::ToInt32($payloadBytes, 4)
            
            # Body is from byte 8 to size-2 (excluding null terminators)
            $bodyLength = $size - 10
            if ($bodyLength -gt 0) {
                $body = [System.Text.Encoding]::ASCII.GetString($payloadBytes, 8, $bodyLength)
            } else {
                $body = ""
            }
            
            return @{
                RequestId = $requestId
                Type = $type
                Body = $body
            }
        }
        
        # Authenticate
        Send-Packet -RequestId 1 -Type 3 -Body $Password
        $authResponse = Read-Packet
        
        if ($authResponse.RequestId -ne 1) {
            Write-Error "RCON authentication failed"
            return $null
        }
        
        # Send command
        Send-Packet -RequestId 2 -Type 2 -Body $Command
        $cmdResponse = Read-Packet
        
        # Cleanup
        $stream.Close()
        $tcpClient.Close()
        
        # Return response body
        return $cmdResponse.Body
        
    } catch {
        Write-Error "RCON error: $_"
        return $null
    }
}

<#
.SYNOPSIS
    Create a bot player on the server

.DESCRIPTION
    Spawns a fake player entity that can be controlled via commands.
    Uses Minecraft's /player command (from Carpet mod) or summons NPC.

.PARAMETER PlayerName
    Name for the bot player

.PARAMETER X
    X coordinate to spawn at

.PARAMETER Y
    Y coordinate to spawn at

.PARAMETER Z
    Z coordinate to spawn at

.PARAMETER World
    World name (default: world)

.OUTPUTS
    Bot player info hashtable
#>
function New-BotPlayer {
    param(
        [Parameter(Mandatory=$true)]
        [string]$PlayerName,
        
        [double]$X = 0,
        [double]$Y = 64,
        [double]$Z = 0,
        
        [string]$World = "world"
    )
    
    return @{
        Name = $PlayerName
        X = $X
        Y = $Y
        Z = $Z
        World = $World
        Spawned = $false
    }
}

<#
.SYNOPSIS
    Spawn a custom villager for testing

.DESCRIPTION
    Uses Village Overhaul plugin commands to spawn a custom villager.
    Assumes plugin provides /vo spawn-villager or similar command.

.PARAMETER VillagerType
    Type of custom villager (e.g., "blacksmith", "merchant")

.PARAMETER X
    X coordinate

.PARAMETER Y
    Y coordinate

.PARAMETER Z
    Z coordinate

.PARAMETER World
    World name

.OUTPUTS
    Commands to execute on server startup
#>
function New-CustomVillager {
    param(
        [Parameter(Mandatory=$true)]
        [string]$VillagerType,
        
        [double]$X = 0,
        [double]$Y = 64,
        [double]$Z = 0,
        
        [string]$World = "world"
    )
    
    # Return command that will be written to server commands file
    return "execute in $World run summon minecraft:villager $X $Y $Z {CustomName:'""$VillagerType""',Tags:['vo_custom','vo_test']}"
}

<#
.SYNOPSIS
    Simulate player interaction with entity

.DESCRIPTION
    Generates commands to simulate a player right-clicking an entity.
    This uses command blocks or plugin commands to trigger interaction events.

.PARAMETER PlayerName
    Bot player name

.PARAMETER EntitySelector
    Entity selector (e.g., @e[tag=vo_custom,limit=1])

.OUTPUTS
    Command string to execute
#>
function Invoke-PlayerInteraction {
    param(
        [Parameter(Mandatory=$true)]
        [string]$PlayerName,
        
        [Parameter(Mandatory=$true)]
        [string]$EntitySelector
    )
    
    # Teleport player to entity first
    $teleportCmd = "tp $PlayerName $EntitySelector"
    
    # Trigger interaction via plugin command (assumes Village Overhaul provides this)
    # Alternative: Use NMS to trigger PlayerInteractEntityEvent
    $interactCmd = "vo test-interaction $PlayerName $EntitySelector"
    
    return @($teleportCmd, $interactCmd)
}

<#
.SYNOPSIS
    Create server startup commands file

.DESCRIPTION
    Writes commands to a file that will be executed on server startup.
    This is used to set up test scenarios automatically.

.PARAMETER ServerDir
    Server directory

.PARAMETER Commands
    Array of commands to execute

.PARAMETER DelayTicks
    Delay in ticks between commands (default: 20 = 1 second)

.OUTPUTS
    Path to commands file
#>
function New-ServerCommandsFile {
    param(
        [Parameter(Mandatory=$true)]
        [string]$ServerDir,
        
        [Parameter(Mandatory=$true)]
        [string[]]$Commands,
        
        [int]$DelayTicks = 20
    )
    
    $commandsFile = Join-Path $ServerDir "startup-commands.txt"
    
    # Write commands with delays
    $output = @()
    foreach ($cmd in $Commands) {
        $output += $cmd
        if ($DelayTicks -gt 0) {
            # Note: This requires a command executor plugin or data pack
            $output += "# Wait $DelayTicks ticks"
        }
    }
    
    Set-Content -Path $commandsFile -Value $output
    
    Write-Host "OK Created commands file: $commandsFile" -ForegroundColor Green
    return $commandsFile
}

<#
.SYNOPSIS
    Wait for specific log pattern in server logs

.DESCRIPTION
    Monitors server log file for a specific pattern, used to verify actions completed.

.PARAMETER LogFile
    Path to server log file

.PARAMETER Pattern
    Regex pattern to search for

.PARAMETER TimeoutSeconds
    Maximum time to wait (default: 30)

.OUTPUTS
    $true if pattern found, $false if timeout
#>
function Wait-ForLogPattern {
    param(
        [Parameter(Mandatory=$true)]
        [string]$LogFile,
        
        [Parameter(Mandatory=$true)]
        [string]$Pattern,
        
        [int]$TimeoutSeconds = 30
    )
    
    $elapsed = 0
    $found = $false
    
    while ($elapsed -lt $TimeoutSeconds) {
        if (Test-Path $LogFile) {
            $content = Get-Content $LogFile -Raw -ErrorAction SilentlyContinue
            if ($content -match $Pattern) {
                $found = $true
                break
            }
        }
        
        Start-Sleep -Seconds 1
        $elapsed++
    }
    
    return $found
}

Export-ModuleMember -Function Enable-Rcon, Send-RconCommand, New-BotPlayer, New-CustomVillager, 
                              Invoke-PlayerInteraction, New-ServerCommandsFile, Wait-ForLogPattern
