# 005 — 客户端业务模块化迁移

- **状态**：TODO
- **基线提交**：1e24324
- **严重度**：高
- **类别**：架构、可测试性、跨平台边界
- **预计范围**：分 5 个阶段迁移，新增 5-9 个 Android library 模块；每阶段单独提交并可回滚

## 问题

当前工程已经完成了平台级拆分：`shared` 承载跨平台模型和规则，`player-ui` 承载三端共用的 Compose 播放界面，`androidApp`、`desktopApp` 和 `webApp` 分别负责平台入口；模块声明见 `settings.gradle.kts:18`。

但 Android 和 Windows 应用内部仍是大模块：

- `androidApp` 主源码约 12,600 行。`TaotaoMusicApp()` 同时创建认证、网络、下载、播放、同步、IM、更新对象，并维护大量页面状态，见 `androidApp/src/main/java/com/taotao/music/ui/TaotaoMusicApp.kt:125`。
- `TencentMusicApi` 同时处理搜索、播放地址、认证、AI、IM、公告和歌单接口，约 1,300 行，见 `androidApp/src/main/java/com/taotao/music/data/TencentMusicApi.kt:21`。
- `desktopApp` 的 `Main.kt` 和 `DesktopShell.kt` 同时承担应用启动、状态编排、页面和功能实现，分别约 2,000 行和 1,200 行。
- Android 包之间存在拆模块后会形成的循环依赖：音乐 API 依赖 IM 模型，IM 客户端和会话存储又依赖音乐 API；更新管理器依赖热修复安装器，热修复安装器又依赖更新模型。
- `AudioPlayer` 和 `UpdateManager` 直接暴露 Compose `mutableStateOf`，播放器和更新核心无法独立移动到不依赖 UI 的模块。

## 目标

- 保留现有跨平台边界和用户行为，先改善依赖方向与测试边界，不重写播放链路或接口协议。
- 让 `androidApp` 退回到应用组合根：负责生命周期、依赖组装、导航入口和 Manifest 绑定。
- 将网络、持久化、播放器、更新和热修复拆成可独立编译与测试的 Android library 模块。
- 将页面按领域迁移到 feature 模块，页面只能依赖接口和状态模型，不直接创建底层实现。
- 让核心模块通过普通数据类和 `StateFlow` 暴露状态，Compose 状态只存在于 UI 适配层。
- 保持 `shared`、`player-ui`、桌面发布模块和 `patch` 的现有职责，不为了追求模块数量而拆分稳定的小组件。

## 非目标

- 不把服务端拆成微服务。现有 NestJS 模块化单体已经满足领域隔离要求。
- 不为每个页面建立一个 Gradle 模块。只有存在独立依赖、独立测试或独立发布边界时才建立 feature 模块。
- 不在本计划中统一 Android、Windows 和 Web 的播放引擎；三端播放器仍保留平台适配。
- 不修改 API 路径、NDJSON 格式、更新清单格式、补丁方法键或签名发布规则。

## 目标依赖图

依赖方向统一指向箭头尾部：

```text
shared                         跨平台模型与纯规则
  ^
  |
player-ui                     跨平台播放 UI
  ^
  |
android-feature-*  ----------+----------  desktopApp / webApp
  |
  +--> android-player
  +--> android-data
  +--> android-update
  +--> android-contracts

androidApp                    只做组合根、生命周期和入口绑定
  +--> android-feature-*
  +--> android-player
  +--> android-update

patch --compileOnly--> androidApp 与 shared 的宿主产物
desktopLauncher / desktopUpdater 由桌面发布任务组装，不依赖 Android 模块
```

## 模块职责

### 保留模块

- `:shared`：`Song`、`Lyric`、`AudioQuality` 和不依赖平台的解析、映射规则。禁止引入 Android、Compose、HTTP 或本地存储。
- `:player-ui`：主题、歌曲行、迷你播放器、播放详情和布局骨架。只依赖 `:shared` 以及 Compose 公共 API。
- `:desktopApp`：Windows 播放器、系统媒体控制、桌面持久化和桌面入口。第一阶段不拆新的桌面 Gradle 模块，只先按职责整理文件。
- `:webApp`：Wasm 分享页和浏览器音频适配，继续作为薄适配层。
- `:patch`：继续使用 `compileOnly` 读取宿主编译产物，不能把宿主类打进补丁 DEX。

### 新增 Android 模块

- `:android-contracts`：纯 Kotlin 的端内接口和跨层模型，例如音乐数据源接口、IM 网关接口、热修复端口、会话生命周期事件和 UI 无关的更新状态。不得依赖 Compose、Media3 或悟空 SDK。
- `:android-data`：认证会话、HTTP 客户端、音乐数据源、收藏/历史/歌单/下载等存储实现。对外只导出接口、结果模型和必要的 repository；具体 JSON、SharedPreferences 和文件操作留在实现内部。
- `:android-player`：Media3 播放器、`PlaybackService`、队列和定时播放。对外通过播放器状态流和命令接口工作，不向核心暴露 Compose 状态。
- `:android-update`：整包更新、远程配置、下载校验和安装流程。热修复通过 `HotfixPort` 注入，不能直接依赖 `HotfixInstaller`。
- `:android-hotfix`：补丁下载、校验、加载、回滚和诊断。只依赖 `:android-contracts` 中的补丁模型和端口；插桩范围仍由 `build-logic` 控制。
- `:android-feature-auth`、`:android-feature-music`、`:android-feature-library`、`:android-feature-chat`、`:android-feature-ai`、`:android-feature-settings`：按领域承载页面、ViewModel/状态收集和用户操作。可合并为较少模块，前提是模块内部仍保持清晰的领域边界。

