# CI 云端构建(单仓统一流水线)

[返回文档中心](README.md)

最后更新:2026-09-30

正式构建在 GitHub 侧由**单仓 [taotaomusic/core](https://github.com/taotaomusic/core)** 的统一工作流承担:根目录 `.github/workflows/ci.yml` 用 `dorny/paths-filter` 按改动路径只构建相关部分,一处改动不重跑无关 job。本文讲与后端直接相关的部分;三仓快照的旧机制已废弃,末尾保留一段备查。

## 1. 触发与路径过滤

- 触发:push 到 `main`,以及在 Actions 页手动 `workflow_dispatch`。
- `concurrency` 按 ref 取消进行中的旧 run,同一分支同时只有一条流水线。
- 第一个 job `changes` 跑 `dorny/paths-filter@v3`,输出四个开关:

| 开关 | 命中路径 |
| --- | --- |
| `crypto` | `crypto-src/**` |
| `server` | `server/**` |
| `desktop` | `desktop/**` |
| `client` | `androidApp/**`、`webApp/**`、`player-ui/**`、`shared/**`、`build-logic/**`、`patch/**`、`gradle/**`、`*.gradle*`、`gradle.properties`、`settings.gradle*`、`version.properties`、`crypto/**` |

两个提交信息魔法字:

- `[full]` —— 强制四个开关全部为真,全量构建。
- `[purge]` —— 先删除仓库里**全部**旧 Release,随后各 job 重建干净的滚动 Release(用于清理历史遗留)。

## 2. job 一览

| job | runner | 跑的条件 | 产物 |
| --- | --- | --- | --- |
| `crypto` | ubuntu | crypto 或 server 或 client 有改动 | Android `.so` + Node `.node`(同 run artifact + Release `crypto-latest`) |
| `server` | ubuntu | server 或 crypto 有改动 | `server-dist-latest` Release + ghcr 镜像 |
| `client-android` | **windows** | client 或 crypto 有改动 | APK(artifact,由 `client-publish` 发布) |
| `client-web` | ubuntu | client 有改动 | Release `share-player-latest` |
| `client-publish` | ubuntu | `client-android` 成功 | Release `latest`(滚动,APK) |
| `desktop` | windows | desktop 或 crypto 有改动 | Release `desktop-latest`(Tauri) |

要点:

- **加密产物在同一次 run 内经 `upload-artifact` / `download-artifact` 流转**:`server` job 取 `crypto-node-linux`,`client-android` job 取 `crypto-android`,不再跨仓、跨 Release 拉取。
- crypto 有改动时 server 和客户端也会连带重建(它们都消费加密产物);反向不成立。
- `client-web` 固定在 **Linux** 跑:Windows runner 上 npm 刚生成的 `wasm-opt.cmd` shim 偶发被占用导致进程启动失败,Linux 的 POSIX shim 无此问题。该 job 还负责 `kotlin-js-store` 锁文件自愈:漂移时以 Linux 解析为准回写仓库(`[skip ci]` 提交)。
- PSK 已改为后端动态下发(见 [37-api-crypto.md](37-api-crypto.md)),产物不含密钥,流水线无需注入任何 PSK 相关 Secret;`CRYPTIFY_KEY` 只用于 `.so` 的编译期字符串混淆。

## 3. server job:构建、契约验证与发布

与旧 music-server 仓库工作流同源,步骤:

1. PostgreSQL 16 服务容器 + 验证环境变量(口径同 `tools/verify-contract.mjs` 文件头)。
2. 下载同 run 的 `crypto-node-linux` artifact 放进 `server/crypto/dist/node/`。
3. `npm ci` 后写版本号:**根 `VERSION`(当前 `1.0`)+ 构建号**拼成 `<MAJOR.MINOR>.<GITHUB_RUN_NUMBER>` 写进 `package.json`。
4. `npm run build`(带 `SKIP_WEB_PLAYER=1` —— 单仓里上级有 Gradle 工程,后端不自建 wasm 播放器,分享播放器由 `client-web` job 发布、下一步拉取)。
5. 从 `hdppppppp/music` 的 Release `share-player-latest` 拉真实分享播放器(旧仓库暂留的产物,拉不到退回占位文件)。
6. 重置验证库 → 起 4720 验证实例 → **完整 `verify-contract.mjs`,全绿才算通过**。
7. 发布滚动 Release **`server-dist-latest`**:`dist/` 内容扁平打包 + `crypto/` 目录并入,解压到部署目录即可 `node main.js`;版本历史经 `tools/release-log.sh` 追加进 Release 说明。
8. 仅 push 到 main 时构建 Docker 镜像(**复用这份已验证 dist**)推到 ghcr。

ghcr 镜像(2026-09-30 起):

```text
ghcr.io/taotaomusic/music-server
```

打三个 tag:`latest`(部署默认拉取)、`<package.json 的 version>`、`<sha 前 12 位>`。换新包名的原因是 `ghcr.io/taotaomusic/core` 包的 Actions 写授权损坏(`permission_denied: write_package`,重跑无效,包 ACL 无 REST/GraphQL 接口只能在网页里修),`GITHUB_TOKEN` 首推会自动建包并关联本仓库。

> **`server/docker-compose.yml` 若仍写旧包名 `ghcr.io/hdppppppp/music-server`,需要同步改成上面的新名**,否则 `docker compose pull` 拉到的永远是停更前的旧镜像。

## 4. 客户端与桌面 job

- `client-android`(Windows runner,`incrementVersion` 依赖 PowerShell):还原签名 Secrets(见 [61-release-android.md](61-release-android.md))后跑 `:androidApp:assembleRelease`,未配 Secrets 自动退回 `:androidApp:assembleDebug`;版本号读 `output-metadata.json`,APK 以 artifact 交给 `client-publish` 重命名为 `TaotaoMusic-<版本>-<release|debug>.apk` 发布到滚动 Release **`latest`**(每次覆盖,免登录可下载)。
- `client-web`:构建 `:webApp:wasmJsBrowserDistribution`,打包发布到滚动 Release **`share-player-latest`**(后端 server job 与本地 `build:web-player` 之外的分发渠道)。
- `desktop`:Tauri + Rust,版本号同样是根 `VERSION` + 构建号;未配 `TAURI_SIGNING_PRIVATE_KEY` 时出无签名安装包、不产更新清单(不让 desktop 构建变红),配好后自动更新(`latest.json`)生效。产物发滚动 Release **`desktop-latest`**,旧资产按版本清理、只留本次与 `latest.json`。

## 5. 版本号现状

- **后端与桌面端**:版本 = 根 `VERSION` + `GITHUB_RUN_NUMBER`,只存在于构建产物的元数据里(`package.json` / `tauri.conf.json`),不回写仓库;历史记录在各自 Release 说明里。
- **Android**:仍由 `version.properties` 单调递增驱动(见 [61-release-android.md](61-release-android.md) 的铁律),CI 与本地构建口径一致。
- 后端部署包本身的版本在 Release `server-dist-latest` 的版本历史里可查。

## 6. 仓库与 Release 一览

- **taotaomusic/core**(主仓):全部代码 + 统一 CI;滚动 Release 有 `crypto-latest`、`server-dist-latest`、`share-player-latest`、`latest`(安卓)、`desktop-latest`。本地取加密产物用 `tools/fetch-crypto.ps1`,默认也是从 core 的 `crypto-latest` 拉。
- **gitee origin**:全量备份,不跑构建。
- **hdppppppp/music / music-server / tools**(旧三仓):暂时保留、**不再更新**;`share-player.zip` 目前仍从 music 仓库的 `share-player-latest` 拉取(新仓库的 `client-web` job 也发同名 Release,迁移完成后应切过来)。

## 7. 与本地发布的关系

- 云端构建与本地构建等价(同一提交);本地发布流程见 [60-deploy-backend.md](60-deploy-backend.md) 与 [61-release-android.md](61-release-android.md)。
- 无论是本地还是云端构建,登记版本号只能取 `output-metadata.json`,不能读构建后的 `version.properties`(见 [61-release-android.md](61-release-android.md))。
- 本地开发取加密产物:`tools/fetch-crypto.ps1`(core 的 `crypto-latest`),或在 `crypto-src/` 下 `cargo build`(见 [37-api-crypto.md](37-api-crypto.md))。

## 8. 历史:三仓快照(已废弃,仅备查)

2026-09 之前,GitHub 侧由 `tools/sync-repos.ps1` **手动**把 monorepo 快照成三个仓库分别构建:

| 源目录 | 目标仓库 | 用途 |
| --- | --- | --- |
| `server/` | `hdppppppp/music-server` | 后端构建(`server/.github/workflows/backend.yml` 发 `server-dist-latest`、推 `ghcr.io/hdppppppp/music-server`) |
| `crypto-src/` | `hdppppppp/tools` | 加密层交叉编译,产物发 Release |
| 其余全部 | `hdppppppp/music` | 客户端构建(`.github/workflows/client.yml` 发 APK 到 Release `latest`) |

快照历史只有逐次快照的线性提交;云端构建成功后把递增的 `version.properties` 回写 music 仓库,sync 时以「本地与云端较大者」收编。该机制已整体废弃:`tools/sync-repos.ps1` 文件保留备查但不再执行,`server/.github/workflows/backend.yml` 在单仓里不再被 GitHub 识别为工作流(仅作历史参考),旧三仓与 `ghcr.io/hdppppppp/music-server` 旧包停更。不要绕过快照机制直接往旧仓库提交内容。
