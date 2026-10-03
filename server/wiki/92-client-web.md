# Web 分享播放器(webApp,Kotlin/Wasm)

[返回文档中心](README.md)

最后更新:2026-10-03

本篇讲 Kotlin/Wasm 分享播放器:定位与入口、技术栈、播放链路与 60 秒试听、与后端的契约、构建分发链路、CI 与浏览器侧限制。分享短链的后端契约见 [35-api-shares.md](35-api-shares.md),复用的组件库与模型见 [93-player-ui.md](93-player-ui.md) 与 [94-shared-module.md](94-shared-module.md);与桌面端(Tauri webview)的网络层差异对比见 [91-client-desktop.md](91-client-desktop.md),云端构建与版本号见 [62-ci-cloud-build.md](62-ci-cloud-build.md)。分享创建侧的客户端 UI 不在本篇:安卓在 `ui/common/Share.kt`(90 篇),桌面在 `api.createSongShare`(91 篇)。

## 1. 定位与入口

- 分享播放器是 `/s/{token}` 短链指向的**免登录单页**:安卓/桌面端经 `POST /api/v1/shares/songs` 生成短链(35 篇),任何人在浏览器打开即可试听最多 60 秒并跳转下载完整版。
- 服务端把 `dist/share-player/` 托管在独立前缀 `/share` 下,`GET /s/{token}` 返回改写 `<base>` 后的入口 HTML(`server/src/main.ts`,响应 `no-cache` 每次协商);歌曲身份、封面与试听地址由公开接口 `GET /api/v1/public/shares/{token}` 提供(普通 JSON 信封,35 篇第 2 节)。
- 页面结构(`Main.kt`):品牌标题 → 圆形封面 → player-ui 的 `PlayerCompactLayout`(进度条 + 播放/暂停)→「下载桃桃音乐,完整播放」按钮 →「打开桃桃音乐,接续完整播放」按钮(仅安卓 UA 且歌曲有身份时出现)→「标准音质 · 最多 60 秒试听」说明;整页经 `BoxWithConstraints` 居中。
- 「下载完整版」按钮跳 `appDownloadUrl`——服务端按与安卓整包更新同一套规则拼的**最新 100% 放量** Android 版本下载地址(登记了 GitHub 外链返回拼 `DOWNLOAD_PROXY_PREFIX` 的代理外链,未登记才回落本机 `/api/v1/app/apk/{version}`,见 `song-share.service.ts` → `ReleaseService.shareAppDownloadUrl`);token 取不到时的演示卡缺省 `/download`。**历史包袱提醒:发版链路改为 GitHub Release 外链后服务器不落盘安装包字节,本机 `/app/apk` 端点对现行版本必然 404「文件缺失」——2026-10 之前分享页按钮失修的根因。**
- 「打开桃桃音乐」按钮把当前歌拼成 `taotaomusic://open?...` 深链接跳转,由安卓 App(Manifest 声明 + `data/OpenSongLink.kt` 解析)接续完整播放;**参数键与安卓解析器一一对应,两端必须同步改**。仅在 `navigator.userAgent` 含 `Android` 时渲染(桌面/iOS 没有 scheme 接收方,自定义 scheme 会弹「未知协议」错误);歌曲既无数字 ID 也无 mid 时同样隐藏(演示卡走这条路)。参数值逐个 `encodeURIComponent`(经 `@JsFun` 外联)。
- token 在 `Main.kt` 里从 `window.location.pathname` 取最后一段(排除 `s` 本身);取不到时渲染 `demoShareSong()` 演示卡,方便纯前端调试(演示卡 `previewUrl` 为空串,不发声是正常路径)。
- 服务端只是**静态托管**:分享页不落任何会话状态,歌曲身份每次都按短码现查,元数据里的试听/下载地址都是响应时现拼的。
- 前缀分工:`/s/{token}` 只出协商的入口 HTML,静态资源全在 `/share` 前缀(版本化目录)下;两个前缀别混用。

## 2. 技术栈与模块结构