## 依赖规则

1. `:shared` 不得依赖任何客户端模块。
2. `:player-ui` 不得依赖 Android data、player、update 或具体平台 SDK。
3. `:android-contracts` 不得依赖 UI 和平台实现。
4. `:android-data`、`:android-player`、`:android-update`、`:android-hotfix` 之间通过 `:android-contracts` 通信，不允许互相引用具体实现。
5. feature 模块可以依赖 `:shared`、`:player-ui` 和 Android core 模块，但 feature 之间禁止直接依赖；跨 feature 通信使用 contracts 或应用级事件。
6. `:androidApp` 可以依赖所有 Android 模块，但其它模块不得反向依赖 `:androidApp`。
7. `api` 只用于确实暴露给消费者的类型；实现细节使用 `implementation`。如果公共 UI API 使用 `Song`，要么将 `:shared` 声明为 `api`，要么要求消费者显式依赖 `:shared` 并在文档中固定这个约定。
8. 新增模块必须放在现有 `com.taotao.music` 包层级下，并保持源码正常换行和中文说明。

## 迁移前置工作

### 1. 固化基线和依赖检查

1. 记录当前 `:shared:allTests`、`:player-ui:allTests`、`:androidApp:testDebugUnitTest`、`:desktopApp:test` 和 Release APK 的基线结果。
2. 在 `settings.gradle.kts` 中加入模块注释和统一命名，不先移动现有文件。
3. 增加一个轻量依赖检查任务或 CI 检查，禁止 Android core 模块引用 `ui` 包，禁止 feature 互相引用。
4. 将 Kotlin Multiplatform target、JVM 21、Compose 基础依赖等重复配置提取到 `build-logic` convention plugin；版本集中管理，暂不借此升级依赖版本。

### 2. 先消除循环依赖

1. 把 `ImConversationSync`、`ImSession` 等端内协议模型从 `TencentMusicApi` 的伴生类型中移到 `:android-contracts`。
2. 定义 `ImGatewayApi` 或等价端口，`WukongImClient` 依赖端口获取会话和同步数据；音乐 API 只提供实现，不再被 IM 客户端反向引用。
3. 将 `AuthSession` 的退出事件改为会话生命周期回调，由组合根通知 IM 清理；认证模块不直接创建 `ImSessionStore`。
4. 把 `AvailablePatch`、补丁安装结果和确认事件移到 `:android-contracts`。
5. 让 `UpdateManager` 依赖 `HotfixPort`，由 `:android-hotfix` 实现；删除 `update -> hotfix -> update` 的直接类型依赖。
6. 这一步完成前不得创建对应的 Gradle 模块，否则循环依赖会被隐藏在临时 `implementation(project(...))` 中。

## 分阶段步骤

### 阶段一：抽取 contracts，保持所有功能在原模块运行

1. 新建 `:android-contracts`，只放接口、状态模型和事件模型。
2. 在原 `androidApp` 中先实现这些接口，使用适配器包装现有 `TencentMusicApi`、`WukongImClient`、`HotfixInstaller`。
3. 将 `AudioPlayer` 和 `UpdateManager` 的公共入口改为接口或普通状态模型，暂时保留旧类作为兼容门面。
4. 验证所有页面行为不变，再删除旧的跨包直接类型引用。

**阶段出口**：`androidApp` 仍可单独运行；contracts 不出现 Android、Compose、Media3 或悟空 SDK import；循环依赖清零。

### 阶段二：抽取 Android core

1. 新建 `:android-data`，迁移认证、网络和存储实现；按 `auth`、`music`、`library`、`chat`、`ai` 子包整理，不继续把所有实现堆在单一 `data` 包。
2. 将 `TencentMusicApi` 拆成音乐、认证、用户/AI、IM 网关等客户端，保留同一 HTTP 错误和令牌刷新策略。
3. 新建 `:android-player`，迁移 `AudioPlayer`、`PlaybackService`、`SleepTimer` 和播放相关编解码；以 `StateFlow` 提供队列、播放、进度、错误和定时器状态。
4. 新建 `:android-update` 与 `:android-hotfix`，按 contracts 注入依赖；保持 `build-logic` 的插桩包前缀和补丁方法键稳定。
5. `androidApp` 只保留兼容门面和组装代码，确认运行稳定后再删除门面。

**阶段出口**：Android core 模块可独立编译和单元测试；core 模块没有 Compose UI 状态；热修复构建仍能从 release 宿主产物生成补丁。

### 阶段三：按领域迁移 Android 页面

