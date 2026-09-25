# tools/sync-repos.ps1
# 将主仓库内容同步到两个 GitHub 镜像仓库：
#   - server/ 目录 -> git@github.com:hdppppppp/music-server.git（server/ 内容作为镜像仓库根）
#   - 其余全部内容 -> git@github.com:hdppppppp/music.git（去掉 server/ 后的主仓库根）
#
# 机制说明：
#   - 同步只取已提交内容（HEAD），工作区未提交的改动不会同步出去。
#   - 每次同步在本仓库的 sync/server、sync/client 两个分支上追加一个「快照提交」，
#     再把这两个分支推送到对应镜像的 main。镜像仓库里只有逐次快照的线性历史，
#     不含主仓库提交历史，避免主仓库历史里的临时产物（例如已删除的构建包）外泄。
#   - 远端名固定为 music-server 与 music（见 AGENTS.md「双仓库同步」）。
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-repos.ps1          # 同步并推送
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-repos.ps1 -DryRun  # 只预览，不推送

param(
    # 只生成快照计划并打印，不更新分支、不推送
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# 镜像仓库远端名与本地快照分支
$ServerRemoteName = "music-server"
$ClientRemoteName = "music"
$ServerSyncBranch = "sync/server"
$ClientSyncBranch = "sync/client"

function Assert-LastExit([string]$Step) {
    if ($LASTEXITCODE -ne 0) { throw "$Step 失败（exit $LASTEXITCODE）" }
}

# 始终在仓库根目录执行，与调用时所在目录无关
Set-Location (git rev-parse --show-toplevel).Trim()
Assert-LastExit "定位仓库根目录"

# 远端必须已经配置好
git remote get-url $ServerRemoteName *> $null
if ($LASTEXITCODE -ne 0) { throw "缺少远程仓库 $ServerRemoteName，先执行 git remote add $ServerRemoteName git@github.com:hdppppppp/music-server.git" }
git remote get-url $ClientRemoteName *> $null
if ($LASTEXITCODE -ne 0) { throw "缺少远程仓库 $ClientRemoteName，先执行 git remote add $ClientRemoteName git@github.com:hdppppppp/music.git" }

$headSha = (git rev-parse HEAD).Trim()
$headSubject = (git log -1 --format=%s HEAD).Trim()
Assert-LastExit "读取 HEAD"

# 快照提交信息注明来源主仓库提交，方便在镜像仓库里追溯
$sourceLabel = "{0} {1}" -f $headSha.Substring(0, 8), $headSubject
$serverMessage = "sync: 同步自主仓库 server/（$sourceLabel）"
$clientMessage = "sync: 同步自主仓库（$sourceLabel）"

# server 快照树：直接复用主仓库里 server/ 的树对象
$serverTree = (git rev-parse "HEAD:server").Trim()
Assert-LastExit "解析 server 子树"

# client 快照树：主仓库根树去掉 server 条目后重建
$rootEntries = git ls-tree "HEAD^{tree}"
Assert-LastExit "读取根树"
$clientTree = $rootEntries | Where-Object { -not $_.EndsWith("`tserver") } | git mktree
Assert-LastExit "生成 client 快照树"

# 在快照分支上追加提交：已有快照则作为父提交，形成线性历史；首次同步则从零开始
function Add-SnapshotCommit {
    param([string]$SyncBranch, [string]$Tree, [string]$Message)

    git rev-parse -q --verify "refs/heads/$SyncBranch" *> $null
    if ($LASTEXITCODE -eq 0) {
        $parent = (git rev-parse "refs/heads/$SyncBranch").Trim()
        $commit = (git commit-tree $Tree -p $parent -m $Message).Trim()
    }
    else {
        $commit = (git commit-tree $Tree -m $Message).Trim()
    }
    Assert-LastExit "生成快照提交 $SyncBranch"

    if (-not $DryRun) {
        git update-ref "refs/heads/$SyncBranch" $commit
        Assert-LastExit "更新分支 $SyncBranch"
    }
    return $commit
}

$serverCommit = Add-SnapshotCommit -SyncBranch $ServerSyncBranch -Tree $serverTree -Message $serverMessage
$clientCommit = Add-SnapshotCommit -SyncBranch $ClientSyncBranch -Tree $clientTree -Message $clientMessage

if ($DryRun) {
    Write-Host "[DryRun] $ServerSyncBranch <- server/ 快照树 $serverTree，提交 $serverCommit"
    Write-Host "[DryRun] $ClientSyncBranch <- 主仓库根树(去 server) $clientTree，提交 $clientCommit"
    Write-Host "[DryRun] 未更新分支、未推送"
    exit 0
}

# 推送：本地快照分支 -> 镜像仓库 main
git push $ServerRemoteName "${ServerSyncBranch}:main"
Assert-LastExit "推送 $ServerRemoteName"
git push $ClientRemoteName "${ClientSyncBranch}:main"
Assert-LastExit "推送 $ClientRemoteName"

Write-Host ""
Write-Host "同步完成："
Write-Host "  server/  -> $ServerRemoteName ($ServerSyncBranch:main)  $serverCommit"
Write-Host "  其余内容 -> $ClientRemoteName ($ClientSyncBranch:main)  $clientCommit"
Write-Host "来源主仓库提交：$sourceLabel"
