<#
.SYNOPSIS
    从 GitHub Release 拉取加密层四平台产物，落到 crypto/dist/。

.DESCRIPTION
    加密层的源码已经移出为独立仓库（hdppppppp/tools），编译由那边的
    GitHub Actions 负责。本脚本让主项目这边**不装任何交叉编译环境**
    也能拿到产物。

    拉取后目录结构与本地构建产物完全一致，所以接入代码不需要区分
    「产物是本地编的还是下载的」：

        crypto/dist/
        ├── android/
        │   ├── arm64-v8a/libtaotao_crypto.so
        │   └── x86_64/libtaotao_crypto.so
        ├── windows/taotao_crypto.dll
        ├── node/
        │   ├── linux-x64/taotao_crypto.node
        │   └── windows-x64/taotao_crypto.node
        └── wasm/
            ├── taotao_crypto_bg.wasm
            ├── taotao_crypto.js
            └── taotao_crypto.d.ts

.PARAMETER Version
    Release tag，例如 v0.1.0。省略则取最新的**已发布** Release。

    注意：CI 产出的 Release 默认是 draft 状态，draft 不在 latest 的范围内。
    要先在 GitHub 上把它发布出来，或者用 -Version 配合 -Token 拉 draft。

.PARAMETER Repo
    owner/repo，默认 hdppppppp/tools。

.PARAMETER Token
    GitHub token。仓库是私有的，或者要拉 draft Release 时必须提供。
    也可以用环境变量 GH_TOKEN / GITHUB_TOKEN。

.PARAMETER OutDir
    产物落位目录，默认 <仓库根>/crypto/dist。

.PARAMETER Only
    只拉指定的包。可多选：android / windows / node-linux-x64 /
    node-windows-x64 / wasm。省略则全拉。

.PARAMETER SkipChecksum
    跳过 SHA256 校验。默认会校验（Release 里带了 SHA256SUMS.txt）。
    只在 Release 缺校验文件、或你明确知道自己在做什么时用。

.EXAMPLE
    pwsh tools/fetch-crypto.ps1

.EXAMPLE
    # 拉指定版本，只取 Web 和后端用的两份
    pwsh tools/fetch-crypto.ps1 -Version v0.1.0 -Only wasm,node-linux-x64

.EXAMPLE
    # 私有仓库
    $env:GH_TOKEN = 'ghp_xxx'
    pwsh tools/fetch-crypto.ps1 -Version v0.1.0
#>
[CmdletBinding()]
param(
    [string]$Version,

    [string]$Repo = 'hdppppppp/tools',

    [string]$Token = $(if ($env:GH_TOKEN) { $env:GH_TOKEN } else { $env:GITHUB_TOKEN }),

    [string]$OutDir,

    [ValidateSet('android', 'windows', 'node-linux-x64', 'node-windows-x64', 'wasm')]
    [string[]]$Only,

    [switch]$SkipChecksum
)

$ErrorActionPreference = 'Stop'

# 脚本在 tools/ 下，仓库根是上一级。
$RepoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path $RepoRoot 'crypto/dist'
}

# 包名 → 解压目标子目录。
# node 的两个平台分开放：`.node` 是平台相关二进制，混在一起迟早会有人拿错，
# 而拿错的表现是「invalid ELF header」这种跟代码毫无关系的报错。
$Packages = [ordered]@{
    'android'           = 'android'
    'windows'           = 'windows'
    'node-linux-x64'    = 'node/linux-x64'
    'node-windows-x64'  = 'node/windows-x64'
    'wasm'              = 'wasm'
}

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "  $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "警告：$Message" -ForegroundColor Yellow
}

function Get-AuthHeaders {
    $headers = @{ 'Accept' = 'application/vnd.github+json' }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        $headers['Authorization'] = "Bearer $Token"
    }
    return $headers
}

<#
.SYNOPSIS
    解析出 Release 的 tag 和资源下载地址。

.DESCRIPTION
    优先走 API：这样 draft Release 和私有仓库都能处理，
    也能拿到 assets 的真实下载地址（私有仓库的 browser_download_url 需要认证）。

    API 失败时退回公开仓库的直链约定，这样在没网访问 API（或被限流）时
    仍然能拉公开 Release。
#>
function Resolve-Release {
    $headers = Get-AuthHeaders
    $apiBase = "https://api.github.com/repos/$Repo/releases"

    if ([string]::IsNullOrWhiteSpace($Version)) {
        $apiUrl = "$apiBase/latest"
    } else {
        $apiUrl = "$apiBase/tags/$Version"
    }

    try {
        $release = Invoke-RestMethod -Uri $apiUrl -Headers $headers -TimeoutSec 30
        return @{
            Tag    = $release.tag_name
            Assets = @($release.assets | ForEach-Object {
                @{ Name = $_.name; Url = $_.url; BrowserUrl = $_.browser_download_url }
            })
        }
    } catch {
        if (-not [string]::IsNullOrWhiteSpace($Version)) {
            # 指定了版本时退回直链约定。
            $tag = if ($Version.StartsWith('v')) { $Version } else { "v$Version" }
            Write-Warn "GitHub API 不可用（$($_.Exception.Message)），改用直链下载 $tag"
            return @{ Tag = $tag; Assets = @() }
        }
        throw "无法获取 Release 信息：$($_.Exception.Message)`n如果是私有仓库或要拉 draft，请提供 -Token 或设置 GH_TOKEN。"
    }
}

<#
.SYNOPSIS
    下载单个文件。带 token 时走 API 的资源地址（私有仓库必须这样）。
