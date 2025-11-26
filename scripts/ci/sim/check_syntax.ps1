$scriptPath = Join-Path $PSScriptRoot 'test-path-determinism.ps1'
$s = Get-Content $scriptPath -Raw
$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseInput($s, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors -and $errors.Count -gt 0) {
	foreach ($e in $errors) {
		Write-Host '----'
		Write-Host ('Message: {0}' -f $e.Message)
		Write-Host ('StartLine: {0} StartCol: {1}' -f $e.Extent.StartLineNumber, $e.Extent.StartColumn)
		Write-Host 'Text:'
		Write-Host $e.Extent.Text
	}
	exit 1
} else { Write-Host 'PARSE OK'; exit 0 }