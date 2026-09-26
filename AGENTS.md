# 桃桃音乐项目开发规范

## 项目结构

桃桃音乐是一个 Kotlin Multiplatform 音乐播放器，当前提供 Android、Windows 和 Web 分享播放器。

- `androidApp/`：Android 应用入口、Jetpack Compose 页面、Android 资源和平台能力。
- `desktopApp/`：Windows Compose Desktop 应用、JVM 播放、桌面持久化和系统媒体能力。
- `desktopLauncher/`、`desktopUpdater/`：Windows 发布包的启动器和模块更新器。
- `webApp/`：Kotlin/Wasm 分享播放器和浏览器音频适配。
- `player-ui/`：Android、Windows、Web 共用的播放主题、歌曲行、迷你播放器和布局组件。
- `shared/`：跨平台共享的数据模型、歌词解析和音质规则，代码放在 `src/commonMain/`。
- `patch/`、`build-logic/`：热修复补丁模块与 Gradle/字节码插桩构建逻辑。
- `crypto/`：传输层加密层的**产物目录**，只放 `dist/`（四端编译产物），用 `tools/fetch-crypto.ps1` 从 tools 仓库的 Release 拉取。本地**不需要**安装 Rust / Android NDK / wasm-bindgen 等交叉编译环境。
- `crypto-src/`：传输层加密层的**Rust 源码**（`core/jni/node/wasm` 四 crate + 自带 `.github/workflows/`）。它由 `tools/sync-repos.ps1` 推到 GitHub `tools` 仓库，交叉编译由那边的 GitHub Actions 负责，产物发 Release 后再经 `fetch-crypto.ps1` 落回 `crypto/dist/`。改加密协议在这里改，别去 `crypto/dist/` 动产物。
- 根目录 Gradle 文件：定义上述客户端模块和 `:shared` 的构建关系。
- `build/` 目录：构建生成物，只读，不手工修改。
- `server/`：NestJS + TypeScript + PostgreSQL 的接口适配服务；服务源码必须保持可读，不得提交压缩后的源码。
- 外部参考仓库不得放在项目根目录；临时参考代码使用项目外目录，交付前清理无关仓库。
- 新增 Kotlin 代码必须放在已有的 `com.taotao.music` 包层级下。

## 三仓库同步（GitHub 正式仓库）

GitHub 侧三个仓库（music / music-server / tools）都是**正式仓库**，由助手手动跑 `tools/sync-repos.ps1` 维护（不是自动镜像）。gitee 的 `origin` 保留完整 monorepo 作**总备份**。同步从本仓库 HEAD 生成**内容快照**推送；快照历史只有逐次快照的线性提交，不含 monorepo 提交历史，历史里的临时产物不会外泄：

- `server/` → <https://github.com/hdppppppp/music-server>（远端名 `music-server`，本地快照分支 `sync/server`，server/ 内容即镜像仓库根）——后端构建
- `crypto-src/` → <https://github.com/hdppppppp/tools>（远端名 `tools`，本地快照分支 `sync/crypto`，crypto-src/ 内容即镜像仓库根）——加密层构建，产物发 Release
- 其余全部内容（客户端各模块与文档）→ <https://github.com/hdppppppp/music>（远端名 `music`，本地快照分支 `sync/client`，已去掉 server/ 与 crypto-src/）——客户端构建

执行 `powershell -NoProfile -ExecutionPolicy Bypass -File tools\sync-repos.ps1`（加 `-DryRun` 只预览）。注意几点：

- 只同步**已提交**内容，工作区未提交的改动不会同步出去；推送前先在本仓库提交。
- **版本号单调延续**：云端构建成功后自动把递增的 `version.properties` 提交回 music 仓库（提交信息带 `[skip ci]`）；sync 时以「本地与云端较大者」为准收编进快照并回写本仓库（单独提交，origin 不自动推送）。因此每次同步会触发一次构建、版本号 +1；本地构建出的号同样被尊重，两侧互不回退、重号风险归零。tools 仓库只发 Release 产物、不回写提交，crypto 快照以远端 tip 为父保证快进推送、不覆盖其历史。
- 同步是助手手动执行的维护动作，不要在 GitHub 仓库里绕过快照机制直接改文件（`version.properties` 除外，云端 CI 会回写）。

