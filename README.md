# 桃桃音乐

Kotlin Multiplatform 音乐播放器 + 自建后端。当前提供 Android、Windows 客户端和 Web 分享播放器；`shared` 复用模型，`player-ui` 统一三端主题与播放界面组件。

## 📚 文档导航

| 文档 | 说明 | 适用场景 |
|------|------|----------|
| [AGENTS.md](AGENTS.md) | 开发规范：代码风格、模块划分、测试、签名 | 日常开发、代码审查 |
| **[RELEASE.md](RELEASE.md)** | 发布与热更新流程、版本号铁律、不能破的契约 | **推版本前必读** |
| [server/README.md](server/README.md) | 后端架构、接口文档、数据层规则、契约验证 | 后端开发、接口对接 |
| [server/wiki/00-code-index.md](server/wiki/00-code-index.md) | CodeGraph 后端代码索引、104 条路由、24 张表与配置快照 | 核对源码、路由和文档是否漂移 |
| [client-code-index.md](client-code-index.md) | CodeGraph 客户端索引、模块边界、关键符号与刷新状态 | 客户端架构定位、跨模块调用和文档同步 |
| [MUSIC_CROSS_PLATFORM.md](MUSIC_CROSS_PLATFORM.md) | Android/Windows 音乐功能、定时播放、云端歌单契约 | 双端开发、联调和验收 |
| [HOT_UPDATE.md](HOT_UPDATE.md) | 热更新设计动机与边界 | 理解热更新能力范围 |

> **⚠️ 重要提醒**：发布新版本或修改后端接口前，务必先阅读 [RELEASE.md](RELEASE.md)，其中包含多条不能违反的铁律和契约。

## 🏗️ 项目结构

```
androidApp/   Android 应用
              ├─ Jetpack Compose 界面（Material3 设计）
              ├─ Media3 ExoPlayer 播放引擎
              ├─ 热更新客户端（整包 + DEX 补丁）
              ├─ 离线下载管理
              └─ 崩溃日志捕获与导出

desktopApp/   Windows 应用
              ├─ Compose Desktop 宽屏界面
              ├─ FFmpeg + Java Sound 播放引擎
              ├─ Windows 系统媒体控件与托盘后台
              ├─ 搜索、队列、歌词、收藏与最近播放
              └─ 离线下载和本地设置

webApp/       Kotlin/Wasm 分享播放器
              ├─ 循环播放一首 60 秒低码率试听
              └─ 复用 player-ui，不包含队列和歌曲下载

player-ui/    Android、Windows、Web 共用的播放界面层
              ├─ 统一亮色/暗色主题和圆角规格
              ├─ 歌曲信息、进度与主播放控制
              ├─ 加载、空数据和错误状态视图
              ├─ Android/Windows 共用歌曲行与迷你播放器
              └─ 紧凑/宽屏响应式播放器和主布局骨架

shared/       跨平台共享层（Kotlin Multiplatform）
              ├─ 歌曲、歌词、专辑数据模型
              ├─ 歌词解析器（LRC 行级 + YRC 逐字）
              ├─ 音质档位枚举与映射
              └─ 单元测试覆盖

server/       NestJS 后端服务（TypeScript + PostgreSQL）
              ├─ 用户认证与会话管理
              ├─ 收藏、播放历史、听歌统计
              ├─ QQ 音乐 / 网易云音乐上游适配
              ├─ 热更新版本管理与灰度分发
              ├─ 分享短链、试听缓存与公告
              ├─ Windows 模块清单、内容寻址对象与差分发布
              ├─ 悟空 IM 会话与同步代理
              ├─ 邮箱验证码、头像上传与后台用户管理
              ├─ AI 图片生成任务代理
              └─ 管理后台（Vue 3 + Element Plus）

patch/        热修复补丁模块
              └─ 独立编译的 DEX 文件（几 KB），不打入 APK

build-logic/  Gradle 插件
              └─ 编译期字节码插桩：为逻辑层方法注入补丁拦截入口
```

### 架构原则

- **客户端不直接调用第三方音乐接口**，所有请求经过自建后端中转
- **后端以接口适配为主**，不保存完整媒体内容：
  - **实时转发**：音频流、图片、歌词
  - **持久化**：用户账号、令牌哈希、收藏记录、歌单、播放统计、发布清单
  - **试听例外**：分享短链会缓存最多 60 秒、64 kbps 的服务端裁剪 MP3
  - **不存储**：完整歌曲文件、封面图片、上游 API 响应（除必要的元数据）
