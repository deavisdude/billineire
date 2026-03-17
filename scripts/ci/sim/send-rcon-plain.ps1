$pwLine = (Select-String -Path 'test-server\server.properties' -Pattern 'rcon.password').ToString()
$pw = $pwLine -replace 'rcon.password=',''
Write-Host "Using RCON password: $pw"

$tcpClient = New-Object System.Net.Sockets.TcpClient
$tcpClient.Connect('127.0.0.1', 25575)
$stream = $tcpClient.GetStream()

function Send-Packet {
    param($RequestId, $Type, $Body)
    $bodyBytes = [System.Text.Encoding]::ASCII.GetBytes($Body)
    $packetSize = 10 + $bodyBytes.Length
    $packet = New-Object byte[] ($packetSize + 4)
    [BitConverter]::GetBytes([int32]$packetSize).CopyTo($packet, 0)
    [BitConverter]::GetBytes([int32]$RequestId).CopyTo($packet, 4)
    [BitConverter]::GetBytes([int32]$Type).CopyTo($packet, 8)
    $bodyBytes.CopyTo($packet, 12)
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
    $bodyLength = $size - 10
    if ($bodyLength -gt 0) { $body = [System.Text.Encoding]::ASCII.GetString($payloadBytes, 8, $bodyLength) } else { $body = "" }
    return @{ RequestId = $requestId; Type = $type; Body = $body }
}

# Authenticate
Send-Packet -RequestId 1 -Type 3 -Body $pw
$authResponse = Read-Packet
Write-Host "Auth response ID: $($authResponse.RequestId)"

# Send command
Send-Packet -RequestId 2 -Type 2 -Body 'votest fixed-layout 12345 3'
$cmdResponse = Read-Packet
Write-Host "Command response: $($cmdResponse.Body)"

$stream.Close()
$tcpClient.Close()