- Kotlin/Wasm(`binaries.executable()`)+ Compose Multiplatform(Material3 + material-icons-extended),见 `webApp/build.gradle.kts`;`wasmJs` 是唯一浏览器目标。
- 版本定义在根 `build.gradle.kts`:Kotlin 2.0.21 + Compose 1.7.1。**升级 Kotlin 时 binaryen 内置版本会跟着变**,见第 10 节的坑。
- **复用 `:shared` 与 `:player-ui`**(与桌面端相反,桌面端是完全独立的 React 实现):模型 `Song` 来自 shared,页面骨架用 player-ui 的 `PlayerCompactLayout` / `PlayerArtworkSlot` / `SharedContentState` 与 `TaotaoPlayerTheme`。
- wasmJsMain 其余依赖:compose runtime/foundation、`kotlinx-coroutines-core` 1.9.0 与 `kotlinx-serialization-json` 1.7.3。
- 产物是两个**固定名**入口文件:`taotao-share-player.js`(`outputFileName` 配置的 JS 胶水)与 `TaotaoMusic-webApp-wasm-js.wasm`(应用 wasm);JS 必须提供 wasm 要 import 的那批 `js_code` 实现,两者严格同批(第 6 节的坑源于此)。skiko、字体等相对引用靠 `<base>` 跟着版本化路径走。
- `binaryen` 锁 `npm("binaryen", "118.0.0")` 且 `download = false`:`BinaryenRootExtension` 把 wasm-opt `command` 指到 `build/js/node_modules/.bin` 里的本地副本(Windows 取 `wasm-opt.cmd`、其余取 `wasm-opt`,路径在 gradle 脚本里按 `os.name` 选),与 Kotlin 2.0.21 内置版本一致,生产构建不直连 GitHub Release。
- 内置中文字体的第三方声明在 `webApp/THIRD_PARTY_NOTICES.md`:Noto Sans SC,依 SIL OFL 1.1 使用。
- webApp 自身没有测试源集;与分享页相关的可测点在服务端纯函数(版本化改写,第 6 节)。
- 源码只有三个 Kotlin 文件(`webApp/src/wasmJsMain/kotlin/com/taotao/music/web/`):

| 文件 | 职责 |
| --- | --- |
| `Main.kt` | `ComposeViewport` 入口、token 解析、主题(跟随系统深浅色)、分享页布局与封面加载 |
| `ShareModels.kt` | `parseShareSong`:兼容统一信封与裸对象两种响应,`previewDurationSeconds` 夹到 1–60(缺省 60)、`source` 缺省 `tencent` |
| `WebAudioController.kt` | `<audio>` 封装:加载/播放/暂停/seek/状态回流,守 60 秒试听上限 |

- `resources/index.html`(wasmJsMain)带 `<base href="/share/">`;内置中文字体是 compose 资源 `webApp/src/commonMain/composeResources/font/noto_sans_sc_regular.otf`(源码里经 `Res.font.noto_sans_sc_regular` 引用)——Canvas/Wasm 不稳定继承系统字体,所有文本样式经 `Typography.withFontFamily` 全量覆盖,否则 Web 端字号与 Android/Windows 分叉。

## 3. 播放链路与 60 秒试听