- **播放音频直连 CDN**：客户端从后端获取上游直链后，直接从 QQ 音乐 CDN 拉流，音频字节不经过自建服务器

## 🎵 完整播放链路详解

理解这条链路就理解了整个项目的核心设计。

### 1️⃣ 搜索阶段

```
客户端 → GET /api/v1/search?keyword=歌名&num=60&quality=10
         ↓
后端打一次上游获取列表
         ↓
逐行以 NDJSON 流式写出（每行一首歌的元信息）
         ↓
客户端收到一行渲染一行（增量显示，无需等待全部结果）
```

**性能数据**：
- 本机实测：130–900ms（取决于上游缓存命中）
- 线上实测：首行约 1.2 秒（含 TCP 握手、TLS、上游请求），首行之后 60 行在 18ms 内全部到达

**搜索结果包含的关键字段**：
- `favorited`：当前用户是否已收藏（一次批量 SQL 查询填入，避免客户端 N+1 查询）
- `vip`：是否为付费/会员专属歌曲（用于界面标记）
- `mid` / `type`：解析播放地址时必需的参数（`songID` 为 0 的歌只能靠 `mid` 解析）

**为什么搜索不解析播放地址？**

早期版本在搜索时为每首歌解析播放链接并探测首字节：
- 20 首歌约 1.8 秒
- 60 首歌最坏情况 240 次上游请求
- **客户端拿到地址后又会丢掉它**（因为用的是占位地址）

这些请求换来的只是"过滤掉拿不到地址的歌"。现在改为点播时才解析，搜索只需一次上游请求。

**代价与权衡**：
- 付费/下架的歌现在会出现在搜索结果里
- 通过 `vip` 字段在界面标记
- 真正无法播放的歌在点击时才提示

### 2️⃣ 入队阶段

```
用户点击某首歌
    ↓
客户端将占位地址加入播放队列
    /api/v1/songs/{id}/play?quality=10
    ↓
这个地址永不过期，可以安全持久化到播放列表
```

**为什么用占位地址？**
- 上游直链是限时的（几小时后失效）
- 如果直接存直链，冷启动恢复队列时已失效
- 占位地址在真正播放时才换成实际直链

### 3️⃣ 取流阶段（关键设计）

```
ExoPlayer 准备播放
    ↓
ResolvingDataSource 拦截占位地址
    ↓
GET /api/v1/songs/{id}/link?quality=10&mid=&type=
    ↓
后端解析出上游直链并返回实际档位
    ↓
客户端拿到真实 CDN 地址
```

**返回示例**：
```json
{
  "code": 0,
  "data": {
    "songId": "97773",
    "url": "https://ws.stream.qqmusic.qq.com/...",
    "quality": 10,
    "requestedQuality": 12,
    "kbps": "1644kbps",
    "fallback": true
  }
}
```

**音质降级机制**：
- 上游不会自动降级（付费歌请求高音质可能返回错误）
- 后端实现音质阶梯：`[18, 14, 11, 10, 8, 4, 0]`
- 先查询 `/song/info` 获取该歌真实存在的档位，直接挑一个可用的
- `info` 失败时才退回阶梯逐档尝试，最多试 4 档
- `fallback: true` 表示发生了降级，客户端据此提示用户

**失败处理**：
- 解析失败返回 `502`，**绝不能返回 401**
- 返回 401 会触发客户端的令牌续期逻辑，把"上游问题"误判为"登录失效"

### 4️⃣ 播放阶段

```
ExoPlayer 用真实 CDN 地址拉流
    ↓
直接连接 QQ 音乐 / 网易云 CDN
    ↓
音频字节不经过自建服务器（节省带宽，降低延迟）
```

**为什么不走代理？**
- 自建服务器带宽有限，代理会成为瓶颈
- 直连 CDN 延迟更低，体验更好
- 解析失败明确报错，不会悄悄绕回自建服务器

**保留的兜底路径**：
- `GET /api/v1/songs/{id}/play` 仍然保留
- 装机的旧客户端在用
- 支持 Range 断点续传（透传给上游）
- 上游非 2xx 统一归成 `502`（不透传上游状态码）

### 5️⃣ 歌词阶段

