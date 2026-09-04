$ErrorActionPreference = "Stop"

Set-Location $PSScriptRoot\..

$secureToken = Read-Host "请粘贴 Outlook access token（输入不会显示）" -AsSecureString
$tokenPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)

try {
    $env:OUTLOOK_ACCESS_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($tokenPtr)
    $env:OUTLOOK_TO = "kiwipro@qq.com"
    npm run send-mail
}
finally {
    if ($tokenPtr -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($tokenPtr)
    }
    Remove-Item Env:OUTLOOK_ACCESS_TOKEN -ErrorAction SilentlyContinue
    Remove-Item Env:OUTLOOK_TO -ErrorAction SilentlyContinue
}