### 云端构建（GitHub Actions）

两个镜像仓库各带工作流，随同步推送自动触发，也可在 Actions 页手动 `workflow_dispatch`：

- **music 仓库**：`.github/workflows/client.yml`（源文件在主仓库根目录同名路径）——Windows runner 上构建 `:androidApp:assembleRelease` 与 `:desktopApp:packageDesktopUpdateBundle`，APK 和含 `launcher.exe` 的更新包从 Actions Artifact 下载。必须 Windows runner：`incrementVersion` 走 `powershell` 命令。另有 `web-player` job 在 Windows 上构建 `:webApp:wasmJsBrowserDistribution`。三个构建成功后由 `publish-release` job 汇总发到固定 tag 的滚动预发布版 **Release `latest`**（`TaotaoMusic-<版本>-<release|debug>.apk` + 桌面包 zip，每次覆盖），免登录可下载。
- **music-server 仓库**：`server/.github/workflows/backend.yml`（源文件在 `server/.github/` 内）——Ubuntu + PostgreSQL 服务容器，构建时从 `share-player-latest` 拉取真实分享播放器放进 `dist/share-player/`（拉不到退回占位文件），然后起验证实例执行完整 `verify-contract.mjs`，**全绿才算通过**，通过后把完整 dist 发布到固定 tag 预发布版 **Release `server-dist-latest`**。独立仓库没有 Gradle 工程，`build:web-player` 会自动跳过（见 `build-web-player.mjs`）。
- **APK 签名**：在 music 仓库 Secrets 配 `ANDROID_KEYSTORE_BASE64`（`taotao-release.jks` 的 base64）、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD` 后出签名 Release 包；未配置时自动退回 Debug 包，仅验证工具链。

## 文档索引

- [README.md](README.md)：项目总览、播放链路、开发入口。
- [client-code-index.md](client-code-index.md)：客户端 CodeGraph 索引、模块边界、关键符号和刷新命令。
- **[RELEASE.md](RELEASE.md)：发布与热更新流程、版本号铁律、不能破的客户端契约。改后端或推版本前必读。**
- [server/README.md](server/README.md)：后端接口、数据层规矩、契约验证。
- [HOT_UPDATE.md](HOT_UPDATE.md)：热更新的设计动机（部分内容已被实现取代，文内有标注）。
- [MUSIC_CROSS_PLATFORM.md](MUSIC_CROSS_PLATFORM.md)：Android/Windows 双端功能边界、定时播放与云端歌单契约。
- [server/wiki/README.md](server/wiki/README.md)：后端专题文档中心（架构、契约、数据库、部署、排障、管理后台认证等 12 篇）。

## 文档语言

项目内置文档、代码注释、模块说明、用户提示和构建说明统一使用简体中文。必要的技术名词可以保留英文原名，并在首次出现时补充中文说明。

## 模块化与组件复用

- Activity 只负责 Android 生命周期和 Compose 入口，不承载页面布局或业务逻辑。
- 页面按职责拆分为独立文件和组件，保持 Composable 小而清晰、状态驱动。
- 通用视觉组件必须抽取并复用，例如歌曲列表项、专辑封面、迷你播放器、按钮和主题。
- 跨平台的数据模型和业务规则放入 `shared`，Android 专属 UI 和平台集成放入 `androidApp`。
- 新功能优先扩展现有组件和状态模型，禁止复制粘贴同类 UI；如果组件需要两个以上页面使用，应抽取为公共组件。
- 组件的参数、回调和状态边界要明确，尽量使用单向数据流，避免在组件内部隐藏全局状态。

## 源码可读性

- 生产源码不得压缩、混淆或以单行形式提交；必须保持正常缩进、换行、命名和必要注释。
- Release 构建当前保持 `isMinifyEnabled = false`，确保交付版本可调试、可追踪。
- 使用 Kotlin 官方风格：四个空格缩进，类型和 Composable 使用 `UpperCamelCase`，函数和属性使用 `lowerCamelCase`。
- 公共组件、共享模型和非直观的状态转换必须添加简体中文说明。
- 修改后使用 Android Studio Kotlin formatter 格式化代码。
- TypeScript 源码也必须保持正常换行、缩进和模块职责边界；压缩仅允许发生在构建产物中。

## 构建与运行

在项目根目录执行：

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:installDebug
.\gradlew.bat :androidApp:assembleRelease
.\gradlew.bat build
```