1. 先迁移依赖边界最清晰的 `settings`、`auth` 和 `chat` 页面。
2. 再迁移 `music`、`library`、播放队列和歌单页面，页面只接收状态与回调，不再直接 `remember { TencentMusicApi(...) }` 或创建 Store。
3. 将 `TaotaoMusicApp.kt` 收敛为应用状态组装、底部导航、返回键和全局 Snackbar/更新门禁协调。
4. 按现有导航规范同步维护 `switchTab`、`AnimatedContent.targetState` 和 `BackHandler` 三处入口。
5. 每迁移一个 feature，删除原 package 的重复实现，禁止新旧两套状态长期并存。

**阶段出口**：`TaotaoMusicApp.kt` 只负责组合和导航；页面 feature 可以独立测试，页面不直接依赖具体网络、存储或 Media3 类。

### 阶段四：整理桌面端并回收共享逻辑

1. 将 `desktopApp/Main.kt` 中的状态编排、网络、播放、持久化和发布更新按 package 拆开，先不改变 Gradle 模块。
2. 对 Android 与 Windows 都需要的纯业务规则，迁移到 `:shared`；平台实现继续留在各自模块。
3. 只有在桌面与 Android 确实共享同一组接口和测试后，才考虑新增 KMP domain 模块；不复制 Android feature 模块到桌面端。
4. 保持 `desktopLauncher`、`desktopUpdater` 和桌面模块清单生成逻辑不变，验证 `packageDesktopUpdateBundle`。

**阶段出口**：桌面入口文件只负责组装；桌面发布产物仍包含独立的应用、共享 JAR、运行时依赖、launcher 和 updater。

### 阶段五：清理与固化规则

1. 删除兼容门面、旧包路径和未使用依赖。
2. 将 convention plugin 应用到所有 KMP/Compose 模块，减少 target 和依赖声明重复。
3. 为 Android core 和 feature 增加最小必要的单元测试；共享规则继续放在 `shared/src/commonTest/`。
4. 更新 README、`MUSIC_CROSS_PLATFORM.md` 和发布文档中的模块树与构建命令。
5. 记录 Kotlin 2.0.21 与 AGP 8.7.3 的兼容性决策：先完成现有版本的构建验证，再单独安排升级或警告抑制。

## 边界和风险

- **热修复兼容性**：移动 `data`、`player`、`update` 类的包名会改变插桩生成的方法键。阶段二前保持这些包前缀不变，或同时更新插桩、补丁生成器、服务端登记和已有补丁策略。
- **播放服务生命周期**：`PlaybackService` 的 Manifest、MediaSession、通知和后台启动行为必须由 `:android-player` 统一持有，不能让 UI feature 直接操作 Service。
- **账号隔离**：认证、收藏、历史、播放 outbox 和 IM 会话的账号分桶逻辑必须保留；抽取 Store 时先迁移测试，再移动文件。
- **公共 API 泄漏**：公共函数参数中出现的 `Song`、更新状态和 IM 状态必须来自 contracts/shared，不能把 `TencentMusicApi` 的内部 DTO 继续暴露到 feature。
- **构建时长**：新增模块会增加 Gradle 配置和 KMP 编译任务；每阶段只加入能形成独立边界的模块，并保留增量构建检查。
- **工作区改动**：迁移时不得覆盖其它未提交修改；每阶段提交前先确认 `git diff` 只包含该阶段的文件。

## 验证

每阶段至少执行：

```powershell
.\gradlew.bat :shared:allTests
.\gradlew.bat :player-ui:allTests
.\gradlew.bat :androidApp:testDebugUnitTest
.\gradlew.bat :desktopApp:test
```

阶段二完成后追加：

```powershell
.\gradlew.bat :androidApp:compileDebugKotlin
.\gradlew.bat :patch:buildPatch -PpatchVersion=1
```

阶段三和最终交付追加：

```powershell
.\gradlew.bat :androidApp:assembleRelease
.\gradlew.bat :desktopApp:packageDesktopUpdateBundle
.\gradlew.bat :webApp:compileProductionExecutableKotlinWasmJs
```

Release 验收必须从 `androidApp/build/outputs/apk/release/output-metadata.json` 读取版本号，并检查签名；不能用构建后已递增的 `version.properties` 作为包版本来源。

后端本计划不改接口。若迁移过程中调整接口或发布模块，额外执行：

```powershell
npm run build --prefix server
node server/tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

## 完成标准

- Gradle 项目图无循环依赖，`androidApp` 不再被其它业务模块反向依赖。
- `androidApp` 的页面只依赖 feature 状态和 contracts；网络、存储、播放器、更新实现均位于对应 core 模块。
- Android core 模块不导入 Compose；播放器和更新状态通过普通模型/`StateFlow` 适配到 UI。
- `TaotaoMusicApp.kt` 与 `TencentMusicApi.kt` 不再承担跨领域的全部编排和接口实现。
- Android Release APK、桌面模块化更新包、Web 分享播放器和热修复补丁均可按现有命令生成。
- `shared`、`player-ui`、Android、桌面和服务端的既有测试与契约验证保持全绿。
