# Windows 桌面端(desktop,Tauri 2 + React)

[返回文档中心](README.md)

最后更新:2026-10-01

本篇讲 Windows 桌面客户端:技术栈与目录、状态与数据流、网络层铁律(CORS 与能力白名单)、传输加密现状、自动更新、构建调试与功能清单。桌面端 UI 是**独立的 React 实现,不复用 Kotlin 的 player-ui / shared**(这两个 Kotlin 模块见 [93-player-ui.md](93-player-ui.md) 与 [94-shared-module.md](94-shared-module.md));安卓端整体见 [90-client-android.md](90-client-android.md),加密协议见 [37-api-crypto.md](37-api-crypto.md),云端构建见 [62-ci-cloud-build.md](62-ci-cloud-build.md)。

## 1. 技术栈与模块定位

- 前端:React 18 + TypeScript + Vite 5(`desktop/package.json`);构建脚本 `npm run build` = `tsc && vite build`,先过 TypeScript 类型检查;构建目标 `chrome110`,dev 端口固定 5183,产物进 `desktop/dist/`(`vite.config.ts`)。Rust 侧 edition 2021(`rust-version 1.82`),`publish = false`。
- 壳:Tauri 2(`desktop/src-tauri/`),主窗口 1100×720,`identifier com.taotao.music.desktop`;业务插件只有两个——`tauri-plugin-http`(前端 fetch 由 Rust 直连)与 `tauri-plugin-updater`(自动更新),都在 `src-tauri/src/lib.rs` 的 `run()` 注册。
- Rust 侧另依赖**同仓加密核心** `taotao-crypto-core`(path 依赖 `../../crypto-src/core`,直接编译进二进制——**不走 JNI,也不加载 .node/.dll 产物**)、`winreg`(读设备号)与 `ureq`(握手用轻量 HTTP)。
- 后端客户端唯一入口是 `desktop/src/api.ts`(约 600 行):`ENDPOINT` 常量、信封解包、401 刷新并重放一次、NDJSON 搜索、收藏/歌单/最近播放/播放上报全封装在此;页面不直接拼请求。
- UI 分层:左侧导航四个页面(搜索/歌单/最近播放/我的收藏)+ 底部播放条 `PlayerBar` + 全屏详情 `PlayerDetail`;样式按页拆 CSS(`App.css`、`main/search.css` / `player.css` / `playlists.css` / `recent.css` / `lyrics.css`);播放控制图标统一用内置 SVG 集 `main/icons.tsx`。
- 判断成功的唯一依据是响应信封 `code === 0`(`unwrap` 对 `!resp.ok` 或非 0 码统一抛错,文案取 `message` 或 HTTP 状态);401/403 语义不做任何「宽容」处理,与安卓口径一致。新增调用方不要绕过 `unwrap` 自己解析。

## 2. 目录结构

| 位置 | 职责 |
| --- | --- |
| `desktop/index.html` | Vite 入口,内含 `#mock` 假数据旁路(见第 7 节) |
| `desktop/tsconfig.json` | TypeScript 编译配置(`npm run build` 里 `tsc` 门禁的依据) |
| `desktop/src/api.ts` | 唯一后端客户端 + `ENDPOINT` + `absoluteUrl()` 封面归一 + `readableError()` 文案归一 |
| `desktop/src/App.tsx` | 根组件:恢复登录 → 未登录进 `auth/Auth`,已登录进 `AppProvider + MainScreen` |
| `desktop/src/auth/Auth.tsx` | 登录/注册(邮箱验证码),就地校验;会话落 `localStorage`(键 `taotao.session`) |
| `desktop/src/state/AppState.tsx` | 全局状态 Provider:播放队列、音质、定时关闭、收藏、歌单、播放上报 outbox |
| `desktop/src/main/` | 页面与组件,见第 8 节功能清单;`searchHistory.ts` 单独管搜索历史存取 |
| `desktop/src/main/img.ts` | `hideOnError`:封面加载失败隐藏 `<img>`,避免碎图标 |
| `desktop/src-tauri/src/lib.rs` | Rust 侧:插件注册、启动静默检查更新、`crypto_handshake_demo` 握手自检命令 |
| `desktop/src-tauri/src/main.rs` | 入口:Release 下隐藏控制台窗口(`windows_subsystem = "windows"`) |
| `desktop/src-tauri/build.rs` | `tauri-build` 常规构建脚本 |
| `desktop/src-tauri/tauri.conf.json` | 窗口/打包(`msi` + `nsis`)/updater 配置:更新端点清单(第 6 节)与签名**公钥**内嵌于此 |
| `desktop/src-tauri/Cargo.toml` | Rust 依赖清单:`taotao-crypto-core` path 依赖、`winreg`、`ureq`;Release profile 压体积(opt-level "s" + thin LTO) |
| `desktop/src-tauri/capabilities/default.json` | `http:default` 域名白名单(见第 4 节) |
| `desktop/updater.key` / `updater.key.pub` | 更新签名密钥对,已被 `desktop/.gitignore` 排除,**绝不入库** |