当前要求使用 JDK 21 和项目自带 Gradle Wrapper。生产 APK 输出在 `androidApp/build/outputs/apk/release/`。

**`assembleDebug` 与 `assembleRelease` 都会递增 `version.properties`**（`incrementVersion` 是它们的 `finalizedBy`）。因此：

- 登记发布时版本号只能取自 `androidApp/build/outputs/apk/release/output-metadata.json`，**不能读 `version.properties`** —— 构建结束时它已经比刚产出的包大 1。
- **不要回滚 `version.properties`**，也不要为了让版本号连续而复用旧号。跳号无害，重号会静默覆盖已发布记录的 sha256，导致更新推不出去。

完整规则见 [RELEASE.md](RELEASE.md)。

## 后端开发

- 构建必须用 `tsc`，开发用 `ts-node`。**不能用 esbuild 或 tsx** —— 它们不支持 `emitDecoratorMetadata`，NestJS 的构造器注入会拿不到 `design:paramtypes`。
- 数据层改动后必须跑 `server/tools/verify-contract.mjs`（检查项数量随脚本版本变化，以实际输出为准；当前约 300 项，须全绿），用独立的验证库而不是正式库。
- 新增路由默认就受全局访问令牌守卫保护；公开路由必须显式标 `@Public()`。漏标只会让接口意外要求登录（能立刻发现），不会意外裸奔。
- 数据层与客户端之间有一组不能破的契约（401 不能变 403、`/search` 必须是裸 NDJSON、SQL 别名必须加双引号等），逐条列在 [RELEASE.md](RELEASE.md) 里。

### 管理员认证体系

后端管理后台使用企业级认证系统，代码位于 `server/src/admin-auth/` 和 `server/src/ldap/`：

- **管理员账号**：独立于普通 users 表的 `admin_users` 表，支持三种角色（super_admin / admin / viewer）
- **会话管理**：基于数据库会话的 Bearer token 认证（`Authorization: Bearer <token>`）。历史上并存的静态 `ADMIN_TOKEN` / `X-Admin-Token` 通道**已整体移除**
- **双因素认证 (2FA)**：基于 TOTP 的动态码验证（RFC 6238 / RFC 4226 / RFC 4648 base32 自实现，见 `server/src/admin-auth/totp.ts`，不依赖第三方库）
- **强制改密**：`admin_users.must_change_password` 为 1 时，除 `/me` 与改密接口外一律拒绝（403/4031）
- **登录退避**：账号维度指数退避（5 次后 5→10→20→30 分钟封顶），错误码 4291，与来源地址限流的 4290 区分
- **操作审计日志**：`admin_audit_log` 表记录所有管理员操作
- **IP 白名单**：可选的网络层访问控制
- **LDAP/SSO 集成**：可选的企业目录对接，使用原生 `net` 模块（非 ldapjs 库）

