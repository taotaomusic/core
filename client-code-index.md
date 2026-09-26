# 客户端代码索引（CodeGraph 基准）

[返回项目总览](README.md)

本页是 Android、Windows、Web 和跨平台客户端源码的导航基准。索引数据库位于本机根目录 `.codegraph/`，由 CodeGraph 生成，不应提交到版本库。服务端继续使用独立的 `server/.codegraph/` 索引；本页的根索引包含服务端文件以便跨端追踪，但客户端统计和模块说明会明确排除 `server/`。

## 1. 当前快照

本页在 2026-09-26 重建根索引后核对（此前的 `.codegraph/` 曾丢失，`codegraph init .` + `codegraph index .` 重建），状态命令为 `codegraph status . --json`：

| 项目 | 当前值 |
| --- | ---: |
| CodeGraph 版本 | 1.0.1 |
| 最后索引时间 | 2026-09-26T16:31:07Z |
| 根索引记录文件 | 343 |
| 根索引节点 | 7,992 |
| 根索引关系 | 21,521 |
| 客户端文件（排除 `server/`） | 149 |
| 客户端节点（排除 `server/`） | 3,970 |
| 待同步新增/修改/删除 | 0 / 0 / 0 |
| 是否建议重建 | 否 |

根索引语言统计如下（含服务端）。根目录的 Gradle、脚本、工作流和版本配置也会被记录，因此总数不只包含业务源码。

| 语言 | 文件 | 说明 |
| --- | ---: | --- |
| Kotlin | 147 | Android、KMP、桌面、Wasm、构建逻辑和补丁 |
| TypeScript | 137 | `server/` 源码与工具 |
| Rust | 18 | `crypto-src/` 加密层四 crate |
| Vue | 15 | 服务端管理后台前端 |
| JavaScript | 9 | 服务端构建与契约工具 |
| YAML | 5 | GitHub Actions 工作流 |
| Python | 4 | 根目录维护脚本 |
| XML | 3 | Android Manifest 和资源配置 |
| Properties | 3 | Gradle/版本配置 |
| Java | 2 | `desktopLauncher`、`desktopUpdater` |

根索引还包含 194 个服务端相关文件（server/ 源码、前端、工具与工作流）；后端路由、数据库和配置请以 [server/wiki/00-code-index.md](server/wiki/00-code-index.md) 为准，不要把本页的根索引统计当成后端独立快照。

## 2. 模块地图

### 客户端运行模块

| 模块 | 文件/节点 | 主要职责 | 关键入口或文件 |
| --- | ---: | --- | --- |
| `androidApp` | 85 / 2,439 | Android 生命周期、Compose 页面、账号与本地 Store、网络适配、Media3 播放、IM、热修复和更新。`ui/` 已拆 15 个功能子包（account/ai/app/auth/chat/common/home/library/mine/player/playlist/search/settings/theme/update），全局状态在 `ui/app/` 状态容器（`TaotaoAppState`、`SearchState`、`PlaylistState`、`AppEffects`、`AppDialogs`、`AppPageRouter`、`AppContent`） | `MainActivity.kt`、`TaotaoMusicApp.kt`、`TencentMusicApi.kt`、`AudioPlayer.kt`、`PlaybackService.kt` |
| `shared` | 6 / 52 | Kotlin Multiplatform 纯模型和规则：歌曲、歌词、音质与解析器 | `MusicModels.kt`、`Lyric.kt`、`AudioQuality.kt` |
| `player-ui` | 23 / 381 | Android、Windows、Web 共用的播放主题、歌曲行、迷你播放器、播放布局和状态视图；`theme/` 是 Taotao 设计 token 与组件，`layout/` 承载主布局骨架 | `SharedMiniPlayer.kt`、`PlayerSurface.kt`、`layout/SharedMainLayout.kt`、`theme/AppleStyleTheme.kt` |
| `desktopApp` | 17 / 853 | Windows Compose Desktop 应用、搜索/歌单/收藏/历史、JVM 播放、持久化、同步、系统媒体和定时播放 | `Main.kt`、`DesktopShell.kt`、`DesktopPlayer.kt`、`DesktopMusicApi.kt` |
| `desktopLauncher` | 2 / 23 | Windows 发布包的轻量启动器 | `LauncherMain.java` |
| `desktopUpdater` | 2 / 24 | Windows 模块更新器和启动/替换流程 | `UpdaterMain.java` |
| `webApp` | 4 / 106 | Kotlin/Wasm 分享播放器，只负责公开试听页和浏览器音频适配 | `Main.kt`、`WebAudioController.kt` |

