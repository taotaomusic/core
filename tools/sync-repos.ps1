# tools/sync-repos.ps1
# 将本仓库（monorepo 工作副本）内容同步到三个 GitHub 正式仓库：
#   - server/ 目录     -> git@github.com:hdppppppp/music-server.git（server/ 内容作为仓库根）
#   - crypto-src/ 目录 -> git@github.com:hdppppppp/tools.git（crypto-src/ 内容作为仓库根，加密层构建）
#   - 其余全部内容     -> git@github.com:hdppppppp/music.git（去掉 server/ 与 crypto-src/ 后的主仓库根）
#
# 三个 GitHub 仓库都是「快照镜像 + 云端构建」；gitee 的 origin 保留完整 monorepo 作总备份。
#
# 机制说明：
#   - 同步只取已提交内容（HEAD），工作区未提交的改动不会同步出去。
#   - 每次同步在本仓库的 sync/server、sync/client 两个分支上追加一个「快照提交」，
#     再推送到对应仓库的 main。快照提交只含当次内容，不带 monorepo 提交历史。
#   - client 快照的父提交取 GitHub main 的最新 tip：云端 CI 构建成功后会把递增的
#     version.properties 直接提交回仓库（提交信息带 [skip ci]），同步必须把这个提交
#     续在链上（否则非快进推送会被拒），并以「两边版本号较大者」为准收编进快照、
#     回写本仓库工作副本 —— 保证本地与云端的版本号都单调递增、互不回退。
#   - GitHub 仓库里只有逐次快照的线性历史，不含 monorepo 提交历史。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-repos.ps1          # 同步并推送
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-repos.ps1 -DryRun  # 只预览