```
GET /api/v1/songs/{id}/lyrics?format=json
    ↓
后端同时返回：
  - lrc：行级时间轴（标准 LRC 格式）
  - yrc：逐字时间轴（格式：[行起始ms,行时长ms]文本(字起始ms,字时长ms)...）
  - trans：翻译歌词（如有）
    ↓
客户端根据 yrc 实现逐字高亮卡拉 OK 效果
```

**兼容性处理**：
- 默认返回纯文本 LRC（`Content-Type: text/plain`）
- 带 `format=json` 时才返回 JSON 信封
- 旧客户端不带 `format` 参数，直接拿到 LRC 文本

### 🔄 完整时序图

```
[搜索] → [入队占位地址] → [播放时解析直链] → [直连CDN拉流] → [并行拉歌词]
  ↓           ↓                  ↓                 ↓              ↓
130ms      即时              首次约500ms        开始播放        即时
        (内存操作)      (后续命中缓存更快)   (直连无转发)   (JSON响应)
```

## ⚙️ 核心功能详解

### 🎧 播放引擎

**技术栈**：Media3 `MediaSessionService` + ExoPlayer

**能力清单**：
- ✅ 后台播放（系统杀进程后自动恢复）
- ✅ 锁屏控制与通知栏媒体控制
- ✅ 拔耳机自动暂停
- ✅ 音质动态切换（播放页可临时切档，下次播放从设置档位开始）
- ✅ 播放队列持久化（冷启动恢复上次播放位置）

**架构设计**：
- `AudioPlayer`：业务层播放器，通过 `MediaController` 下发指令
- `PlaybackService`：`MediaSessionService` 实现，持有 `ExoPlayer` 实例
- `ResolvingDataSource`：自定义数据源，拦截占位地址并换成真实直链
- **所有播放指令统一走 `MediaController`**，不使用自定义 action

**前台服务契约（踩过的坑）**：
- `startForegroundService` 后**必须在几秒内**调用 `startForeground`
- 否则系统直接杀进程（Android 8+ 严格执行）
- Media3 的 `onStartCommand` 会静默忽略自定义 action
- 当年"播放一段时间就闪退"就是这么来的
- 所以播放指令走 `MediaController`，而下载通知刻意**不用**前台服务

### 🔐 账号与认证

**密码存储**：随机盐 + 高成本 scrypt（内存成本 16384，并行度 8，块大小 8）

**令牌体系**：
- **访问令牌**：15 分钟有效期，格式 `base64url(payload).HMAC-SHA256`
- **刷新令牌**：30 天有效期，只存 SHA-256 哈希，**刷新时轮换**
- 刻意没用 JWT 库 —— 换格式会让所有装机客户端的令牌立刻失效

**自动续期机制**：
- 客户端拦截所有 `401` 响应
- 自动调用 `/auth/refresh` 获取新令牌
- 用新令牌重放原请求
- 用户无感知

**限流保护**：
- 登录 / 注册：按来源地址 10 次 / 15 分钟
- 各用途计数器互相独立（登录、图片创建、图片轮询、更新检查）

### ❤️ 收藏系统

**特性**：
- 搜索结果内联 `favorited` 字段（一次批量查询，避免 N+1）
- 客户端本地缓存 + 乐观更新（点击立即生效，请求失败时回滚）
- 服务端的值是权威值（刷新时以服务端为准）
- 软删除设计：取消收藏不删记录，只标记失效

**字段设计**：
- `createdAt` / `firstFavoritedAt`：第一次收藏时间（取消后重新收藏不变）
- `favoritedAt`：当前这一轮收藏开始的时间

### 🎚️ 音质系统

**档位范围**：0–18（18 为 NAC 无损）

**双重设置**：
- **播放默认音质**：影响加入队列时的档位
- **下载默认音质**：下载只花一次流量，可以选得更高
- 播放页可临时切档（不影响设置）

**档位选择器**：
- 调用 `/api/v1/songs/{id}/info` 获取该歌**真实存在**的档位
- 只列出 `size > 0` 的档位并显示文件体积
- 下载前预览体积，避免下到一半发现太大

