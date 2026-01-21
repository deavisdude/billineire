$module = (Resolve-Path 'scripts\ci\sim\BotPlayer.psm1').Path
. "$module"
$pwLine = (Get-Content 'test-server\server.properties' | Select-String -Pattern 'rcon.password').ToString()
$pw = $pwLine -replace 'rcon\.password=',''
Write-Host "Using RCON password: $pw"
$resp = Send-RconCommand -Password $pw -Command 'votest fixed-layout 12345 3'
Write-Host 'RCON response:'
Write-Host $resp