param(
    # 只生成快照计划并打印，不更新分支、不推送、不回写版本号
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# GitHub 仓库远端名与本地快照分支
$ServerRemoteName = "music-server"
$ClientRemoteName = "music"
$CryptoRemoteName = "tools"
$ServerSyncBranch = "sync/server"
$ClientSyncBranch = "sync/client"
$CryptoSyncBranch = "sync/crypto"

function Assert-LastExit([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step 失败（exit $LASTEXITCODE）" }
}

# 始终在仓库根目录执行，与调用时所在目录无关
$topLevel = git rev-parse --show-toplevel
Assert-LastExit "定位仓库根目录"
Set-Location $topLevel.Trim()

# 远端必须已经配置好
git remote get-url $ServerRemoteName *> $null
if ($LASTEXITCODE -ne 0) { throw "缺少远程仓库 $ServerRemoteName，先执行 git remote add $ServerRemoteName git@github.com:hdppppppp/music-server.git" }
git remote get-url $ClientRemoteName *> $null
if ($LASTEXITCODE -ne 0) { throw "缺少远程仓库 $ClientRemoteName，先执行 git remote add $ClientRemoteName git@github.com:hdppppppp/music.git" }
git remote get-url $CryptoRemoteName *> $null
if ($LASTEXITCODE -ne 0) { throw "缺少远程仓库 $CryptoRemoteName，先执行 git remote add $CryptoRemoteName git@github.com:hdppppppp/tools.git" }

$headSha = (git rev-parse HEAD).Trim()
$headSubject = (git log -1 --format=%s HEAD).Trim()
Assert-LastExit "读取 HEAD"

$sourceLabel = "{0} {1}" -f $headSha.Substring(0, 8), $headSubject
$serverMessage = "sync: 同步自主仓库 server/（$sourceLabel）"
$clientMessage = "sync: 同步自主仓库（$sourceLabel）"
$cryptoMessage = "sync: 同步自主仓库 crypto-src/（$sourceLabel）"

# server 快照树：直接复用主仓库里 server/ 的树对象
$serverTree = (git rev-parse "HEAD:server").Trim()
Assert-LastExit "解析 server 子树"

# crypto 快照树：复用主仓库里 crypto-src/ 的树对象（加密层源码，推到 tools 仓库构建）
$cryptoTree = (git rev-parse "HEAD:crypto-src").Trim()
Assert-LastExit "解析 crypto-src 子树"

# ---- client 快照：根树去掉 server 条目；版本号按「两边较大者」收编 ----

# 先取 GitHub main 的最新 tip 作为快照父提交（CI 回写版本号的提交在它上面，必须续链）
# fetch 的进度走 stderr，PS 5.1 下用 cmd 包裹重定向（PS 管道重定向 stderr 会升级成终止错误）
cmd /c "git fetch $ClientRemoteName main >nul 2>&1"
$remoteClientTip = $null
if ($LASTEXITCODE -eq 0) {
    $remoteClientTip = (git rev-parse "FETCH_HEAD").Trim()
    Assert-LastExit "解析远端 client tip"
}

# 同理取 tools 仓库 main 的最新 tip 作为 crypto 快照父提交：以远端为父保证快进推送，
# 不覆盖 tools 仓库既有历史（tools CI 只发 Release 产物、不回写提交，故无需版本号收编）。
cmd /c "git fetch $CryptoRemoteName main >nul 2>&1"
$remoteCryptoTip = $null
if ($LASTEXITCODE -eq 0) {
    $remoteCryptoTip = (git rev-parse "FETCH_HEAD").Trim()
    Assert-LastExit "解析远端 crypto tip"
}

# 本地版本号读工作副本（本地构建刚递增过、还没提交时也以它为准）
$localVersionText = [IO.File]::ReadAllText("version.properties")
$localCode = 0
if ($localVersionText -match 'VERSION_CODE=(\d+)') { $localCode = [int]$Matches[1] }

# 远端版本号读 GitHub main 上的 version.properties
$remoteCode = -1
$remoteVersionLines = $null
if ($remoteClientTip) {
    $remoteVersionLines = git show "${remoteClientTip}:version.properties"
    Assert-LastExit "读取远端 version.properties"
    $remoteVersionText = $remoteVersionLines -join "`n"
    if ($remoteVersionText -match 'VERSION_CODE=(\d+)') { $remoteCode = [int]$Matches[1] }
}

# 胜出内容：只有远端版本号更大时才需要替换快照里的 version.properties 条目
$winnerContent = $null
if ($remoteCode -gt $localCode) {
    $winnerContent = ($remoteVersionLines -join "`n") + "`n"
    Write-Host "版本号收编：云端 code $remoteCode > 本地 code $localCode，快照采用云端版本并回写本仓库"
}
else {
    Write-Host "版本号以本地为准（code $localCode；远端 code $remoteCode）"
}

$rootEntries = git ls-tree "HEAD^{tree}"
Assert-LastExit "读取根树"
$clientLines = @($rootEntries | Where-Object { -not ($_.EndsWith("`tserver") -or $_.EndsWith("`tcrypto-src")) })

if ($winnerContent) {
    # 经临时文件写入胜出版本内容并转成 blob，再替换快照树里的 version.properties 条目
    $tmpVersion = Join-Path $env:TEMP "taotao-sync-version.properties"
    [IO.File]::WriteAllText($tmpVersion, $winnerContent, (New-Object System.Text.UTF8Encoding($false)))
    $winnerBlob = (git hash-object -w $tmpVersion).Trim()
    Assert-LastExit "写入胜出版本 blob"
    $clientLines = @($clientLines | ForEach-Object {
        if ($_ -match '^(100644 blob )([0-9a-f]+)(\tversion\.properties)$') { "$($Matches[1])$winnerBlob$($Matches[3])" }
        else { $_ }
    })
    Remove-Item $tmpVersion -ErrorAction SilentlyContinue
}

if ($clientLines.Count -lt 5) { throw "client 快照树条目异常（仅 $($clientLines.Count) 条）" }
# 经临时文件喂给 git mktree，绕开 PowerShell 管道的编码转换（PS 5.1 管道会混入 BOM）
$mktreeInput = Join-Path $env:TEMP "taotao-sync-mktree.txt"
[IO.File]::WriteAllText($mktreeInput, (($clientLines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
$clientTree = (cmd /c "git mktree < `"$mktreeInput`"").Trim()
Assert-LastExit "生成 client 快照树"
Remove-Item $mktreeInput -ErrorAction SilentlyContinue

# 在快照分支上追加提交：client 以远端 tip 为父（无远端时退回本地快照分支），server 以本地快照分支为父
function Add-SnapshotCommit {
    param([string]$SyncBranch, [string]$Tree, [string]$Message, [string]$Parent)

    if (-not $Parent) {
        git rev-parse -q --verify "refs/heads/$SyncBranch" *> $null
        if ($LASTEXITCODE -eq 0) { $Parent = (git rev-parse "refs/heads/$SyncBranch").Trim() }
    }
    if ($Parent) { $commit = (git commit-tree $Tree -p $Parent -m $Message).Trim() }
    else { $commit = (git commit-tree $Tree -m $Message).Trim() }
    Assert-LastExit "生成快照提交 $SyncBranch"

    if (-not $DryRun) {
        git update-ref "refs/heads/$SyncBranch" $commit
        Assert-LastExit "更新分支 $SyncBranch"
    }
    return $commit
}

$serverParent = $null
git rev-parse -q --verify "refs/heads/$ServerSyncBranch" *> $null
if ($LASTEXITCODE -eq 0) { $serverParent = (git rev-parse "refs/heads/$ServerSyncBranch").Trim() }

$serverCommit = Add-SnapshotCommit -SyncBranch $ServerSyncBranch -Tree $serverTree -Message $serverMessage -Parent $serverParent
$clientCommit = Add-SnapshotCommit -SyncBranch $ClientSyncBranch -Tree $clientTree -Message $clientMessage -Parent $remoteClientTip
$cryptoCommit = Add-SnapshotCommit -SyncBranch $CryptoSyncBranch -Tree $cryptoTree -Message $cryptoMessage -Parent $remoteCryptoTip

# 版本号回写本仓库：让本地构建的版本号不落后于云端（工作副本有未提交改动时跳过，尊重本地状态）
if ($winnerContent -and -not $DryRun) {
    $dirty = git status --porcelain -- version.properties
    if ($dirty) {
        Write-Host "::警告：本仓库 version.properties 有未提交改动，跳过版本号回写"
    }
    else {
        [IO.File]::WriteAllText("version.properties", $winnerContent, (New-Object System.Text.UTF8Encoding($false)))
        git add version.properties
        git commit -m "ci: 收编云端递增的版本号（code $remoteCode）"
        Assert-LastExit "提交版本号回写"
        Write-Host "已在主仓库提交版本号回写（origin 未自动推送，记得 git push）"
    }
}

if ($DryRun) {
    Write-Host "[DryRun] $ServerSyncBranch <- server/ 快照树 $serverTree，提交 $serverCommit"
    Write-Host "[DryRun] $ClientSyncBranch <- 根树(去 server/crypto-src) $clientTree，父提交 $remoteClientTip，提交 $clientCommit"
    Write-Host "[DryRun] $CryptoSyncBranch <- crypto-src/ 快照树 $cryptoTree，父提交 $remoteCryptoTip，提交 $cryptoCommit"
    Write-Host "[DryRun] 未更新分支、未推送、未回写版本号"
    exit 0
}

# 推送：本地快照分支 -> GitHub main
git push $ServerRemoteName "${ServerSyncBranch}:main"
Assert-LastExit "推送 $ServerRemoteName"
git push $ClientRemoteName "${ClientSyncBranch}:main"
Assert-LastExit "推送 $ClientRemoteName"
git push $CryptoRemoteName "${CryptoSyncBranch}:main"
Assert-LastExit "推送 $CryptoRemoteName"

Write-Host ""
Write-Host "同步完成："
Write-Host "  server/     -> $ServerRemoteName (${ServerSyncBranch}:main)  $serverCommit"
Write-Host "  crypto-src/ -> $CryptoRemoteName (${CryptoSyncBranch}:main)  $cryptoCommit"
Write-Host "  其余内容    -> $ClientRemoteName (${ClientSyncBranch}:main)  $clientCommit"
Write-Host "来源主仓库提交：$sourceLabel"