**降级策略**（后端实现）：
- 音质阶梯：`[18, 14, 11, 10, 8, 4, 0]`
- 先查 `info` 挑可用档，`info` 失败时逐档尝试
- 最多试 4 档，每档都会探测首字节
- 跳过 `kbps=0kbps` 的死链
- `18` 是腾讯 NAC 私有容器；Windows 播放器暂不解码该格式，桌面端会过滤并提示改选其他档位。网易云的最高档统一标记仍按最终音频容器判断。

### 📥 离线下载

**下载内容**（一键打包）：
- 音频文件（用户选择的音质档位）
- 封面图片
- 行级歌词（LRC）
- 逐字歌词（YRC，用于卡拉 OK 效果）

**存储位置**：应用私有目录（`filesDir`），卸载时自动清除

**下载流程**：
1. 用户在音质选择器选档（看到文件体积）
2. 系统通知显示进度
3. 下载到 `.part` 临时文件
4. 全部完成后 `rename` 为正式文件名（原子操作）
5. 失败时只删 `.part`，不影响已完成的下载

**断点续传**：
- 支持 HTTP Range 请求
- 中断后从已下载位置继续
- 校验文件完整性（对比预期大小）

### 🔥 热更新系统

**两条通道**：

| | 整包更新 | 热修复补丁 |
|---|---|---|
| **内容** | 完整 APK（约 14MB） | 只含改动方法的 DEX（几 KB） |
| **生效** | 用户确认安装 → 重启 | **立即生效，不用重启** |
| **能改什么** | 全部 | **只有 `data` / `player` / `update` 包** |
| **用在** | 功能、界面、依赖升级 | 逻辑 bug 的紧急止血 |

**为什么界面代码不能热修？**
- Compose 的可组合函数依赖 `startRestartGroup` / `endRestartGroup` 严格配对
- 在函数开头插入提前 return 会破坏组结构
- 所以 `ui/` 包刻意没有插桩
- UI bug 只能发整包

**灰度放量机制**：
- 命中条件：`sha1(发布ID + ":" + 主体标识) % 100 < rollout_percent`
- 混入发布 ID：每个版本独立分桶，不是永远同一批人当小白鼠
- 用哈希而非随机数：对同一用户稳定，更新提示不会时有时无
- 实测 20000 个设备号在 10% / 30% / 50% 下的命中率：9.60% / 29.93% / 50.46%

**补丁自愈机制**（为什么敢上线）：
1. 加载补丁**之前**把尝试计数 +1 并**同步写盘**
2. 应用平稳跑起来（界面组合完成再等 5 秒）后清零
3. 启动时发现计数不为 0 → **直接弃用该补丁并删文件**
4. 失败过的补丁版本会被记住，不再重试
5. 宿主 versionCode 与补丁目标版本必须严格相等，升级后旧补丁自动失效

**最坏情况**：崩一次就自动回滚，而不是反复崩。

**管理后台**：
- 浏览器打开 `/admin/` 路径（如 `https://music.xydaigua.cn/admin/`），服务根路径不是后台入口
- 首次进入用管理员账号密码登录，会话令牌存在浏览器 localStorage，有效期 24 小时
- 首次部署由服务端创建 `admin` 账号：设了 `ADMIN_INITIAL_PASSWORD`（至少 12 位）就用它，否则随机生成并在启动日志里打印一次。**两种情况首次登录都会被强制改密**（403/4031）
- 功能：发布列表与放量、补丁列表与放量、强制更新下限、公告管理、用户统计

### 💥 崩溃日志

**背景**：测试机无法连 adb，远程排查困难

**实现**：
- 全局捕获未处理异常
- 堆栈写到本地文件（`filesDir/crashes/`）
- 「我的」页内可查看、复制
- 导出成 `.log` 文件交给系统分享
- 多条堆栈叠起来轻易上万字符，剪贴板装不下，所以必须支持文件导出

## 🚀 开发指南

### 客户端开发

**环境要求**：
- JDK 21
- Android SDK（使用项目自带 Gradle Wrapper）

**常用命令**：

```powershell
# 调试构建（会递增版本号）
.\gradlew.bat :androidApp:assembleDebug

# 发布构建（验收标准，会递增版本号）
.\gradlew.bat :androidApp:assembleRelease

# 共享层单元测试
.\gradlew.bat :shared:allTests

# Android 单元测试
.\gradlew.bat :androidApp:testDebugUnitTest

# 完整构建（所有模块）
.\gradlew.bat build
```

**产物位置**：`androidApp/build/outputs/apk/release/androidApp-release.apk`