新模块注册在 `app.module.ts`。启动时由 `AdminBootstrapService`（`onApplicationBootstrap`）创建超级管理员 `admin`：配置了 `ADMIN_INITIAL_PASSWORD`（至少 12 位）就用它，否则随机生成一个并在日志里打印一次（`warn` 级）。**两种情况都会置 `must_change_password = 1`，首次登录必须改密。**

### 管理员 API 端点（前缀 `/api/v1/admin/auth`）

| 方法 | 路径 | 最低角色 |
| --- | --- | --- |
| POST | `/admin/auth/login` | 公开 |
| POST | `/admin/auth/totp-verify` | 公开（需携带 `temp_token`） |
| POST | `/admin/auth/logout` | 公开（幂等） |
| GET | `/admin/auth/me` | 任意已认证管理员 |
| POST | `/admin/auth/change-password` | 本人 |
| POST | `/admin/auth/totp-enable` / `totp-confirm` / `totp-disable` | 本人 |
| GET | `/admin/auth/users` | admin |
| POST | `/admin/auth/users` | super_admin |
| PATCH | `/admin/auth/users/:id` | super_admin |
| DELETE | `/admin/auth/users/:id` | super_admin |
| GET | `/admin/auth/audit-log` | admin |
| GET/POST | `/admin/auth/ip-whitelist/:adminId` | super_admin |

### 管理员认证的几条硬约束

- **`@UseGuards(AdminAuthGuard, RolesGuard)` 不能挂在控制器类上**。类级守卫对 `login` 同样生效，而登录时用户还没有凭据，结果所有登录请求先被自己的守卫 401 掉。公开路由必须逐个方法标注，受保护方法统一用 `@AdminGuarded()`。
- **用到 `AdminAuthGuard` 或 `RolesGuard` 的模块必须自己 `imports: [AdminAuthModule]`**。Nest 在**声明 Controller 的模块**里解析守卫的依赖，不是在提供守卫的模块里。漏导入会在启动时抛 `UnknownDependenciesException`，进程完全起不来（`ImageGenerationModule` 踩过）。这条既不是类型错误，静态审计也看不出来。
- **2FA 第二步必须校验 `temp_token`**。它是登录第一步签发的一次性挑战票据（内存存储、5 分钟、用后即焚），把「密码已验证」这个事实带到第二步。缺了它，任何知道 `admin_id` 的人都能跳过密码直接猜 6 位动态码。
- **`AdminAuthGuard` 只认 `Authorization: Bearer <token>`**。静态 `X-Admin-Token` / `ADMIN_TOKEN` 通道已整体移除（它固定 `super_admin`，同时绕过 2FA、IP 白名单、会话撤销和审计归属）。`AdminActor` 上仍有 `auditActorId()` 做防御性收敛，保证任何非正常身份都写 `null` 而不撞外键。`admin_audit_log.admin_id` 与 `admin_users.created_by` 都是 `ON DELETE SET NULL`，管理员被删除后审计记录保留为「无归属」。
- **默认管理员的创建必须挂在 `onApplicationBootstrap`**。建表在 `DatabaseService.onModuleInit`，早于 main.ts 里手动 `app.get()` 的代码执行。
- **不要用类级 `@Public()` 之外的隐式约定**：管理端控制器统一 `@Public()` + 显式 `@AdminGuarded()`，绕开全局访问令牌守卫。
- **CSP 与安全响应头必须挂在 `main.ts` 的 Express 中间件里，且必须在 `expressStatic` 之前**。Nest 拦截器盖不住 `express.static` 直接吐出的文件。后台 CSP 是 `script-src 'self'`，所以 `index.html` 里不能有任何内联 `<script>` —— 主题初始化脚本外置在 `src/frontend/public/theme-bootstrap.js`。

### 管理端授权与审计

