# Web 分享播放器(webApp,Kotlin/Wasm)

[返回文档中心](README.md)

最后更新:2026-09-30

本篇讲 Kotlin/Wasm 分享播放器:定位与入口、技术栈、播放链路与 60 秒试听、构建分发链路、CI 与浏览器侧限制。分享短链的后端契约见 [35-api-shares.md](35-api-shares.md),复用的组件库与模型见 [93-player-ui.md](93-player-ui.md) 与 [94-shared-module.md](94-shared-module.md);与桌面端(Tauri webview)的网络层差异对比见 [91-client-desktop.md](91-client-desktop.md)。

## 1. 定位与入口

- 分享播放器是 `/s/{token}` 短链指向的**免登录单页**:安卓端经 `POST /api/v1/shares/songs` 生成短链(35 篇),任何人在浏览器打开即可试听最多 60 秒并跳转下载完整版。
- 服务端把 `dist/share-player/` 托管在独立前缀 `/share` 下,`GET /s/{token}` 返回改写 `<base>` 后的入口 HTML(`server/src/main.ts`);歌曲身份、封面与试听地址由公开接口 `GET /api/v1/public/shares/{token}` 提供(普通 JSON 信封,35 篇第 2 节)。
- token 在 `Main.kt` 里从 `window.location.pathname` 取最后一段(排除 `s` 本身);取不到时渲染 `demoShareSong()` 演示卡,方便纯前端调试。
- 服务端只是**静态托管**:分享页不落任何会话状态,歌曲身份每次都按短码现查。

## 2. 技术栈与模块结构

- Kotlin/Wasm(`binaries.executable()`)+ Compose Multiplatform(Material3 + material-icons-extended),见 `webApp/build.gradle.kts`;`wasmJs` 是唯一浏览器目标。
- **复用 `:shared` 与 `:player-ui`**(与桌面端相反,桌面端是完全独立的 React 实现):模型 `Song` 来自 shared,页面骨架用 player-ui 的 `PlayerCompactLayout` / `PlayerArtworkSlot` / `SharedContentState` 与 `TaotaoPlayerTheme`。
- 产物是两个**固定名**入口文件:`taotao-share-player.js`(`outputFileName` 配置的 JS 胶水)与 `TaotaoMusic-webApp-wasm-js.wasm`(应用 wasm);JS 必须提供 wasm 要 import 的那批 `js_code` 实现,两者严格同批(第 5 节的坑源于此)。
- `binaryen` 锁 `npm("binaryen", "118.0.0")` 且 `download = false`:与 Kotlin 2.0.21 内置版本一致,直接用 `build/js/node_modules` 里的本地 `wasm-opt`,生产构建不直连 GitHub Release。
- 源码只有三个 Kotlin 文件(`webApp/src/wasmJsMain/kotlin/com/taotao/music/web/`):

| 文件 | 职责 |
| --- | --- |
| `Main.kt` | `ComposeViewport` 入口、token 解析、主题(跟随系统深浅色)、分享页布局与封面加载 |
| `ShareModels.kt` | `parseShareSong`:兼容统一信封与裸对象两种响应,`previewDurationSeconds` 夹到 1–60(缺省 60)、`source` 缺省 `tencent` |
| `WebAudioController.kt` | `<audio>` 封装:加载/播放/暂停/seek/状态回流,守 60 秒试听上限 |

- `resources/index.html` 带 `<base href="/share/">`;`resources/font/noto_sans_sc_regular.otf` 是内置中文字体——Canvas/Wasm 不稳定继承系统字体,所有文本样式经 `Typography.withFontFamily` 全量覆盖,否则 Web 端字号与 Android/Windows 分叉。

## 3. 播放链路与 60 秒试听

- 服务端试听接口(`GET /api/v1/public/shares/{token}/preview`,35 篇第 3 节)只**转发上游完整音频**,不裁剪、不缓存——「最多 60 秒」这条限制**完全由前端守**:`ontimeupdate` 里 `currentTime >= previewDurationSeconds` 就绕回开头重播,`seekTo` 也夹到试听时长内。
- **不能开 `audio.loop`**:浏览器会节流后台标签页的定时回调,一旦漏掉那次到点检查,`loop` 会把整首歌循环播下去而不是停在试听末尾。
- `preload = "metadata"`:只有 60 秒试听,没必要先把整首歌拉下来。
- 状态经 `onStateChanged` 回流成 player-ui 的 `PlayerUiState`;`capabilities` 关掉上一首/下一首与循环(单曲试听没有这些语义)。
- 封面经 `window.fetch` 拉二进制再用 skia 解码;失败(含外部封面地址不带 CORS 允许)静默回退 ♫ 占位——服务端约定 `coverUrl` 是免鉴权绝对 https 地址,但不保证对方开了跨源,回退是正常路径不是 bug。

## 4. 构建链路与产物分发

```powershell
.\gradlew.bat :webApp:wasmJsBrowserDistribution
# 产物:webApp/build/dist/wasmJs/productionExecutable/
```

- 本地后端:`server/tools/build-web-player.mjs`(`npm run build:web-player`)把产物拷进 `server/dist/share-player/`。两种**跳过**路径:
  - `SKIP_WEB_PLAYER=1`:core 单仓里上级目录有 Gradle 工程,后端 CI 的 server job 用它明确跳过,避免后端构建误触发一次 wasm 构建;
  - 上级目录找不到 `settings.gradle.kts` / wrapper(独立仓库部署形态)时自动跳过,产物由部署侧另行提供。