### 客户端构建与发布支撑

| 模块 | 文件/节点 | 主要职责 | 备注 |
| --- | ---: | --- | --- |
| `build-logic` | 8 / 68 | Gradle convention/plugin、热修复字节码插桩和桌面模块配置 | 编译期能力，不应被运行时模块反向依赖 |
| `patch` | 2 / 24 | 独立 DEX 补丁入口和补丁构建配置 | 通过 `compileOnly` 读取宿主产物，不打入 APK |

索引还记录根目录的 `build.gradle.kts`、`settings.gradle.kts`、`gradle.properties`、`version.properties` 以及维护脚本；这些文件用于定位构建边界，但不计入上面的运行模块职责。

## 3. 依赖和数据流

客户端的主依赖方向如下，箭头表示调用或数据依赖：

```text
shared（Song / Lyric / AudioQuality / 纯规则）
  ├── player-ui（跨平台 Compose 播放界面）
  ├── androidApp（Android 平台实现与页面）
  ├── desktopApp（Windows 平台实现与页面）
  └── webApp（Wasm 分享页）

androidApp / desktopApp
  → 自建后端 /api/v1
  → 音乐搜索、账号、收藏、歌单、播放统计、更新和分享接口
  → 播放地址返回后由平台播放器从上游 CDN 拉流

androidApp
  → Media3 AudioPlayer / PlaybackService
  → 本地队列、下载、播放会话与热修复/整包更新

desktopApp
  → DesktopPlayer（FFmpeg/Java Sound 适配）
  → DesktopPersistence / PlaybackOutbox
  → desktopLauncher + desktopUpdater（发布包更新）
```

当前跨端边界保持为：

- `shared` 不依赖 Android、Compose、HTTP 或本地存储；纯模型和解析规则在 `commonMain`，测试在 `commonTest`。
- `player-ui` 只提供界面骨架和可插槽的回调；实际封面、歌词、队列、下载和播放引擎仍由各端注入。
- Android 与 Windows 共用歌曲身份 `source + songId`、音质枚举和云端歌单契约，但本地缓存、播放引擎和生命周期状态分别由平台持有。
- Web 分享播放器只处理公开分享和试听，不接入 Android 的账号、IM、AI、下载和队列能力。
- **传输加密（协议 v2，握手绑定设备号）**：`androidApp/.../data/crypto/HardwareDeviceId.kt`（ANDROID_ID）与
  `desktopApp/.../crypto/MachineGuid.kt`（注册表 MachineGuid）提供设备号来源；四端编译产物在
  `crypto/dist/`（源码在 `crypto-src/`，经 tools 仓库交叉编译）。当前只有服务端消费产物
  （`server/src/crypto/native-loader.ts`），客户端 JNI 绑定尚未进构建路径，Web 端不接入。
- `build-logic` 与 `patch` 参与构建和热修复，不属于业务运行时依赖；修改包名或插桩键前必须阅读 [RELEASE.md](RELEASE.md) 和 [HOT_UPDATE.md](HOT_UPDATE.md)。

## 4. 关键符号导航

以下符号已经在根索引中解析，可直接用 `codegraph node` 查看源码、调用者和被调用者：