## 3. 状态与数据流

- 单向数据流:页面组件只调 `useApp()` 暴露的操作(`playList` / `playAt` / `togglePlay` / `moveQueueItem` 等),队列的权威副本在 `AppState`;底部 `PlayerBar` 与全屏 `PlayerDetail` 都从同一状态渲染。
- 播放状态的事实源是**音频元素事件**:`AppState` 渲染唯一的 `<audio>`(hidden),`isPlaying`、进度、时长全部由 `play` / `pause` / `timeupdate` / `loadedmetadata` 事件写回,UI 不自行推算;播放出错只写 `playError` 并 toast,轻提示 2600ms 自动消失。音频元素自身加载失败(直链失效)只在确有音源时写「音频加载失败,请尝试重新播放」,避免空元素噪声。
- 闭包防旧值:队列、下标、音质、播放态、定时器标记都有**镜像 ref**(`queueRef` / `indexRef` / `preferredQualityRef` / `isPlayingRef` 等),`ended` 回调与 interval 里一律读 ref,不读 state。
- 快速切歌用**播放请求代数**(`playGenRef`):每次 `playAt` / `reresolveCurrent` 先自增,`resolveLink` 返回后代数不符直接丢弃——并发请求只有最后一次生效,不会旧直链盖掉新直链。
- 播放会话生命周期(与安卓 `shouldReuseSession` 对齐):暂停/恢复、seek、音质换源**复用**同一会话;换歌、自然播完(含单曲循环重播)、对当前曲再点播放(显式重播意图)**终结并另起新会话**;无远端身份(`songId` 为空)的会话整体跳过不上报。
- outbox 语义:同一 `sessionId` 只保留最新快照(服务端按 sessionId 幂等 upsert),容量上限 200 条、超出丢最旧;上传成功按**内容比对**删除,上传期间同会话入队的新快照因内容必然变化而保留,不会误删。
- 收藏乐观更新在操作前整体快照(`prevKeys` / `prevSongs`),请求失败回滚到快照再提示;新建歌单的记录插入本地列表头部,与服务端「按更新时间降序」的展示口径一致。
- 循环模式 off / all / one:`next` / `prev` 的回绕只认 all(到尾/在 0 时其余模式停住);one 的重播由 `ended` 回调处理,并单独开新上报会话。
- 全屏详情 `PlayerDetail` 的弹窗层(分享/音质/定时关闭)按 **音质 > 定时 > 分享** 的层级用 ESC 逐层关闭,详情页自身的 ESC 收起与「⌄」按钮走同一接管路径;详情收起时先播 160ms 淡出再卸载。
- 页面切换在 `MainScreen`:四个页面**常驻挂载**、CSS 显隐切换(保留各自的搜索结果与滚动位置),进出场动画 200ms 进 / 150ms 退(`App.css` 的 `page-enter` / `page-exit`,与 `PAGE_EXIT_MS = 150` 对齐)。
- 挂载后各拉一次收藏库与歌单列表,失败轻提示并以 `favReady` / `playlistsReady` 标记完成——页面据此区分「空列表」和「还没加载」。
- 本地持久化键(全部 `localStorage`,不上传):

| 键 | 内容 |
| --- | --- |
| `taotao.session` | 令牌包(accessToken / refreshToken / expiresAt) |
| `taotao.deviceId` | 播放上报设备号(`crypto.randomUUID` 生成一次) |
| `taotao.playbackOutbox` | 播放上报 outbox,先落盘、服务端确认后删除 |
| `taotao.quality` | 偏好音质档(仅接受 0–18 整数,缺省 4) |
| `taotao.sleepWaitSongEnd` | 「播完整首歌再停止播放」标记("1"/"0") |
| `taotao.searchHistory` / `taotao.searchHistoryPaused` | 搜索历史(上限 20 条、大小写不敏感去重)与暂停记录标记 |

## 4. 网络层铁律:CORS 与能力白名单

