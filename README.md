# 桃桃音乐

> 一个自用的跨平台音乐播放器 —— Kotlin Multiplatform 客户端 + 自建 NestJS 后端。
> 客户端不直连任何第三方音乐接口，全部经自建后端中转后直连 CDN 拉流。

桃桃音乐是一套完整的个人音乐应用：Android 与 Windows 双端原生客户端、一个浏览器里就能用的
Web 分享播放器，以及支撑它们的自建后端。三端共享同一套数据模型与播放界面组件，后端负责
多音源适配、账号体系、歌单与统计、热更新分发。

- **平台**：Android / Windows（Tauri）/ Web（Kotlin/Wasm 分享页）
- **形态**：原生客户端 + 自建后端，无第三方 SDK 依赖播放链路
- **定位**：个人学习项目，代码完全可读、可调试、可追踪

---

## ✨ 功能特性

### 🎧 播放体验

- 多音源切换（腾讯 / 网易 / 酷我），音质档位 0–18，无损最高档可用
- 逐字歌词（YRC）卡拉 OK 高亮，支持翻译歌词
- 后台播放、锁屏控制、通知栏媒体控制、拔出耳机自动暂停
- 播放队列持久化，冷启动恢复上次播放位置与进度
- 播放页可临时切换音质，下载可单独选择更高档位
- 离线下载：音频 + 封面 + 行级歌词 + 逐字歌词一键打包，支持断点续传

### 🖥️ 三端界面

- **Android**：Jetpack Compose + Material3，搜索 / 歌单 / 最近播放 / 收藏 / 我的
- **Windows**：Tauri 2 + React 18，全屏播放详情、队列拖拽排序、定时关闭、系统媒体控件
- **Web**：Kotlin/Wasm 分享播放器，分享短链即可在浏览器里试听

三端共用 `player-ui` 的主题与播放界面层、`shared` 的数据模型与歌词解析。

### 🛠️ 后端能力

- 用户认证（scrypt 密码 + 访问/刷新令牌自动续期）
- 收藏、播放历史、听歌统计、云端歌单
- 多音源上游适配与音源账号管理
- 热更新：整包更新 + DEX 热修复补丁，支持灰度放量
- 分享短链、公告、邮箱验证码、头像上传
- 传输层加密（PSK 握手 + AEAD，可选，未启用时明文降级）
- 管理后台（Vue 3 + Element Plus，TOTP / LDAP / 审计日志 / IP 白名单）

---

## 🏗️ 架构一览

```
┌──────────────┐   ┌──────────────┐   ┌──────────────┐
│   Android    │   │   Windows    │   │  Web 分享页   │
│   Compose    │   │    Tauri     │   │  Kotlin/Wasm │
└──────┬───────┘   └──────┬───────┘   └──────┬───────┘
       └──────────────────┼──────────────────┘
                          │  统一经自建后端中转
                 ┌────────▼────────┐
                 │  NestJS 后端     │  账号 / 歌单 / 统计 / 热更新
                 │  + PostgreSQL   │  多音源适配 + 音质降级
                 └────────┬────────┘
                          │  解析出上游直链
                 ┌────────▼────────┐
                 │  腾讯 / 网易 / 酷我 CDN │  ← 音频字节直连，不经服务器
                 └─────────────────┘
```

**核心设计**：客户端**不直接调用第三方音乐接口**，也不把音频流经自建服务器转发。后端只做
「适配 + 解析」——收到点播请求时解析出上游直链返回给客户端，客户端按音源直连对应 CDN 拉流。
这样服务器带宽不成为瓶颈，延迟也更低。

播放链路的三个关键决策：

| 决策 | 原因 |
|---|---|
| 搜索只返回元信息，不解析播放地址 | 早期版本为 60 首歌做 240 次上游请求，客户端拿到地址后又丢掉；改为点播时再解析 |
| 队列里只存**永不过期**的占位地址 `/songs/{id}/play` | 上游直链几小时就失效，直接持久化会让冷启动恢复队列时无法播放 |
| 真正播放时由 `ResolvingDataSource` 换真实直链 | 占位地址在播放那一刻才解析，兼顾持久化与新鲜度 |

---

## 📦 项目结构

