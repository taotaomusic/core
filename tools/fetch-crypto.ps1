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
    Release tag。默认 `dev-latest` —— main 分支的开发构建，用占位密钥，
    免登录就能下载，适合联调。

    生产版本传具体 tag（例如 `v2.0.0`）。正式 Release 自动发布（非 draft），
    公开仓库可直链下载；私有仓库需 -Token。

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
    pwsh tools/fetch-crypto.ps1 -Version v2.0.0 -Only wasm,node-linux-x64

.EXAMPLE
    # 私有仓库
    $env:GH_TOKEN = 'ghp_xxx'
    pwsh tools/fetch-crypto.ps1 -Version v2.0.0
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

# 关掉进度条。这不是洁癖：Invoke-WebRequest 每写一块数据就重绘一次进度条，
# 在控制台里会刷出几千行控制字符，既把真正的输出淹掉（重定向到文件时
# 日志几乎全是进度条），又因为每次重绘都要写终端而明显拖慢下载速度。
$ProgressPreference = 'SilentlyContinue'

# ---- Windows PowerShell 5.1 的两处兼容性加固 ----
#
# ① 默认的 SecurityProtocol 是 SystemDefault，在老系统上可能协商到
#    TLS 1.0/1.1，而 GitHub 要求 1.2+。显式设一次，代价为零。
# ② Invoke-* 默认走 IE 解析引擎，在没有 IE 的环境（Server Core、容器、
#    精简镜像）会直接失败。PS 6+ 已经默认 basic parsing 并移除了这个
#    参数，所以要按版本判断 —— 传了会报「找不到参数」。
if ($PSVersionTable.PSVersion.Major -lt 6) {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $BasicParsing = @{ UseBasicParsing = $true }
} else {
    $BasicParsing = @{}
}

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

    # 默认取开发构建。它是个 prerelease，所以不能用 /releases/latest
    # （那个只指向正式的最新版，而正式版目前可能还不存在）。
    if ([string]::IsNullOrWhiteSpace($Version)) {
        $Version = 'dev-latest'
    }

    $apiUrl = "$apiBase/tags/$Version"

    try {
        $release = Invoke-RestMethod -Uri $apiUrl -Headers $headers -TimeoutSec 30
        return @{
            Tag    = $release.tag_name
            Assets = @($release.assets | ForEach-Object {
                @{ Name = $_.name; Url = $_.url; BrowserUrl = $_.browser_download_url }
            })
        }
    } catch {
        # API 不可用时退回直链约定（公开仓库的 Release assets 是直链）。
        # 注意不要自作聪明补 `v` 前缀 —— `dev-latest` 本来就不带 v，
        # 补上会变成 `vdev-latest` 这种不存在的 tag。
        Write-Warn "GitHub API 不可用（$($_.Exception.Message)），改用直链下载 $Version"
        return @{ Tag = $Version; Assets = @() }
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
    Invoke-WebRequest @params @BasicParsing | Out-Null
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