- 服务端试听接口(`GET /api/v1/public/shares/{token}/preview`,35 篇第 3 节)只**转发上游完整音频**,不裁剪、不缓存。
- 所以「最多 60 秒」这条限制**完全由前端守**:`ontimeupdate` 里 `currentTime >= previewDurationSeconds` 就绕回开头重播(置 0 再 play,不重新 load),`seekTo` 也夹到试听时长内。
- 试听上限值来自元数据的 `previewDurationSeconds`(服务端按 `min(60, 歌曲时长)` 取小,前端再夹 1–60);要改上限得 35 篇的服务端常量与本篇夹取两端同改。
- **不能开 `audio.loop`**:浏览器会节流后台标签页的定时回调,一旦漏掉那次到点检查,`loop` 会把整首歌循环播下去而不是停在试听末尾。
- `preload = "metadata"`:只有 60 秒试听,没必要先把整首歌拉下来。
- 状态经 `onStateChanged` 回流成 player-ui 的 `PlayerUiState`;`WebAudioState` 只有 `isPlaying` / `isBuffering` / `positionMs` / `durationMs` / `errorMessage` 五个字段,`durationMs` 直接用试听时长;`capabilities` 关掉上一首/下一首与循环(单曲试听没有这些语义)。
- 交互接线:`PlayerCompactLayout` 的进度条吃 `positionMs` / `durationMs`,`onSeek` 进 `seekTo`(毫秒,夹取上限);播放/暂停走 `controller::togglePlaying`;`onToggleRepeat` 传空操作。
- 生命周期:`WebAudioController` 由 `remember(share.previewUrl)` 按试听地址重建,`DisposableEffect` 的 `onDispose` 调 `release()` 清源——别在别处手动 new 或复用旧实例。
- 加载/失败态用 player-ui 的 `SharedContentState`(LOADING / ERROR);封面按窄屏断点取两档(常量都在 `Main.kt` 顶部):`CompactWidthBreakpoint` 520dp 以下取 `ArtworkSizeCompact` 248dp,否则 `ArtworkSizeWide` 300dp;正文最大 `ShareContentMaxWidth` 480dp。
- 封面经 `window.fetch` 拉二进制再用 skia `makeFromEncoded` 整张解码。
- 解码失败(含封面地址不带 CORS 允许)静默回退 ♫ 占位——响应里的 `coverUrl` 是创建分享时快照下来的地址,不保证提供方开了跨源,回退是正常路径不是 bug。

## 4. 与后端的契约要点

| 操作 | 端点 | 形态与要点 |
| --- | --- | --- |
| 创建分享(需登录) | `POST /api/v1/shares/songs` | 201 返回 `{token,url}`;`source` 走 `isMusicSource` 白名单,不认识的音源 400/4001;`mid` 最长 128(35 篇) |
| 读元数据(公开) | `GET /api/v1/public/shares/{token}` | 普通 `{code,data}` 信封;`@RateLimit("app")`;读一次顺手 `noteAccess` 记访问 |
| 听试听(公开) | `GET /api/v1/public/shares/{token}/preview` | `@RawResponse()` **裸音频流**,转发上游完整音频,不裁剪不缓存;`previewUrl` 由服务端按请求的 public base 现拼,是同源绝对地址 |
| 下载完整版 | `appDownloadUrl`(服务端现拼) | 与安卓整包更新同一套:GitHub 外链 + `DOWNLOAD_PROXY_PREFIX` 代理优先,未登记外链才回落 `GET /api/v1/app/apk/{version}` |

- 元数据 `data` 字段:`title`、`artist`、`album`、`coverUrl`、`duration`(已格式化 `mm:ss`)、`songId`、`mid`、`type`、`source`、`vip`、`previewDurationSeconds`、`previewUrl`、`appDownloadUrl`。
- 客户端解析只有 `parseShareSong` 一个入口,信封/裸对象双兼容,**新增字段不要绕过它**;`requiredString` 对 `title` / `artist` 缺失直接抛错,由页面转成 ERROR 态。
- 公开接口统一 `@Public()` + `@RateLimit("app")`(`server/src/shares/song-share.controller.ts`),与登录侧共用同一限流分组;客户端解析只有 `parseShareSong` 一个入口,信封/裸对象双兼容,**新增字段不要绕过它**。
- 404 语义:token 不存在或已失效时元数据接口报错,页面把 `check(response.ok)` 的失败转成「分享链接不存在或已失效」的 ERROR 态——分享页没有重试 UI,重新打开短链就是重试。

## 5. 构建链路与产物分发

```powershell
.\gradlew.bat :webApp:wasmJsBrowserDistribution
# 产物:webApp/build/dist/wasmJs/productionExecutable/
```