- 云端:core 的 `client-web` job 构建后打包发布到 core 的滚动 Release **`share-player-latest`**;server job 目前**仍从旧 music 仓库(`hdppppppp/music`)的同名 Release 拉真实产物**,拉不到退回三个占位文件(`taotao-share-player.js` / `TaotaoMusic-webApp-wasm-js.wasm` / `index.html`)。core 这边的同名 Release 已就绪,迁移切源完成前,后端 dist 里的分享页可能落后于本仓最新构建(见 [62-ci-cloud-build.md](62-ci-cloud-build.md) 第 6 节)。
- 版本历史:根 `VERSION` + 构建号(如 `1.0.47`)由 `tools/release-log.sh` 记入 Release 说明,仓库内没有独立版本号文件。

## 5. 服务端托管与防混批

分享页的部署事故几乎都源于「固定名入口 + 缓存」,实现与推理都写在源码注释里(`server/src/common/share-player-assets.ts`、`main.ts`),这里只留结论:

- JS 胶水与应用 wasm 是固定名且**必须同批**;缓存导致的「旧 JS + 新 wasm」或「旧 wasm + 新 JS」都会在实例化时抛 `LinkError: ... js_code ... requires a callable`。
- 两层防护:**路径版本化**(`/share/v/<指纹>/…`,指纹 = 两个入口文件内容的 sha256 前 12 位,整目录 `immutable`)加**入口 HTML `no-cache` 每次协商**——`<base>` 决定版本号,缓存住 HTML 等于把用户锁死在旧版本。
- `rewriteShareIndexHtml` 把 `<base>` 指向版本化路径、同时把入口脚本改成相对路径,**两步必须同时成立**;认不出来就整体原样返回,绝不半改。

## 6. CI:client-web job(root `.github/workflows/ci.yml`)

- **固定 ubuntu runner**:wasm 产物与构建平台无关,而 Windows runner 上 npm 刚生成的 `wasm-opt.cmd` shim 偶发被占用导致进程启动失败(Optimize 任务连挂多次),Linux 的 POSIX shim 无此问题。
- **`kotlin-js-store` 锁文件以 Linux 解析为准**:npm 锁文件解析有平台差异,Windows 侧生成的锁在 Linux 会被 npm 改写,`kotlinStorePackageLock` 直接判死。job 先 `-x :kotlinStorePackageLock` 构建一次,锁漂移就把 `build/js/package-lock.json` 回写 `kotlin-js-store/` 并以 `[skip ci]` 提交;KGP 2.0.21 没有 `kotlinUpgradePackageLock` 任务,靠手工对齐两份锁。
- 触发条件是 `client` 开关(`webApp/**`、`shared/**`、`player-ui/**` 等任一命中,见 62 篇第 1 节)。
- 本地(Windows)改依赖后不必手工编辑 `kotlin-js-store`:锁漂移交给 CI 自愈回写即可,手工改反而制造下一轮漂移。

## 7. 浏览器侧限制(与桌面端对比)

- 服务端默认不下发 CORS 头(91 篇第 3 节),但分享页的 API 请求全是**同源相对路径**(`fetch("/api/v1/public/shares/...")`),页面本身也由同一服务托管——不存在跨源,`window.fetch` 直连即可。桌面端 webview 跨源才必须走 `tauri-plugin-http`,这是两端的本质差异,不要把桌面端的做法照搬过来。
- 分享页**不涉及**裸 NDJSON `/search` 协议(那是登录后的搜索接口,32 篇);公开元数据是普通 JSON 信封,`parseShareSong` 做了信封/裸对象双兼容。
- 传输加密对分享页透明:`/api/v1/**` 的加密中间件只处理带 `X-Taotao-Crypto` 头的请求,公开接口无头直通(37 篇)。

## 8. 常见坑

- **混批 `LinkError`**(第 5 节):用户端已 `immutable` 落地的旧缓存靠硬刷新是救不回来的,必须靠路径版本化换 URL 自愈;排查时先看 URL 是否带 `/share/v/<指纹>/`。
- **不要随手给 `WebAudioController` 加 `org.w3c.dom` 回调**:每引用一个新的外部属性,wasm 就多一条 `js_code` 导入,必须由同批 JS 胶水提供——扩大导入面等于扩大混批爆炸面。`onended` 兜底因此被刻意不加(2026-09-21 实踩),60 秒上限由 `ontimeupdate` 与 `seekTo` 夹取已经守得住。
- **60 秒上限只有前端守**:改播放器时别丢 `ontimeupdate` 的绕回与 `seekTo` 夹取,也别开 `loop`;服务端不帮忙裁剪。
- **`<base href="/share/">` 是改写锚点**:`rewriteShareIndexHtml` 靠它匹配,动了这个标签版本化改写会整体失效(原样返回,页面能开但缓存策略退化)。
- **换 Kotlin 版本时核对 binaryen**:内置版本变了要同步改 `npm("binaryen", ...)` 的锁号,否则要么构建直连 GitHub 下载,要么 wasm-opt 行为漂移。
- **后端 dist 里的分享页可能不是最新**:server job 暂从旧 music 仓库拉产物(第 4 节),验证分享页行为时先确认部署实例里 `dist/share-player` 的实际内容,别默认它等于本仓 HEAD。