| 目录 | 说明 |
|------|------|
| `androidApp/` | Android 应用（Compose 界面、Media3 ExoPlayer、热更新、离线下载、崩溃日志） |
| `desktop/` | Windows 桌面端（Tauri 2 + React + TypeScript） |
| `webApp/` | Kotlin/Wasm 分享播放器（试听片段，复用 `player-ui`） |
| `player-ui/` | Android / Windows / Web 共用的主题与播放界面组件 |
| `shared/` | 跨平台共享层：数据模型、歌词解析（LRC/YRC）、音质档位规则 |
| `server/` | NestJS + TypeScript + PostgreSQL 后端服务 |
| `patch/` | 热修复补丁模块（独立编译的 DEX，不打入 APK） |
| `crypto-src/` | 传输加密层 Rust 源码（core / jni / node / wasm 四 crate） |
| `crypto/` | 加密层四端编译产物目录（由 CI 产出后拉取，不手改） |
| `build-logic/` | Gradle 插件：编译期字节码插桩，为逻辑层注入补丁拦截入口 |

---

## 🧰 技术栈

**客户端**：Kotlin Multiplatform · Jetpack Compose / Material3 · Media3 ExoPlayer ·
Tauri 2 + React 18 + TypeScript · Kotlin/Wasm · OkHttp · Coil · Coroutines

**后端**：NestJS · TypeScript · PostgreSQL · node-postgres · Vue 3 + Element Plus（后台）·
Rust + napi-rs / wasm-bindgen / JNI（加密层）

**工具链**：Gradle (Kotlin DSL) · Vite · GitHub Actions（云端构建）

---

## 🚀 快速开始

### Android 客户端

```powershell
.\gradlew.bat :androidApp:assembleDebug     # 调试包
.\gradlew.bat :androidApp:assembleRelease   # 发布包（交付物）
```

产物：`androidApp/build/outputs/apk/release/`。需要 JDK 21。

> ⚠️ 两种 `assemble` 都会递增 `version.properties`，这是设计行为，**不要回滚版本号**。

### Windows 客户端

```powershell
cd desktop
npm install
npm run tauri dev     # 启动桌面端（端口 5183）
npm run dev           # 纯浏览器看 UI：访问 http://localhost:5183/#mock
```

### 后端

```powershell
psql -U postgres -c "CREATE DATABASE music"   # 建库
cd server && npm install
copy .env.example .env                        # 填 DATABASE_URL 与 AUTH_SECRET
npm run dev                                   # 建表在启动时自动完成
```

改动数据层后必须跑契约验证，详见 [server/README.md](server/README.md)。

---

## 📚 文档导航

| 文档 | 说明 |
|------|------|
| [AGENTS.md](AGENTS.md) | 开发规范：代码风格、模块划分、构建与运行 |
| **[RELEASE.md](RELEASE.md)** | **发布与热更新流程、版本号铁律、不能破的契约（推版本前必读）** |
| [server/README.md](server/README.md) | 后端架构、接口文档、数据层规则、契约验证 |
| [server/wiki/README.md](server/wiki/README.md) | 后端专题文档中心（架构 / 接口 / 数据库 / 排障 / 后台，约 40 篇） |
| [MUSIC_CROSS_PLATFORM.md](MUSIC_CROSS_PLATFORM.md) | Android/Windows 双端功能边界、定时播放、云端歌单契约 |
| [HOT_UPDATE.md](HOT_UPDATE.md) | 热更新设计动机与能力边界 |
| [client-code-index.md](client-code-index.md) | 客户端 CodeGraph 索引、模块边界、关键符号 |
| [VERSIONING.md](VERSIONING.md) | 版本号规则 |
| [SECURITY-AUDIT-FINDINGS.md](SECURITY-AUDIT-FINDINGS.md) / [IM-AUDIT-FINDINGS.md](IM-AUDIT-FINDINGS.md) | 安全审计与 IM 适配审计记录 |

---

## 📜 许可证

本项目采用 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

你可以自由使用、修改和分发本项目，但衍生作品必须以相同许可证开源。详见 [LICENSE](LICENSE)。

## 🙏 致谢

- **QQ 音乐 / 网易云音乐 / 酷我音乐**：音乐资源提供方
- **开源社区**：Kotlin、Jetpack Compose、Media3、NestJS、PostgreSQL、Tauri 等优秀技术栈

> 本项目仅供个人学习与研究使用，音乐版权归各上游平台所有。
