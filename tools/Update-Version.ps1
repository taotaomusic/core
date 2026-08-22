param([string]$VersionFile = "$(Join-Path $PSScriptRoot '..\version.properties')")

$properties = @{}
Get-Content $VersionFile | ForEach-Object {
    # 去掉可能存在的 UTF-8 BOM，否则第一个键名会带上不可见字符。
    if ($_.TrimStart([char]0xFEFF) -match '^([^=]+)=(.*)$') { $properties[$matches[1]] = $matches[2] }
}
$properties['VERSION_CODE'] = ([int]$properties['VERSION_CODE']) + 1
$nameParts = $properties['VERSION_NAME'].Split('.')
$nameParts[2] = ([int]$nameParts[2]) + 1
$properties['VERSION_NAME'] = $nameParts -join '.'
$lines = @("VERSION_CODE=$($properties['VERSION_CODE'])", "VERSION_NAME=$($properties['VERSION_NAME'])")
# Windows PowerShell 5.1 的 -Encoding UTF8 会写入 BOM，Java 的 Properties.load 会把 BOM 当作键名的一部分，
# 导致下一次构建读不到 VERSION_CODE。这里显式使用不带 BOM 的 UTF-8 写入。
[System.IO.File]::WriteAllLines($VersionFile, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output "版本已更新为 $($properties['VERSION_NAME']) ($($properties['VERSION_CODE']))"