**⚠️ 重要提醒**：
- **两种 assemble 都会递增 `version.properties`**
- 这是设计行为，不是 bug
- 版本号跳号无所谓，但**绝不能回滚** `version.properties`
- 详见 [RELEASE.md](RELEASE.md) 的版本号铁律

**发布签名配置**：
- 签名配置读 `local.properties`（不进版本库）
- Release 构建保持 `isMinifyEnabled = false`（确保可调试、可追踪）
- 签名密钥文件 `taotao-release.jks` 必须妥善备份（丢失无法恢复）

### Windows 客户端开发

Windows 版本当前优先覆盖音乐功能：账号、双源搜索、播放队列、逐行/逐字歌词、收藏、最近播放、离线下载、主题和音质设置。两端均支持云端歌单（创建、改名、删除、歌曲增删与排序）和按实际播放时间倒计时的定时停止；后端虽已提供 AI 图片与 IM 接口，Android/Windows 客户端当前暂不接入。完整的接口字段、同步边界和定时器行为见 [MUSIC_CROSS_PLATFORM.md](MUSIC_CROSS_PLATFORM.md)。

```powershell
# 运行桌面客户端
.\gradlew.bat :desktopApp:run

# 单元测试与共享逻辑测试
.\gradlew.bat :desktopApp:test :shared:allTests

# 生成 Windows 便携 JAR
.\gradlew.bat :desktopApp:packageUberJarForCurrentOS

# 生成 EXE / MSI（需要 WiX Toolset 3.11）
.\gradlew.bat :desktopApp:packageDistributionForCurrentOS
```

便携产物位于 `desktopApp/build/compose/jars/`，本地数据位于 `%APPDATA%\TaotaoMusic`。登录令牌使用 Windows Java Preferences 存储，不写入项目文件。

桌面播放使用 FFmpeg 解码并通过 Java Sound 输出，支持 MP3、M4A/AAC、WAV、FLAC、OGG/Opus，播放页与设置页均提供标准、HQ、无损、Hi-Res、臻品母带五档音质。腾讯 NAC 私有格式（quality=18）暂不支持 Windows 解码，选择该档位时会明确提示；网易云的 quality=18 是统一最高档标记，按实际返回容器处理。窗口关闭时（系统托盘可用）会隐藏到后台，Windows 系统媒体控件和全局媒体键可控制播放；“退出应用”才会结束进程。下载、歌词、收藏和最近播放均支持离线缓存，播放统计先写本地 outbox，网络恢复后补传。

便携 JAR 需要 JDK/JRE 21；FFmpeg 与系统媒体控制依赖的许可证清单见 [`desktopApp/THIRD_PARTY_NOTICES.md`](desktopApp/THIRD_PARTY_NOTICES.md)。

MSI/EXE 打包需要 WiX Toolset 3.11；若 Gradle 下载的 `wix311.zip` 损坏，请配置有效的 `WIX_PATH` 后重试。

### 后端开发

**环境要求**：
- Node.js 20+
- PostgreSQL 14+

**首次启动**：

```powershell
# 1. 创建数据库（只需一次）
psql -U postgres -c "CREATE DATABASE music"

# 2. 安装依赖
cd server
npm install

# 3. 配置环境变量
copy .env.example .env
# 编辑 .env，填写：
#   DATABASE_URL=postgres://postgres:密码@localhost:5432/music
#   AUTH_SECRET=至少32位随机值（必填）
#   ADMIN_INITIAL_PASSWORD=初始管理员口令，至少12位（可选，不填则启动时随机生成并打印一次）

# 4. 启动开发服务器
npm run dev
```

**建表说明**：
- 建表由服务启动时自动完成（幂等 DDL + 顾问锁）
- 不需要手工执行迁移
- PostgreSQL 必须先于本服务启动

**开发约束**：
- **构建必须用 `tsc`**，不能用 esbuild 或 tsx
- **开发用 `ts-node`**（`npm run dev`）
- 原因：esbuild 不支持 `emitDecoratorMetadata`，NestJS 的构造器注入会失败

**管理后台开发**：

```powershell
# 独立开发服务器（Vite，端口 5173）
npm run dev:frontend

# API 请求默认代理到本机后端（4500 端口）；其它端口先设置 VITE_API_PROXY_TARGET

# 构建前端（产出到 dist/public/）
npm run build:frontend

# 完整构建（后端 + 前端）
npm run build
```