| 领域 | 符号 | 文件 | 作用 |
| --- | --- | --- | --- |
| Android 入口 | `MainActivity` | `androidApp/src/main/java/com/taotao/music/MainActivity.kt` | 生命周期和 Compose 根入口 |
| Android 组合根 | `TaotaoMusicApp` | `androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt` | 组合根入口；全局状态组装已拆到 `ui/app/`（`TaotaoAppState` 等 7 个文件） |
| Android 网络 | `TencentMusicApi` | `androidApp/src/main/java/com/taotao/music/data/TencentMusicApi.kt` | 音乐、账号、歌单、播放、IM 等后端调用和错误/续期处理 |
| Android 播放 | `AudioPlayer` / `PlaybackService` | `androidApp/src/main/java/com/taotao/music/player/` | Media3 播放控制、媒体会话和后台生命周期 |
| Android 定时关闭 | `SleepTimer` 系列 | `androidApp/.../player/SleepTimer.kt`、`SleepTimerPolicy.kt`、`ui/player/SleepTimerDialog.kt` | 播完整首再停的等待态、底部弹层与持久化 |
| Android 拖动排序 | `DragReorderList` / `TaotaoSnackbar` | `androidApp/.../ui/common/DragReorderList.kt`、`ui/common/components.kt` | 播放队列与歌单共用的统一拖动组件、全局 Snackbar |
| Android 播放记录与日记 | `PlaybackHistoryPage` / `SongDiaryPage` / `DiaryRecordsPage` | `androidApp/.../ui/library/MineLibraryPages.kt` | 播放记录独立下级页与单曲倒带日记（路由 key `diary-records`） |
| Android 设备号 | `HardwareDeviceId` | `androidApp/.../data/crypto/HardwareDeviceId.kt` | ANDROID_ID 设备号来源，供传输加密协议 v2 绑定 |
| Android 更新 | `UpdateManager` | `androidApp/src/main/java/com/taotao/music/update/UpdateManager.kt` | 版本检查、下载、校验和安装协调 |
| Android 热修复 | `PatchDispatcher` / `PatchEntry` | `androidApp/src/main/java/com/taotao/music/hotfix/` | 补丁入口、分发和诊断 |
| Windows 设备号 | `MachineGuid` | `desktopApp/src/main/kotlin/com/taotao/music/desktop/crypto/MachineGuid.kt` | 读注册表 MachineGuid 作设备号来源 |
| 共享模型 | `Song` | `shared/src/commonMain/kotlin/com/taotao/music/model/MusicModels.kt` | 被 Android、Windows、播放 UI 等 30 个文件引用的歌曲模型 |
| 歌词规则 | `LyricParser` | `shared/src/commonMain/kotlin/com/taotao/music/model/Lyric.kt` | LRC/YRC 解析、逐字进度和时间映射 |
| 音质规则 | `AudioQuality` | `shared/src/commonMain/kotlin/com/taotao/music/model/AudioQuality.kt` | STANDARD/HIGH/LOSSLESS/HIRES/MASTER 及标签映射 |
| 共用播放 UI | `SharedMiniPlayer` / `PlayerSurface` | `player-ui/src/commonMain/kotlin/com/taotao/music/playerui/` | Android 与 Windows 共用的迷你播放器和详情播放面 |
| Windows 入口 | `main` / `DesktopMusicApp` | `desktopApp/src/main/kotlin/com/taotao/music/desktop/Main.kt` | 桌面状态编排、页面导航、同步和播放生命周期 |
| Windows 壳层 | `DesktopShell` | `desktopApp/src/main/kotlin/com/taotao/music/desktop/DesktopShell.kt` | 导航、歌曲列表、歌词、队列和设置 UI |
| Windows 播放 | `DesktopPlayer` | `desktopApp/src/main/kotlin/com/taotao/music/desktop/DesktopPlayer.kt` | JVM 音频输出、进度、切歌和停止/暂停控制 |
| Web 入口 | `SharePlayerApp` | `webApp/src/wasmJsMain/kotlin/com/taotao/music/web/Main.kt` | 分享页状态与公开试听 UI |