webview 里的 `window.fetch` 受 CORS 约束,而服务端按安全设计**默认不下发 CORS 头**——浏览器侧跨源请求必被预检拦掉。所以打包后的业务请求必须走 `tauri-plugin-http` 由 Rust 侧直连:

- `api.ts` 的 `httpFetch`:检测到 `"__TAURI_INTERNALS__" in window` 就用 `@tauri-apps/plugin-http` 的 fetch;纯浏览器 dev(没有 Tauri 注入)才退回 `window.fetch`。该插件由 Rust 侧发请求,不经过浏览器同源策略,所以服务端不下发 CORS 头也完全无妨——这正是选它的原因。
- 所有业务请求都是 `${ENDPOINT}${path}` 的**绝对地址**拼接——分享播放器那种同源相对路径模式在桌面端不存在(对比见 92 篇第 8 节)。
- **换后端域名要同步改两处**:`src/api.ts` 的 `ENDPOINT` 与 `src-tauri/capabilities/default.json` 里 `http:default` 的 `allow` 白名单(插件按 URL 前缀放行)。两边不一致**会静默失败**:请求表现为普通网络错误,不是权限报错。当前白名单是后端域名 `https://music.xydaigua.cn` 加 `http://localhost:*` / `http://127.0.0.1:*`(为本机联调后端 dev 端口预留)。
- 令牌语义与安卓一致:401 自动用 refreshToken 续期并**重放一次**,仍 401 抛 `SessionExpired` 登出;登录/注册/验证码走不带令牌的 `postJson`(`auth/login` / `auth/register` / `auth/refresh` / `auth/email-verification` 一族)。
- `unwrap` 的信封解包对 `data` 缺失宽容(`json?.data ?? json`),与 Rust 侧 `envelope_data` 同一口径;`restoreToken` 启动恢复登录,令牌未过期直接用,过期先刷新。

## 5. 传输加密:Rust core 直连 + PSK 动态下发

- 加密核心经 Cargo path 依赖编译进桌面二进制(`src-tauri/Cargo.toml`),不依赖 `crypto/dist/` 产物;因此 **`crypto-src/**` 变动会连带触发 CI 的 desktop job 重建**。
- 设备号:Windows 读注册表 `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid`(`winreg`,带 `KEY_WOW64_64KEY`),折进握手密钥做设备绑定——与安卓 `ANDROID_ID`、后端协议 v2 的模型一致(见 [37-api-crypto.md](37-api-crypto.md));读不到退占位串(`unknown-windows-device` / 非 Windows `unknown-desktop-device`,等价不绑定)。
- PSK 同样**由后端动态下发**:Rust 侧 `fetch_psk` 用 `ureq` 带 `Authorization: Bearer <token>` 调 `GET /api/v1/crypto/psk`,再 `ClientEngine::new(pskId, pskHex, deviceId)` 做 `POST /api/v1/crypto/handshake`。客户端不内嵌任何密钥。
- 加密链路对桌面端的影响面目前**只有握手自检**:PSK 拿不到(503/5031)或握手失败只让 `crypto_handshake_demo` 报错,业务明文请求不受影响(与 37 篇「可选增强」的口径一致)。
- 现状:`crypto_handshake_demo` 是注册好的 Tauri 命令(握手自检,返回会话 ID 十六进制),由前端 `invoke` 传入 `endpoint` 与登录令牌;**React 业务请求尚未接入逐块加密**——目前全部明文走 `tauri-plugin-http`,服务端对无 `X-Taotao-Crypto` 头的请求完全透明(37 篇第 3 节)。Rust 侧握手用 `ureq` 同步阻塞(包在 Tauri 命令里),`envelope_data` 兼容信封与裸对象。后续接入时注意 AAD 绑定 `method + path + query`,改路由形状会直接解密失败。

## 6. 自动更新(tauri-plugin-updater)

- 更新清单按 `tauri.conf.json` 的 `plugins.updater.endpoints` **顺序尝试**,当前两个端点:
  1. **后端代理** `https://music.xydaigua.cn/api/v1/desktop/updater/latest.json`(`server/src/release/desktop-updater.controller.ts`):`@Public()` + `@RawResponse()` 返回**裸 JSON**、不套信封(Tauri 按原生清单格式解析)。转发 GitHub 的 `latest.json`,并把各平台的安装包直链改写成下载代理前缀(`DOWNLOAD_PROXY_PREFIX`,未配置则原样)提速;`version`/`signature` 原样保留——minisign 签名是对安装包字节算的,改 url 不破坏校验。清单缓存 5 分钟,GitHub webhook 通知 `desktop-latest` 发布时主动失效(`desktop-updater.service.ts`)。
  2. GitHub Release `https://github.com/taotaomusic/core/releases/download/desktop-latest/latest.json` 兜底。