**生产构建与部署**：

```powershell
# 1. 完整构建
npm run build
# - 先用 tsc 编译后端到 dist/
# - 再用 Terser 压缩 dist/ 内的 JS 并移除注释
# - 再用 vite build 把管理后台产到 dist/public/
# - 最后构建 Kotlin/Wasm 分享播放器到 dist/share-player/

# 2. 上传 dist/ 整个目录和 package-lock.json 到服务器

# 3. 在 dist/ 同级执行
npm install --omit=dev

# 4. 启动服务
node dist/main.js
# 或在 dist/ 内执行 npm start
```

**契约验证（改后端必做）**：

```powershell
# 1. 创建独立的验证数据库（只需一次）
psql -U postgres -c "CREATE DATABASE music_verify"

# 2. 重置数据库结构
node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify

# 3. 用验证库启动一个实例（端口 4720）
$env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
$env:PORT="4720"; $env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"
$env:ADMIN_INITIAL_PASSWORD="verify-initial-123456"
$env:CORS_ALLOWED_ORIGINS="https://verify.example"
$env:NODE_ENV="test"; $env:EMAIL_VERIFICATION_TEST_CODE="123456"
$env:IM_ENABLED="false"
npm run dev

# 4. 另一个终端运行契约脚本（以脚本实际输出为准，须全绿）
node tools/verify-contract.mjs http://127.0.0.1:4720
```

**⚠️ 重要约定**：
- 重置数据库后**必须重启服务**（建表只在启动时执行一次）
- 部署前**备份线上现有产物**（`bootstrap` 异常立刻回滚）
- 线上有装机客户端，破坏契约会悄无声息地让功能失效
- 改动后必须逐条核对 [RELEASE.md](RELEASE.md) 的"绝对不能破的客户端契约"

## ⚠️ 反复踩过的坑（必读）

这些坑都是真实发生过的，留在这里避免重复付学费。完整清单见 [RELEASE.md](RELEASE.md)。

### 🔴 客户端侧

#### 1. 前台服务契约

**问题**：`startForegroundService` 之后必须在几秒内调 `startForeground`，否则系统直接杀进程。

**表现**：播放一段时间就闪退，无任何日志。

**根源**：Media3 的 `onStartCommand` 会静默忽略自定义 action。

**解决**：
- 播放指令走 `MediaController`，不用自定义 action
- 下载通知刻意**不用**前台服务

#### 2. `remember(key)` 与 `LaunchedEffect(key)` 的 key 必须一致

**问题**：key 不一致会出现"状态被清空、拉取逻辑却不重跑"。

**表现**：进度停在 0:00 或歌词空白。

**解决**：确保同一个状态的 `remember` 和 `LaunchedEffect` 用同一个 key。

#### 3. 新增整页要同步三处

**问题**：导航是手写的 `AnimatedContent`，漏一处就会出问题。

**表现**：点了底部标签却还停在原页面。

**必须同步的三处**：
1. `switchTab`：切换底部标签时把新页面的显示状态复位
2. `AnimatedContent` 的 `targetState`：加上对应分支
3. `BackHandler`：把新页面纳入返回键处理

#### 4. `LazyColumn` 会销毁滚出屏幕的项

**问题**：item 内部的 `remember` 会随之重置。

**表现**：入场动画每次滚回来都重播。

**解决**：需要"只播一次"的状态要提到列表外面。

#### 5. 队列里只存永不过期的占位地址

**问题**：上游直链是限时的（几小时后失效）。

**表现**：冷启动恢复队列时无法播放。

**解决**：
- 队列存 `/api/v1/songs/{id}/play?quality=N`
- 真正播放时在 `ResolvingDataSource` 里换成实际直链
- 占位地址还能保证 `queueHasAllAudio` 判断正确

### 🔴 后端侧

#### 6. Node 未处理的 promise rejection 会终止进程

**问题**：并发任务必须在**创建时**就挂上失败处理。

**表现**：留到循环里再 await 就晚了，Node 进程直接退出。

**解决**：
```typescript
// ❌ 错误
const tasks = items.map(item => doAsync(item));
for (const task of tasks) {
  try { await task; } catch (e) { /* 晚了 */ }
}

// ✅ 正确
const tasks = items.map(item =>
  doAsync(item).catch(e => { /* 立即处理 */ })
);
await Promise.all(tasks);
```