索引显示的实际关系示例：`SharedMiniPlayer` 被 Android 的 `TaotaoMusicApp.kt` 和 Windows 的 `DesktopShell.kt` 使用；`Song` 被 30 个客户端文件使用；`TaotaoMusicApp` 由 `MainActivity` 作为唯一入口文件使用。这些关系可作为跨模块修改的影响分析起点。

## 5. 常用索引命令

在项目根目录执行。`codegraph` 会根据最近的 `.codegraph/` 选择根索引；在 `server/` 目录内执行时会优先选择后端嵌套索引。

```powershell
# 查看当前快照和是否有待同步变更
codegraph status . --json

# 大批量切换分支或生成文件后手动同步
codegraph sync .

# 查看客户端模块文件和节点数
codegraph files --filter androidApp --format grouped
codegraph files --filter desktopApp --format grouped
codegraph files --filter shared --format grouped
codegraph files --filter player-ui --format grouped

# 按自然语言了解一条跨模块链路，返回相关源码和调用路径
codegraph explore -p . "Song 从搜索结果进入 Android 播放队列的链路"

# 查看单个文件的符号地图和依赖者
codegraph node -p . --file androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt --symbols-only

# 查看调用者和影响范围
codegraph callers -p . "SharedMiniPlayer"
codegraph impact -p . "com.taotao.music.model::Song"
```

索引状态满足以下条件时，结果才可作为文档快照：

1. `initialized=true`，且 `lastIndexed` 晚于最近一次源码或分支切换。
2. `pendingChanges.added/modified/removed` 全为 `0`。
3. `index.currentExtractionVersion` 与 `index.builtWithExtractionVersion` 相等，`reindexRecommended=false`。
4. 发现新的模块、源文件类型或 CodeGraph 升级后，先执行 `codegraph sync .`；若版本提示需要重建，再执行 `codegraph index .`。
5. 克隆本仓库后首次使用时 `.codegraph/` 不存在（它不进版本库），需先 `codegraph init .` 再 `codegraph index .`；直接跑 `index` 会报 "not initialized"。

CodeGraph 只提供结构索引，不替代 Kotlin 编译器、Gradle、Android Lint 或设备测试。生成代码、`build/`、`.gradle/`、依赖缓存和其他 `.gitignore` 路径被刻意排除；涉及类型推导、资源合并、Manifest 合并或平台运行时行为时，必须回到对应构建和测试命令验证。

## 6. 客户端文档同步表

| 修改内容 | 必须同步的文档 |
| --- | --- |
| 新增/删除客户端模块或 source set | 本页模块地图、`README.md` 项目结构、`MUSIC_CROSS_PLATFORM.md` |
| 修改 `Song`、`Lyric`、`AudioQuality` 或歌单/播放数据契约 | 本页关键符号、`MUSIC_CROSS_PLATFORM.md`、`RELEASE.md` 中对应客户端契约 |
| 修改 Android 导航、页面状态或返回键 | 本页 Android 组合根说明、`AGENTS.md` 的三处导航检查清单、相关页面文档 |
| 修改更新、热修复、补丁键或桌面模块清单 | 本页更新/热修复入口、`RELEASE.md`、`HOT_UPDATE.md`、`server/wiki/10-desktop-release.md` |
| 修改后端接口、认证、NDJSON 或错误码 | 本页数据流、`MUSIC_CROSS_PLATFORM.md`、`server/wiki/00-code-index.md`、`server/wiki/03-api-contracts.md` |

当本页与源码不一致时，以源码、编译器和最新 CodeGraph 状态为准，并在同一变更中刷新本页及对应专题文档。后端索引和契约验证仍按 [server/wiki/00-code-index.md](server/wiki/00-code-index.md) 的独立流程执行。