- minisign **公钥**内嵌在 `tauri.conf.json` 的 `plugins.updater.pubkey`,用于校验更新包签名。
- 启动即静默检查(`lib.rs` 的 `setup` 里 spawn):有新版本就 `download_and_install` 并 `handle.restart()`,失败只打日志、不打断使用。
- 签名**私钥不入库**:`desktop/.gitignore` 排除 `updater.key` / `updater.key.pub`;CI 从 Secret `TAURI_SIGNING_PRIVATE_KEY` 注入(口令变量 `TAURI_SIGNING_PRIVATE_KEY_PASSWORD` 在 workflow 里固定为空串)。私钥保管于本机/密钥库,任何文档、聊天记录不得粘贴其内容或口令。
- CI 的 desktop job(root `.github/workflows/ci.yml`)按「是否配了私钥」分两条路:**未配置**时改写 `createUpdaterArtifacts=false`,出无签名安装包、不产 `latest.json`(构建不变红,但老用户收不到更新);**已配置**时经 `tauri-apps/tauri-action` 构建 + 签名 + 发布,产物是 `.msi` / `-setup.exe` 及各自 `.sig` 加 `latest.json`,发滚动 Release `desktop-latest`(prerelease),旧资产清理到只留本次版本与 `latest.json`。
- 版本号由 CI 自动填:根 `VERSION`(当前 `1.0`)+ `.` + 构建号写进 `src-tauri/tauri.conf.json` 的 `version`,如 `1.0.59`;**仓库里那个 `0.1.0` 只是本地占位,CI 每次覆盖,不回写仓库**,版本历史在 Release 说明里。发版后核对 Release 资产名(`TaotaoMusic_<版本>_x64-setup.exe` / `.msi` 及各自 `.sig` 加 `latest.json`)。
- 带更新器的首个签名版本是 `0.1.1`(git 历史),更早的构建收不到自动更新,需手动装一次新版;此后启动即静默升级,无需再手动安装。
- 清单缓存失效的触发在 GitHub webhook 侧:`release.published`(tag `desktop-latest`)→ `DesktopUpdaterService.invalidateCache()`(`server/src/release/github-webhook.controller.ts`),与安卓 APK 发布走同一 webhook 入口。

## 7. 构建与调试

- 构建:走根 CI 的 desktop job(`desktop/**` 或 `crypto-src/**` 路径命中时触发,Windows runner;push 到 main 或手动 `workflow_dispatch`,提交信息带 `[full]` 强制全量);本地不作为交付验收。
- 本地开发:

```powershell
cd desktop
npm install
npm run tauri dev
```

- 纯浏览器看 UI:`cd desktop && npm run dev` 后访问 `http://localhost:5183/#mock`。`index.html` 里的旁路仅在 URL 带 `#mock` 时生效:预置一个未过期的本地会话(跳过登录页),并拦截 `window.fetch` 返回合成响应——12 首 mock 歌曲的 NDJSON、40 行 LRC、指向本地静音音频的 `/link`(播放器会真实走完 `timeupdate`/`ended` 全链路),`/playback/sessions` 返回空对象(上报链路也能走通);歌单/收藏/联想/热搜返回空。打包后没有这个 hash,无任何行为差异。
- 没带 `#mock` 的纯浏览器 dev 连真实后端会被 CORS 拦(第 4 节)——联调后端要么 `npm run tauri dev`,要么用 mock。
- 本地需要 Rust stable(`x86_64-pc-windows-msvc` target)与 Node 22;CI 用 `Swatinem/rust-cache` 缓存 `desktop/src-tauri`,Release profile 用 `opt-level = "s"` + thin LTO 压体积、提编译速度。
- 本地产物链:`npm run build` 出 `desktop/dist/`,由 `tauri.conf.json` 的 `frontendDist: "../dist"` 引用;`npm run tauri dev` 按 `beforeDevCommand` 先起 Vite(固定 5183)再起 Rust 壳,改前端代码热更新、改 Rust 代码自动重编。dev 走 `devUrl` 直连 Vite、不产 dist,`beforeBuildCommand` 仅打包时生效。

## 8. 功能清单(对照安卓端)

