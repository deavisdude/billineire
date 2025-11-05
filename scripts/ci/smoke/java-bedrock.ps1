param(
    [switch]$FailOnTodo
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..' '..' '..')).Path
$matrixPath = Join-Path $repoRoot 'docs' 'compatibility-matrix.md'
if (-not (Test-Path $matrixPath)) {
    throw "Compatibility matrix not found at $matrixPath"
}

$matrixContent = Get-Content $matrixPath -Raw
$scriptPathFragment = 'scripts/ci/smoke/java-bedrock.ps1'
if ($matrixContent -notmatch [Regex]::Escape($scriptPathFragment)) {
    throw "Matrix does not reference $scriptPathFragment"
}

Write-Host 'Matrix entry confirmed for Java + Bedrock bridge profile.'
Write-Warning 'TODO: Provision Java server with Bedrock bridge and scripted Bedrock client.'

if ($FailOnTodo.IsPresent) {
    throw 'Java + Bedrock smoke test not yet implemented. Use -FailOnTodo to gate merges.'
}