#>
function Save-Asset {
    param(
        [Parameter(Mandatory)] [string]$Url,
        [Parameter(Mandatory)] [string]$Destination,
        [switch]$UseAuth
    )

    $headers = if ($UseAuth) { Get-AuthHeaders } else { @{} }
    $params = @{
        Uri         = $Url
        OutFile     = $Destination
        TimeoutSec  = 300
        ErrorAction = 'Stop'
    }
    if ($UseAuth) {
        # 走 API 下载二进制资源时，Accept 必须退回默认值，
        # 用 application/vnd.github+json 会拿到 JSON 元数据而不是文件本身。
        $params['Headers'] = @{ 'Authorization' = "Bearer $Token" }
    }
    Invoke-WebRequest @params | Out-Null
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -Path $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------

$wanted = if ($Only) { $Only } else { @($Packages.Keys) }

Write-Step "解析 Release（$Repo）"
$release = Resolve-Release
Write-Ok "tag：$($release.Tag)"
if ($release.Assets.Count -gt 0) {
    Write-Ok "发现 $($release.Assets.Count) 个资源"
}

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) "taotao-crypto-fetch-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null

try {
    # ---- 下载校验文件 ----
    $checksums = @{}
    if (-not $SkipChecksum) {
        $sumsFile = Join-Path $tempDir 'SHA256SUMS.txt'
        $sumsAsset = $release.Assets | Where-Object { $_.Name -eq 'SHA256SUMS.txt' } | Select-Object -First 1
        try {
            if ($sumsAsset) {
                if (-not [string]::IsNullOrWhiteSpace($Token)) {
                    Save-Asset -Url $sumsAsset.Url -Destination $sumsFile -UseAuth
                } else {
                    Save-Asset -Url $sumsAsset.BrowserUrl -Destination $sumsFile
                }
            } else {
                Save-Asset -Url "https://github.com/$Repo/releases/download/$($release.Tag)/SHA256SUMS.txt" -Destination $sumsFile
            }
            foreach ($line in Get-Content $sumsFile) {
                # 格式：<64位十六进制>  ./android.zip
                if ($line -match '^([0-9a-fA-F]{64})\s+\*?\.?/?(.+)$') {
                    $checksums[$Matches[2].Trim()] = $Matches[1].ToLowerInvariant()
                }
            }
            Write-Ok "已载入 $($checksums.Count) 条校验和"
        } catch {
            Write-Warn "拿不到 SHA256SUMS.txt（$($_.Exception.Message)），本次跳过校验"
            $checksums = @{}
        }
    }

    # ---- 逐个下载并解压 ----
    $failures = @()
    foreach ($name in $wanted) {
        $zipName = "$name.zip"
        $zipPath = Join-Path $tempDir $zipName
        $targetDir = Join-Path $OutDir $Packages[$name]

        Write-Step "拉取 $zipName"

        $asset = $release.Assets | Where-Object { $_.Name -eq $zipName } | Select-Object -First 1
        $url = if ($asset) { $asset.BrowserUrl } else { "https://github.com/$Repo/releases/download/$($release.Tag)/$zipName" }

        try {
            if ($asset -and -not [string]::IsNullOrWhiteSpace($Token)) {
                Save-Asset -Url $asset.Url -Destination $zipPath -UseAuth
            } else {
                Save-Asset -Url $url -Destination $zipPath
            }
        } catch {
            Write-Warn "下载失败：$($_.Exception.Message)"
            $failures += $name
            continue
        }

        # ---- 校验 ----
        if ($checksums.Count -gt 0) {
            if ($checksums.ContainsKey($zipName)) {
                $actual = Get-Sha256 $zipPath
                if ($actual -ne $checksums[$zipName]) {
                    Write-Warn "校验和不匹配！期望 $($checksums[$zipName])，实得 $actual"
                    Write-Warn "包可能损坏或被篡改，已跳过 $name。"
                    $failures += $name
                    continue
                }
                Write-Ok "SHA256 校验通过"
            } else {
                Write-Warn "$zipName 不在 SHA256SUMS.txt 里，无法校验"
            }
        }

        # ---- 解压 ----
        # 先删旧目录再解压，避免上一版的残留文件混进新产物里 ——
        # 那会导致「明明更新了却还在跑旧 .so」这种极难排查的问题。
        if (Test-Path $targetDir) {
            Remove-Item -Path $targetDir -Recurse -Force
        }
        New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
        Expand-Archive -Path $zipPath -DestinationPath $targetDir -Force

        $files = Get-ChildItem -Path $targetDir -Recurse -File
        $totalKb = [math]::Round((($files | Measure-Object -Property Length -Sum).Sum / 1KB), 1)
        Write-Ok "解压到 $targetDir（$($files.Count) 个文件，$totalKb KB）"
    }

    # ---- 汇报 ----
    Write-Step '结果'
    if ($failures.Count -gt 0) {
        Write-Warn "以下包未取到：$($failures -join ', ')"
    }

    Write-Host ""
    Write-Host "产物目录：$OutDir" -ForegroundColor Cyan
    Get-ChildItem -Path $OutDir -Recurse -File -ErrorAction SilentlyContinue |
        ForEach-Object {
            $relative = $_.FullName.Substring($OutDir.Length + 1)
            Write-Host ("  {0,-52} {1,10:N0} 字节" -f $relative, $_.Length)
        }

    Write-Host ""
    if ($failures.Count -gt 0) {
        Write-Host "有包未取到，退出码 1。" -ForegroundColor Yellow
        exit 1
    }
    Write-Host "完成。含真实 PSK 的产物请勿外传。" -ForegroundColor Green
}
finally {
    Remove-Item -Path $tempDir -Recurse -Force -ErrorAction SilentlyContinue
}
