# CI 云端构建与三仓同步

[返回文档中心](README.md)

最后更新:2026-09-27

正式构建在 GitHub 侧由三仓快照触发的云端工作流承担。三仓同步的完整机制(快照生成、版本号收编、推送规则)以项目根目录 [AGENTS.md](../../AGENTS.md) 的「三仓库同步」一节为准,本文讲与后端直接相关的部分。

## 1. 三仓快照

GitHub 侧 music / music-server / tools 三个正式仓库由项目根的 `tools/sync-repos.ps1` **手动**维护,从本仓库 HEAD 生成内容快照推送:

| 源目录 | 目标仓库 | 内容 |
| --- | --- | --- |
| `server/` | `hdppppppp/music-server` | 后端构建(即镜像仓库根) |
| `crypto-src/` | `hdppppppp/tools` | 加密层交叉编译,产物发 Release |
| 其余全部 | `hdppppppp/music` | 客户端构建(已去掉 server/ 与 crypto-src/) |

要点:

- 只同步**已提交**内容,工作区未提交的改动不会同步出去;推送前先在本仓库提交。
- 快照历史只有逐次快照的线性提交,不含 monorepo 提交历史。
- 版本号单调延续:云端构建成功后自动把递增的 `version.properties` 提交回 music 仓库;sync 时以「本地与云端较大者」为准收编。每次同步会触发一次构建、版本号 +1。
- 同步是助手手动执行的维护动作;不要在 GitHub 仓库里绕过快照机制直接改文件(`version.properties` 除外,云端 CI 会回写)。

## 2. music-server 仓库工作流

`server/.github/workflows/backend.yml`(源文件在本仓库同名路径):

- Ubuntu runner + PostgreSQL 16 服务容器。
- `npm run build` 后从 music 仓库的固定 Release `share-player-latest` 拉取真实分享播放器放进 `dist/share-player/`(拉不到时退回占位文件,仅够缓存头断言)。
- 起验证实例执行完整 `verify-contract.mjs`,**全绿才算通过**。
- 通过后把完整 `dist/` 发布到固定 tag 预发布 Release `server-dist-latest`。
- 同时构建运行时镜像(`server/Dockerfile`,复用这份已验证 dist)推到 ghcr.io:`ghcr.io/hdppppppp/music-server` 打三个 tag——`latest`、`<package.json 的 version>`、`<sha 前 12 位>`;仅 push 到 main 时推,手动触发只验证。

后端版本号的单一来源是 `server/package.json` 的 `version`(手动 SemVer 维护);要发新版本先改它再提交。

独立仓库没有 Gradle 工程,`build:web-player` 会自动跳过(见 `build-web-player.mjs`)。

## 3. music 仓库工作流

`.github/workflows/client.yml`(源文件在主仓库根目录同名路径),Windows runner:

- `:androidApp:assembleRelease` 与 `:desktopApp:packageDesktopUpdateBundle`;APK 和含 `launcher.exe` 的更新包从 Actions Artifact 下载。必须 Windows runner:`incrementVersion` 走 `powershell` 命令。
- 另有 `web-player` job 在 Windows 上构建 `:webApp:wasmJsBrowserDistribution`。
- 三个构建成功后由 `publish-release` job 汇总发到固定 tag 的滚动预发布版 **Release `latest`**(`TaotaoMusic-<版本>-<release|debug>.apk` + 桌面包 zip,每次覆盖),免登录可下载。
- APK 签名:在 music 仓库 Secrets 配 `ANDROID_KEYSTORE_BASE64`、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后出签名 Release 包;未配置时自动退回 Debug 包,仅验证工具链。

## 4. 与本地发布的关系

- 云端构建产物与本地构建等价(同一提交快照);本地发布流程见 [60-deploy-backend.md](60-deploy-backend.md) 与 [61-release-android.md](61-release-android.md)。
- 无论是本地还是云端构建,登记版本号只能取 `output-metadata.json`,不能读构建后的 `version.properties`(见 [61-release-android.md](61-release-android.md))。
- 加密层产物链路:crypto-src 推 tools 仓库 → Actions 交叉编译 → Release → `tools/fetch-crypto.ps1` 拉回 `crypto/dist/`(见 [37-api-crypto.md](37-api-crypto.md))。