| 功能 | 位置 | 要点 |
| --- | --- | --- |
| 登录/注册 | `auth/Auth.tsx` | 就地校验 + 邮箱验证码;401 刷新重放、会话过期统一经 `onExpired` 登出 |
| 搜索 | `main/SearchPage.tsx` | NDJSON 逐行解析(`type:"song"` / `end.meta` 携带总数与翻页);联想 250ms 防抖 + 过期结果丢弃;热搜;搜索历史(`searchHistory.ts`:可暂停记录/单删/清空);默认 `source=kuwo` 写在 `api.ts` |
| 歌曲行 | `main/SongList.tsx` + `SongRowMenu.tsx` | 「⋯」菜单:下一首播放(本地插播)/加入歌单;收藏/歌单/最近播放各页共用 `SongList` 渲染 |
| 播放与队列 | `state/AppState.tsx` + `PlayerDetail.tsx` | 唯一 `<audio>`;播放队列面板支持拖动排序/删除(行带封面缩略图;播放中曲不可删,下标跟随算法保证 current 不漂移),宽窗默认并排展示、窄窗(≤980px)以浮层弹出;插播 `playNext`(同 key 旧位置先移除、当前曲除外);循环 off/all/one |
| 底部播放条 | `main/PlayerBar.tsx` | 封面/歌名(点击打开详情)+ 细进度条 + 传输/循环/收藏/队列控制;零 props 全走 `useApp`,无当前歌曲时置灰「未在播放」 |
| 逐字歌词 | `main/lyrics.ts` + `LyricsPane.tsx` | YRC 字级卡拉OK渐变、LRC 行级、纯文本回退(shared `LyricParser` 的 TS 语义移植);换行由 `timeupdate`(约 4Hz)直接重判,逐字动画 rAF 驱动并按 timeupdate 校正漂移(漂移自愈);自动滚动居中,滚轮后 2.5 秒暂停跟随 |
| 音质选择 | `PlayerDetail.tsx` + `api.fetchQualityTiers` | 档位来自 `/songs/:id/info` 的 `qualities`;换档立即重取直链续播(恢复进度);服务端降级时 toast 实际档位(`labelOfQuality` 与 shared 同口径);偏好落 `localStorage` |
| 定时关闭 | `AppState` + `PlayerDetail` 弹窗 | 截止时间戳算剩余秒数防漂移;「播完整首再停」等待态**跨手动切歌存活**(与安卓一致);等待态优先于单曲循环 |
| 收藏 | `AppState.toggleFavorite` + `main/FavoritesPage.tsx` | 乐观更新、失败回滚;收藏库经 `batch-info` 按 id/mid 双键分批补全 |
| 歌单管理 | `main/PlaylistsPage.tsx` + `AddToPlaylistDialog.tsx` | 新建/重命名/删除/加歌/移除/整单拖拽重排;重排提交完整稳定键列表(`source + songId`,mid-only 用 `mid`),失败(400/4004)重拉详情对账;加歌弹窗顶部「＋ 新建歌单」就地创建并直接加入 |
| 最近播放 | `main/RecentPage.tsx` | `/playback/recent` 聚合行(同歌一行,上限 500)+ `batch-info` 补全(同收藏的双键写法,分批 ≤60) |
| 播放上报 | `AppState` 内部 outbox | 先落盘(`localStorage` `taotao.playbackOutbox`)再发、服务端按 `sessionId` 幂等 upsert、确认后按内容比对删除;15 秒周期快照 + 1 秒心跳位置增量(超 2 秒视为 seek 丢弃);`completed` 口径终结会话;`beforeunload` 落盘;60 秒低频重试、失败静默不登出 |
| 分享短链 | `api.createSongShare` | `POST /shares/songs` 返回 `{token,url}` 后复制(`remoteId`/`mid` 至少其一,否则报「缺少可分享的远端身份」) |
| 自动更新 | `src-tauri/src/lib.rs` | 启动静默检查,见第 6 节 |

封面一律经 `absoluteUrl()` 处理(`api.ts`):服务端下发的相对路径在 webview 里会解析到应用自身 origin 导致裂图,必须补上 API 域名;配 `img.ts` 的 `hideOnError` 兜底隐藏碎图。安卓有而桌面端**没有**的能力:离线下载、AI 工作台、IM 聊天、单曲日记、强制更新门禁(桌面走 Tauri 自动更新)。

## 9. 常见坑

