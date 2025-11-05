# CI Scenario: Culture Pack Validation
param(
    [string]$CultureDir = "plugin/src/main/resources/cultures",
    [string]$SchemaFile = "plugin/src/main/resources/schemas/culture.json",
    [string]$OutputJson = "culture-validation-report.json"
)

$ErrorActionPreference = "Stop"
Write-Host "=== CI Scenario: Culture Pack Validation ===" -ForegroundColor Cyan

if (-not (Test-Path $SchemaFile)) {
    Write-Host "X Culture schema not found: $SchemaFile" -ForegroundColor Red
    exit 1
}
Write-Host "OK Culture schema found: $SchemaFile" -ForegroundColor Green

$manifestPath = Join-Path $CultureDir "_manifest.txt"
if (-not (Test-Path $manifestPath)) {
    Write-Host "X Manifest not found: $manifestPath" -ForegroundColor Red
    exit 1
}

$cultureFiles = Get-Content $manifestPath | Where-Object { $_ -match '\.json$' }
Write-Host "OK Found $($cultureFiles.Count) culture files in manifest" -ForegroundColor Green

$results = @{
    timestamp = (Get-Date).ToString("o")
    schema = $SchemaFile
    totalFiles = $cultureFiles.Count
    validFiles = 0
    invalidFiles = 0
    errors = @()
}

foreach ($fileName in $cultureFiles) {
    $culturePath = Join-Path $CultureDir $fileName
    
    if (-not (Test-Path $culturePath)) {
        Write-Host "X Culture file not found: $culturePath" -ForegroundColor Red
        $results.errors += @{ file = $fileName; error = "File not found" }
        $results.invalidFiles++
        continue
    }
    
    try {
        $cultureData = Get-Content $culturePath -Raw | ConvertFrom-Json
        $errors = @()
        
        # Required fields per schema
        if (-not $cultureData.id) { $errors += "Missing required field: id" }
        if (-not $cultureData.name) { $errors += "Missing required field: name" }
        if (-not $cultureData.structureSet -or $cultureData.structureSet.Count -eq 0) {
            $errors += "Missing or empty structureSet array"
        }
        if (-not $cultureData.professionSet -or $cultureData.professionSet.Count -eq 0) {
            $errors += "Missing or empty professionSet array"
        }
        
        # Validate ID format (lowercase, alphanumeric, underscores)
        if ($cultureData.id -and $cultureData.id -notmatch '^[a-z0-9_]+$') {
            $errors += "Invalid id format: must be lowercase alphanumeric with underscores"
        }
        
        if ($errors.Count -gt 0) {
            Write-Host "X $fileName - Validation errors:" -ForegroundColor Red
            foreach ($err in $errors) { Write-Host "  - $err" -ForegroundColor Red }
            $results.errors += @{ file = $fileName; errors = $errors }
            $results.invalidFiles++
        } else {
            Write-Host "OK $fileName - Valid" -ForegroundColor Green
            $results.validFiles++
        }
    } catch {
        Write-Host "X $fileName - JSON parse error: $($_.Exception.Message)" -ForegroundColor Red
        $results.errors += @{ file = $fileName; error = "JSON parse error: $($_.Exception.Message)" }
        $results.invalidFiles++
    }
}

$results.passed = ($results.invalidFiles -eq 0)
$resultsJson = $results | ConvertTo-Json -Depth 10
Set-Content -Path $OutputJson -Value $resultsJson

Write-Host "`n=== Validation Summary ===" -ForegroundColor Cyan
Write-Host "Total files: $($results.totalFiles)" -ForegroundColor Gray
Write-Host "Valid files: $($results.validFiles)" -ForegroundColor Green
Write-Host "Invalid files: $($results.invalidFiles)" -ForegroundColor $(if ($results.invalidFiles -gt 0) { "Red" } else { "Green" })
Write-Host "Results written to: $OutputJson" -ForegroundColor Gray

if ($results.passed) {
    Write-Host "`nOK All culture files are valid!" -ForegroundColor Green
    exit 0
} else {
    Write-Host "`nX Some culture files failed validation" -ForegroundColor Red
    exit 1
}