- 产物目录是整页所需的一切:`index.html` + JS 胶水 + 应用 wasm + skiko + 内置字体;分发时整目录拷贝或打包,不做单文件挑拣。
- 本地后端:`server/tools/build-web-player.mjs`(`npm run build:web-player`,也是 `npm run build` 的最后一步)把产物拷进 `server/dist/share-player/`。两种**跳过**路径:
  - `SKIP_WEB_PLAYER=1`:core 单仓里上级目录有 Gradle 工程,后端 CI 的 server job 用它明确跳过,避免后端构建误触发一次 wasm 构建;
  - 上级目录找不到 `settings.gradle.kts` / wrapper(独立仓库部署形态)时自动跳过,产物由部署侧另行提供。
- 云端构建:core 的 `client-web` job 构建后把 `productionExecutable` 整目录打包 `share-player.zip`,发布到 core 的滚动 Release **`share-player-latest`**(prerelease,`--clobber` 覆盖)。
- 云端拉取:server job 目前**仍从旧 music 仓库(`hdppppppp/music`)的同名 Release 拉真实产物**,拉不到退回三个占位文件(`taotao-share-player.js` / `TaotaoMusic-webApp-wasm-js.wasm` / `index.html`)。core 这边的同名 Release 已就绪(已核实存在),迁移切源完成前,后端 dist 里的分享页可能落后于本仓最新构建(见 [62-ci-cloud-build.md](62-ci-cloud-build.md) 第 6 节)。
- 版本历史:根 `VERSION` + 构建号(如 `1.0.59`)由 `tools/release-log.sh` 记入 Release 说明,仓库内没有独立版本号文件。
- 产物自检清单(排查分享页问题先过一遍):`server/dist/share-player/` 里三个入口文件齐全;`index.html` 仍带 `<base href="/share/">`;两个固定名入口与版本指纹一致(改过指纹目录 `/share/v/<新指纹>/` 才说明真的更新了)。

## 6. 服务端托管与防混批

分享页的部署事故几乎都源于「固定名入口 + 缓存」,实现与推理都写在源码注释里(`server/src/common/share-player-assets.ts`、`main.ts`),这里只留结论:

- JS 胶水与应用 wasm 是固定名且**必须同批**;缓存导致的「旧 JS + 新 wasm」或「旧 wasm + 新 JS」都会在实例化时抛 `LinkError: ... js_code ... requires a callable`。
- 两层防护:**路径版本化**(`/share/v/<指纹>/…`,指纹 = 两个入口文件内容的 sha256 前 12 位,整目录 `immutable`)加**入口 HTML `no-cache` 每次协商**——`<base>` 决定版本号,缓存住 HTML 等于把用户锁死在旧版本;版本化目录下的 skiko、字体等所有相对引用靠 `<base>` 一起吃到版本号。
- `rewriteShareIndexHtml` 把 `<base>` 指向版本化路径、同时把入口脚本改成相对路径,**两步必须同时成立**:只换 `<base>` 不改脚本,绝对路径 `/share/...` 拿不到版本号;只改脚本不换 `<base>`,页面挂在 `/s/<短码>` 下时相对脚本会被解析成 `/s/taotao-share-player.js` → 404。认不出来就整体原样返回,绝不半改。
- 指纹只对两个入口文件的内容算:产物没变的重新构建不会换指纹,用户不用白重下。
- **为什么不用文件名 hash**:常规前端靠 webpack 的内容 hash 文件名失效缓存,而 Kotlin/Wasm 构建的两个入口天生是**固定名**(`outputFileName` 也不吃 hash),只能外置一层路径版本化。
- 纯函数(`sharePlayerVersion` / `rewriteShareIndexHtml`)单独抽出就是为了可离线断言:`server/tools/verify-static-cache.ts` 用原生 http 覆盖条件请求行为,契约验证脚本里也有关联提示。

## 7. CI:client-web job(root `.github/workflows/ci.yml`)