- **`ENDPOINT` 与 capabilities 白名单是两处配置**:只改 `api.ts` 不改 `default.json`(或反之),请求静默 403,表现像断网——排查「桌面端全挂但接口正常」先看这两处。
- **封面裂图**:新页面直接 `<img src={song.coverUrl}>` 会踩相对路径坑,统一走 `absoluteUrl()`。
- **「继续播放」不能实现成 `playAt(当前下标)`**:`playAt` 的语义是「从头播放这首歌」,对当前曲再点会终结旧上报会话、另起新会话(多记一次播放);暂停/恢复必须走 `togglePlay`(直操音频元素,不经 `playAt`)。
- **页面是常驻挂载的**:`MainScreen` 四个页面同时在 DOM 里靠 `display` 切换,别按条件渲染改掉——会丢各页的搜索结果与滚动位置;新增页面也照这个模式加,而不是引入路由库。
- **`beforeunload` 里只落盘、不发送**:outbox 写 `localStorage` 是同步写,卸载前必然落盘;发送交给下次挂载/60 秒低频重试,别在卸载回调里发 best-effort 的 fetch(不可靠)。
- **纯浏览器 dev 的假象**:`window.fetch` 退回路径只在无 Tauri 注入时生效;在浏览器里「联调通过」不代表 webview 里通(CORS 行为不同),反之 mock 模式验证不了真实网络层。mock 预置的会话是 `accessToken: "mock"` 的假令牌,别用它调试真实鉴权。
- **网络错误文案有两套来源**:浏览器抛 `Failed to fetch`,`tauri-plugin-http`(reqwest)抛 `error sending request ...`——`readableError` 的正则两类都要匹配;换网络通道时记得补文案归一。
- **搜索 source 语义与安卓同坑**:搜索范围参数与歌曲自身来源是两回事,默认源酷我不在服务端「全部」聚合白名单里(见 [32-api-search-music.md](32-api-search-music.md))。
- **别手工维护 `tauri.conf.json` 的 `version`**:那是 CI 占位;真版本在 `desktop-latest` Release 的资产名与版本历史里。
- **更新发不出去的静默场景**:CI 没配 `TAURI_SIGNING_PRIVATE_KEY` 时构建照常绿、安装包照常发,但没有 `latest.json`——两个更新端点(后端代理与 GitHub 兜底)都拿不到清单,已装客户端永远检查不到新版本;发版后记得确认 Release 里有 `latest.json` 且版本号递增。后端代理的清单有 5 分钟缓存(webhook 发版时失效),排查「刚发版收不到更新」时先留出这个时间差。
- **更新公钥是烧进客户端的**:`plugins.updater.pubkey` 随安装包分发,轮换签名密钥对后旧客户端验不过新签名,等于断更新;确需轮换要先发一版「旧密钥签名 + 内嵌新公钥」的过渡版本。
- **Vite 端口是 `strictPort`**:5183 被占用时 dev 直接失败,不会静默换端口——Tauri 的 `devUrl` 也写死 5183,先清端口再起。
- **`taotao.quality` 只接受 0–18 的整数档位**:shared 侧新增音质档位时要同步放宽 `AppState` 恢复偏好时的范围校验,否则新档位落盘后被当无效值回退 4。
- **搜索联想是「取消式防抖」**:250ms 定时器 + 返回后校验「发起时的词 === 当前词」,不一致丢弃;加新的联想入口照抄这个模式,别改成轮询或竞态标志。
- **收藏接口的 POST 没有请求体**:`addFavorite` 不能设 `Content-Type`(与其它 POST 不同),照抄它时别顺手加头。
- **歌词换行别挪出 timeupdate 路径**:逐字动画的 rAF 在窗口失焦/被节流时会暂停,换行判定必须留在 timeupdate(约 4Hz)里,否则暂停期间卡行不跟进。
- **加密接入是「半程」状态**:Rust 侧握手链路已通、业务明文;接入加密时别把 `crypto_handshake_demo` 当业务路径,按 37 篇的失败语义(409/4091 重握手重放、426/4007 禁降级)在 `httpFetch` 层实现。
- **队列「播放中不可删」是刻意口径**:`removeQueueItem` 与插播的去重逻辑都豁免当前曲;放开它会让播放中的曲目被移除后下标错位。`moveQueueItem` 的下标跟随(移当前曲随到 to、前删左移、前插右移)同样别动。
- **工具链升级先看 npm 侧 `package-lock.json`**(正常提交):升级 React/Vite/Tauri 任一大件后本地跑一次 `npm run build`(含 tsc 类型检查)再推;Rust 依赖没有锁文件入库,以 `Cargo.toml` 的语义化版本约束为准。