- **业务管理接口的角色矩阵只有一个定义处**：`server/src/admin-auth/admin-roles.ts`。新增管理接口时从那里取 `READ_ROLES` / `WRITE_ROLES` / `PRIVILEGED_READ_ROLES`，不要手写角色数组 —— 手写迟早会把 `viewer` 放进写权限。
- **返回个人数据的读接口用 `PRIVILEGED_READ_ROLES`，不要用 `READ_ROLES`**。目前是管理员列表、审计日志、用户资料与听歌历史（`/app/admin/users` 带 `email`，`/app/admin/users/:id/playback` 带逐首歌的播放次数与时间戳）。新增接口时先问一句「这个响应里有没有别人的个人信息」，有就用 `PRIVILEGED_READ_ROLES`。改这类接口要同时改前端的页签可见性（`App.vue`），否则观察者会点进一个只会报错的页签。
- **`/app/admin/*` 与 `/desktop/admin/*` 的写操作必须标 `@RequireRole(...WRITE_ROLES)`**，只读账号（`viewer`）被拒 403/4030。读接口用 `READ_ROLES`。`RolesGuard` 对没标注的路由一律放行，所以**漏标等于没有权限校验**，这一点不会报错、也不会被类型检查发现。
- **管理端写操作必须写审计**：注入 `AdminAuditService`，在操作成功之后 `await this.audit.record(request, "域.动作", targetType, targetId, detail)`。它统一处理「非正常身份不能落库」和「`X-Forwarded-For` 只在 `TRUST_PROXY` 下可信」两个坑。漏写不会报错，但 `admin_audit_log` 里就查不到这次操作。
- **`AdminAuthModule` 必须导出 `AdminAuditService`**（以及 `AdminAuthService`、`AdminUsersRepository`）。业务模块的控制器要用它们，而依赖是在**声明 Controller 的模块**里解析的。
- **`common/guards/admin-token.guard.ts` 的 `AdminTokenGuard` 已删除**。它曾是死代码（全项目零引用），所有管理控制器用的都是 `AdminAuthGuard`。不要照着历史文件名推断鉴权行为，也不要用 `grep` 之外的方式猜守卫是否生效 —— 要 `grep` 它在 `@UseGuards` 里的实际引用。


## 测试规范

新增共享逻辑时，在 `shared/src/commonTest/` 添加 `*Test.kt`；Android 单元测试放在 `androidApp/src/test/`，设备测试放在 `androidApp/src/androidTest/`。

可使用以下命令验证：

```powershell
.\gradlew.bat :shared:allTests
.\gradlew.bat :androidApp:testDebugUnitTest
```

## 安全与签名

- 不得提交密码、API Key、机器专属 SDK 路径或其他敏感信息。
- `local.properties` 只保存在本机，用于 SDK 路径和本地发布签名配置，不得提交到版本库。
- 发布签名文件必须妥善备份；签名密码不得写入公共源码、README 或聊天记录以外的仓库文档。
- 发布前检查 `validateSigningRelease`，并使用 `apksigner verify` 验证 APK 签名。

## 提交与交付

提交信息使用简洁的祈使句，可带模块范围，例如 `ui: 增加播放器控制栏`。交付 UI 变更时说明影响模块、验证命令和 APK 输出路径；如果有界面变化，应附模拟器截图或录屏。

## 验收优先级

- 默认以签名 Release APK 生成为主要验收标准，修改完成后优先执行 `.\gradlew.bat :androidApp:assembleRelease`。
- 仅在定位编译问题、快速验证局部改动或 Release 构建受阻时，单独执行 Kotlin 编译测试；单独编译不能替代 APK 交付。
- 交付时必须提供最新 Release APK 的绝对路径、版本号和构建结果。
- 后端改动以 `server/tools/verify-contract.mjs` 全绿为验收标准。

## 界面新增页面的检查清单

导航是手写的 `AnimatedContent`，新增一整页时必须同步**三处**，漏一处就会出现"点了底部标签却还停在原页面"：

1. `switchTab`：切换底部标签时把新页面的显示状态复位。
2. `AnimatedContent` 的 `targetState`：加上对应分支。
3. `BackHandler`：把新页面纳入返回键处理。