同理，`pool.on("error")` 必须挂监听，否则数据库重启会带走 Node 进程。

#### 7. `setGlobalPrefix` 的 `exclude` 不能写通配符

**问题**：写成 `exclude: ["health", "/*"]` 会把**所有**路由都从 `/api/v1` 前缀里豁免掉。

**表现**：接口全线 404，装机客户端瞬间全线失联。

**解决**：静态资源用 express 中间件在路由之前拦截，不要动全局前缀。

#### 8. 动态加载的 dex 必须先设只读

**问题**：Android 14+ 加载可写 dex 会抛 `SecurityException`。

**表现**：下载、校验、落盘全部成功，只在加载那一刻失败，而异常被捕获后补丁被永久标记为失败 —— 不闪退、界面无变化、重启和重新放量都没用。

**解决**：`HotfixInstaller` 落盘后调 `setReadOnly()`，`HotfixLoader.load` 再兜一次底。

#### 9. 访问令牌无效必须返回 401，不能 403

**问题**：客户端只对 401 触发续期重放。

**表现**：返回 403 会让所有接口失去自动续期。

#### 10. `/app/bootstrap` 永远不能返回 401

**问题**：客户端刻意带着**已过期**的令牌调它。

**表现**：返回 401 会让热更新通道静默失效且无任何报错。

**解决**：这个接口的 `Authorization` 是可选的，有效就用，无效就退回 `deviceId`。

#### 11. SQL 别名必须加双引号

**问题**：PostgreSQL 把不加引号的标识符折叠成小写。

```sql
-- ❌ 错误
SELECT song_id AS songId FROM favorites

-- ✅ 正确
SELECT song_id AS "songId" FROM favorites
```

**表现**：客户端读不到 `songId`（只能读到 `songid`），所有歌都显示未收藏，且没有任何报错。

#### 12. 存 `Date.now()` 的列用 `bigint`，其余整数用 `integer`

**问题**：
- 毫秒时间戳约 1.7e12，超出 int4 的 21 亿上限
- pg 默认把 int8 解析成**字符串**

**解决**：
- 时间戳列用 `bigint`
- 其它整数用 `integer`
- 注册 `INT8 → Number` 的解析器（`database.service.ts`）

#### 13. `app_release.enabled` 是 `smallint` 0/1，不是 `boolean`

**问题**：代码里一处是 `=== 1`（严格等于数字），一处是 `!enabled`（真值判断）。

**表现**：改成 boolean 会让强制更新守卫永远返回 409。

#### 14. 搜索结果 `data.id` 必须是 JSON number，收藏 `songId` 必须是字符串

**问题**：类型漂移会让 `remoteId=null`。

**表现**：该歌不可播、不可收藏、无歌词。

#### 15. `coverUrl` / `apkUrl` 必须是绝对 https 且免鉴权

**问题**：客户端直接交给图片库和下载器，不带 `Authorization`。

**表现**：需要鉴权的地址会失败；明文 HTTP 被系统拦截。

### 🔴 版本号铁律

#### 16. 版本号只能从 `output-metadata.json` 读，不能读 `version.properties`

**问题**：递增发生在**构建结束之后**，所以构建完成时 `version.properties` 已经比刚产出的包大 1。

**表现**：客户端陷入死循环：提示有新版本 → 装完发现自己版本号就是那个 → 还提示有新版本。

**解决**：
```powershell
# ✅ 正确：问构建产物自己是什么版本
node -e "const d=require('./androidApp/build/outputs/apk/release/output-metadata.json').elements[0];console.log(d.versionCode, d.versionName)"
```

#### 17. 绝不能回滚 `version.properties`

**问题**：`incrementVersion` 对 debug 构建也生效，版本号跑在已发布版本前面是正常的。

**表现**：回滚后下一次构建会产出同一个 versionCode，登记接口的 `ON CONFLICT DO UPDATE` 会**静默覆盖**已发布记录的 sha256。已装该版本的客户端看到 `available=false`，更新推不出去；还没下载完的客户端会因为 sha 不匹配而校验失败。

**解决**：宁可跳号，也不要回滚。版本号跳号无所谓，重号是事故。

---

