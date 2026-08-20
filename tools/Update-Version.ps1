param([string]$VersionFile = "$(Join-Path $PSScriptRoot '..\version.properties')")

$properties = @{}
Get-Content $VersionFile | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $properties[$matches[1]] = $matches[2] }
}
$properties['VERSION_CODE'] = ([int]$properties['VERSION_CODE']) + 1
$nameParts = $properties['VERSION_NAME'].Split('.')
$nameParts[2] = ([int]$nameParts[2]) + 1
$properties['VERSION_NAME'] = $nameParts -join '.'
@("VERSION_CODE=$($properties['VERSION_CODE'])", "VERSION_NAME=$($properties['VERSION_NAME'])") | Set-Content -Path $VersionFile -Encoding UTF8
Write-Output "版本已更新为 $($properties['VERSION_NAME']) ($($properties['VERSION_CODE']))"
