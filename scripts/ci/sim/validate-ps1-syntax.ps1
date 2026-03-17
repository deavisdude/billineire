param([string]$Path)

if (-not (Test-Path $Path)) {
    Write-Host "ERROR: Path not found: $Path" -ForegroundColor Red
    exit 2
}

$content = Get-Content -Raw -Path $Path
try {
    [System.Management.Automation.Language.Parser]::ParseInput($content, [ref]$null, [ref]$null) | Out-Null
    Write-Host "SYNTAX_OK: $Path"
    exit 0
} catch {
    Write-Host "SYNTAX_ERROR: $Path" -ForegroundColor Red
    Write-Host $_.Exception.Message
    exit 1
}