**💡 完整坑列表见 [RELEASE.md](RELEASE.md)**，包含客户端契约、服务端契约、热修复规则。
## 📊 技术栈总览

### 客户端

| 技术 | 用途 | 版本/说明 |
|------|------|-----------|
| Kotlin Multiplatform | 跨平台框架 | Android + JVM Desktop 共享模型 |
| Jetpack Compose | UI 框架 | Material3 设计语言 |
| Compose Desktop | Windows UI | Material3 宽屏界面 |
| FFmpeg + Java Sound | Windows 音频播放 | MP3 / M4A / AAC / WAV / FLAC / OGG / Opus |
| JavaMediaTransportControls | Windows 系统媒体 | 系统媒体控件、媒体键、托盘后台 |
| Media3 ExoPlayer | 音频播放引擎 | MediaSessionService 架构 |
| Kotlin Coroutines | 异步编程 | Flow + StateFlow 状态管理 |
| OkHttp | HTTP 客户端 | 网络请求与流式响应 |
| Coil | 图片加载 | 异步加载封面图 |
| AndroidX DataStore | 数据持久化 | 替代 SharedPreferences |

### 后端

| 技术 | 用途 | 版本/说明 |
|------|------|-----------|
| NestJS | Web 框架 | TypeScript + 装饰器风格 |
| PostgreSQL | 关系数据库 | 14+ |
| node-postgres (pg) | 数据库驱动 | 连接池模式 |
| Express | HTTP 服务器 | NestJS 底层 |
| Vue 3 | 管理后台前端 | Composition API |
| Element Plus | UI 组件库 | 管理后台样式 |
| Vite | 前端构建工具 | 管理后台打包 |

### 开发工具

| 工具 | 用途 |
|------|------|
| Gradle | 构建系统（Kotlin DSL） |
| Android Studio | Android 开发 IDE |
| VS Code | 后端开发推荐 |
| PowerShell | 脚本与命令执行 |

## 🔗 相关链接

- **上游音乐接口**：QQ 音乐 API v3、网易云音乐 API（通过第三方聚合服务）
- **管理后台**：浏览器访问 `/admin/` 路径（如 `https://music.xydaigua.cn/admin/`）
- **热更新设计**：参见 [HOT_UPDATE.md](HOT_UPDATE.md)
- **发布流程**：参见 [RELEASE.md](RELEASE.md)

## 📝 提交规范

**提交信息格式**：`<类型>(<模块>): <简短描述>`

**类型**：
- `feat`：新功能
- `fix`：修复 bug
- `refactor`：重构（不改变功能）
- `perf`：性能优化
- `style`：代码格式（不影响功能）
- `test`：测试相关
- `docs`：文档更新
- `chore`：构建、工具、依赖等

**示例**：
```
feat(player): 支持播放队列拖拽排序
fix(auth): 修复刷新令牌轮换时的竞态条件
refactor(ui): 提取歌曲列表项为独立组件
perf(search): 搜索改为流式响应，首屏提速 60%
docs(release): 补充热修复补丁的四条禁忌
```

## 🤝 贡献指南

1. **开发前先读文档**：
   - [AGENTS.md](AGENTS.md)：了解代码规范和模块结构
   - [RELEASE.md](RELEASE.md)：如果涉及发布或后端接口

2. **新增功能**：
   - 跨平台的模型和业务逻辑放 `shared/`
   - Android 专属 UI 和平台能力放 `androidApp/`
   - 通用组件必须抽取并复用，禁止复制粘贴

3. **改动后端**：
   - 必须跑契约验证（以脚本实际输出为准，全部须全绿）
   - 逐条核对"不能破的客户端契约"
   - 改数据层后人工检查所有 `.find()` / `.map()` 回调

4. **测试**：
   - `shared/` 有单元测试的必须保持通过
   - 新增共享逻辑时在 `shared/src/commonTest/` 添加测试
   - Release 构建能成功是最低验收标准

5. **交付**：
   - 提供 Release APK 的绝对路径和版本号
   - 后端改动提供契约验证通过的截图
   - UI 变更附上截图或录屏

## 📜 许可证

本项目为个人学习项目，暂未设定开源许可证。

## 🙏 致谢

- **QQ 音乐 / 网易云音乐**：音乐资源提供方
- **第三方聚合接口**：简化上游对接
- **开源社区**：Kotlin、Compose、NestJS、PostgreSQL 等优秀技术栈