| 步骤 | 内容 |
| --- | --- |
| 刷新 kotlin-js-store 锁 | `-x :kotlinStorePackageLock` 构建一次(3 次自动重试);锁漂移就把 `build/js/package-lock.json` 回写 `kotlin-js-store/` 并以 `[skip ci]` 提交(推送失败只告警,下次重试) |
| 构建分享播放器 | `:webApp:wasmJsBrowserDistribution`(带 `--build-cache`,再 3 次自动重试) |
| 打包发布 | zip 产物上传 core 滚动 Release `share-player-latest`(`--clobber`,prerelease) |
| 版本历史 | `tools/release-log.sh` 记 `VERSION.构建号` |
| 工具链 | Java 21(temurin)+ Node 22,`contents: write` 权限 |

CI 里两处 gradle 调用统一带 `--build-cache --no-daemon`,与本地 `build-web-player.mjs` 的参数风格一致。

- **固定 ubuntu runner**:wasm 产物与构建平台无关,迁离 Windows 是因为其上 npm 刚生成的 `wasm-opt.cmd` shim 偶发被占用导致进程启动失败;但 Linux 上同一任务也偶发「problem occurred starting process」,所以两处 gradle 调用都带自动重试,重跑即愈。
- **`kotlin-js-store` 锁文件以 Linux 解析为准**:npm 锁文件解析有平台差异,Windows 侧生成的锁在 Linux 会被 npm 改写,`kotlinStorePackageLock` 直接判死;KGP 2.0.21 没有 `kotlinUpgradePackageLock` 任务,靠手工对齐两份锁(`org.gradle.configureondemand=true` 下直接调 `:kotlinNpmInstall` 会因 root 未注册任务而失败,所以要借 `:webApp` 的构建图并临时排除锁校验)。
- 触发条件是 `client` 开关(`webApp/**`、`shared/**`、`player-ui/**` 等任一命中,见 62 篇第 1 节);工具链 Java 21(temurin)+ Node 22,job 带 `contents: write` 权限(发 Release 与回写锁)。
- job 顺序与其它 job 一致:`needs: [changes, purge]`,先过 paths-filter 分流再执行。
- 本地(Windows)改依赖后不必手工编辑 `kotlin-js-store`:锁漂移交给 CI 自愈回写即可,手工改反而制造下一轮漂移。

## 8. 浏览器侧限制(与桌面端对比)

- 服务端默认不下发 CORS 头(91 篇第 4 节),但分享页的 API 请求全是**同源相对路径**(`fetch("/api/v1/public/shares/...")`),页面本身也由同一服务托管——不存在跨源,`window.fetch` 直连即可。
- 桌面端 webview 跨源才必须走 `tauri-plugin-http`,这是两端的本质差异,不要把桌面端的做法照搬过来(91 篇第 4 节)。
- 试听地址 `previewUrl` 虽是绝对地址但指向同一服务(`<audio>` 加载媒体不受 CORS 约束);真正受跨源影响的只有封面一类外部资源,失败走占位回退(第 3 节)。
- 分享页跑在普通浏览器里,没有 Tauri 能力层、没有 Service Worker——缓存行为完全由响应头分档(版本化资源 `immutable`、入口 HTML `no-cache`)。
- 首次加载大头是 wasm(MB 级,实测约 8 MB),JS 胶水仅数百 KB;版本指纹不变就不重下,所以「改一行文案也全量换指纹」的代价要心里有数。
- 分享页**不涉及**裸 NDJSON `/search` 协议(那是登录后的搜索接口,32 篇);公开元数据是普通 JSON 信封,`parseShareSong` 做了信封/裸对象双兼容。
- 传输加密对分享页透明:`/api/v1/**` 的加密中间件只处理带 `X-Taotao-Crypto` 头的请求,公开接口无头直通(37 篇)。

## 9. 问题定位速查

分享页的线上症状集中在加载与缓存两类,先对号入座再翻后面的坑:

| 症状 | 大概率原因 | 处置 |
| --- | --- | --- |
| `LinkError: ... js_code ... requires a callable` | 混批缓存(旧 JS + 新 wasm 或反之) | 看地址是否带 `/share/v/<指纹>/`;版本化 URL 自愈,硬刷新救不了 |
| 页面一直「正在加载歌曲…」 | 元数据请求失败(短码失效 / 后端不可达) | 看 Network 里 `/api/v1/public/shares/{token}` 的响应 |
| 「分享链接不存在或已失效」 | 短码无效或分享记录不存在 | 无重试 UI,重开短链即重试 |
| 试听到点回到开头 | 前端守限生效(`previewDurationSeconds`) | 正常行为;改上限要两端同改 |
| 封面显示 ♫ 占位 | 外部封面无 CORS 允许或地址失效 | 正常回退路径,不是 bug |
| 页面字号/字体观感异常 | 字体没随版本化路径加载或 Typography 被绕过 | 查 `/share/v/<指纹>/` 下字体资源与 `<base>` |
| 部署后分享页还是旧版 | 后端 dist 里就不是最新(暂从旧仓库拉) | 核对部署实例 `dist/share-player` 实际内容(第 5 节) |

## 10. 常见坑

- **混批 `LinkError`**(第 6 节):用户端已 `immutable` 落地的旧缓存靠硬刷新是救不回来的,必须靠路径版本化换 URL 自愈;排查时先看 URL 是否带 `/share/v/<指纹>/`。
- **不要随手给 `WebAudioController` 加 `org.w3c.dom` 回调**:每引用一个新的外部属性,wasm 就多一条 `js_code` 导入,必须由同批 JS 胶水提供——扩大导入面等于扩大混批爆炸面。`onended` 兜底因此被刻意不加(2026-09-21 实踩),60 秒上限由 `ontimeupdate` 与 `seekTo` 夹取已经守得住。
- **60 秒上限只有前端守**:改播放器时别丢 `ontimeupdate` 的绕回与 `seekTo` 夹取,也别开 `loop`;服务端不帮忙裁剪。
- **`<base href="/share/">` 是改写锚点**:`rewriteShareIndexHtml` 靠它匹配,动了这个标签(或改入口 `<script src="/share/...">` 的写法)版本化改写会整体失效(原样返回,页面能开但缓存策略退化)。
- **改产物入口文件名要先过 `share-player-assets.ts`**:指纹计算与改写都认 `taotao-share-player.js` / `TaotaoMusic-webApp-wasm-js.wasm` 两个名字(`ENTRY_FILES`),改名要同步常量,否则指纹算不出来直接 500。
- **`duration` 缺省 "01:00"**:上游字段缺失时 `parseShareSong` 用它占位,别把展示值当真实时长做逻辑。
- **`source` 缺省 `tencent` 是两端约定**:`parseShareSong` 的缺省值与服务端创建侧的白名单缺省要对齐,单边改会让老数据展示错音源。
- **别给分享页塞 Authorization**:它只调公开接口,带令牌没有意义;以后若接入需登录的能力,另起页面而不是复用短链宿主。
- **字体覆盖是全量 or 无**:新增文本样式若绕过 `TaotaoTypography` 直接写 `fontSize`,会拿不到内置字体家族,Web 端字号与系统字体观感漂移。
- **元数据里的地址是现拼的**:`previewUrl` / `appDownloadUrl` 按请求的 public base 生成,别在客户端持久化或改写它们——短链是长期的,地址随部署环境走;分享页每次打开都重新拉元数据。
- **换 Kotlin 版本时核对 binaryen**:内置版本变了要同步改 `npm("binaryen", ...)` 的锁号,否则要么构建直连 GitHub 下载,要么 wasm-opt 行为漂移。
- **改 `outputFileName` 是三处联动**:gradle 脚本的 `outputFileName`、服务端 `ENTRY_FILES` 指纹常量、以及排障文档里的一切引用——单改一处,指纹算不出来或改写认不出。
- **后端 dist 里的分享页可能不是最新**:server job 暂从旧 music 仓库拉产物(第 5 节),验证分享页行为时先确认部署实例里 `dist/share-player` 的实际内容,别默认它等于本仓 HEAD。
- **纯前端调试用 `demoShareSong` 演示卡**:无 token 时它兜底渲染,但 `previewUrl` 为空串,验证不了播放链路——验证试听必须用真短链。
