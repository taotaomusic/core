# 安卓端(androidApp)

[返回文档中心](README.md)

最后更新:2026-10-11

本篇讲安卓客户端的模块定位、构建与版本号、包结构、导航实现、关键功能的位置,以及它依赖后端时不能破坏的契约。页面组件与主题的跨端复用在 [93-player-ui.md](93-player-ui.md),共享数据模型在 [94-shared-module.md](94-shared-module.md);发布操作细节以 [61-release-android.md](61-release-android.md) 为准。本篇不讲后端实现,也不讲桌面端(`desktop/`,Tauri + React,独立演进)。

## 1. 技术栈与模块定位

- Kotlin 2.4 + Jetpack Compose(Material 3,经 CMP 1.12.1 映射 androidx compose 1.12.1 / material3 1.5.0-alpha22)+ Media3(ExoPlayer / MediaSession),协程驱动,Coil 加载封面。
- 依赖 `:shared`(数据模型、歌词、音质规则)与 `:player-ui`(品牌主题、歌曲行、迷你播放器、布局骨架;已接入 Miuix 0.9.4 组件库与 `TaotaoMiuixTheme` 主题桥,见 [93-player-ui.md](93-player-ui.md)),见 `androidApp/build.gradle.kts`。
- 本地存储已全量迁入 Room/SQLite(`data/db/TaotaoLocalDatabase`,22 张表按业务域建表;历史 SharedPreferences / JSON 文件由 `PrefsToRoomMigrator` 在首启一次性迁移),业务数据不再落 SharedPreferences。
- 播放走前台服务 `player/PlaybackService.kt`(`MediaSessionService`),锁屏媒体通知由此承载;服务识别媒体按键与自定义「收藏」会话命令,定时器等控制统一走 MediaController 会话命令。
- 传输加密经 JNI 调 `crypto/dist/android/` 下的 `libtaotao_crypto.so`(构建脚本把它挂进 jniLibs;产物缺失不阻断构建,运行时降级明文)。
- 工具链:JDK 21 + Gradle Wrapper(9.6)+ AGP 9.4.1 + Kotlin 2.4.21(KSP 2.3.12);`minSdk 24`、`targetSdk 35`、`compileSdk 37`;Release 包只保留 `arm64-v8a`(正式包面向 64 位 ARM 真机)。

## 2. 构建与版本号

```powershell
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :androidApp:assembleRelease   # 产物在 androidApp/build/outputs/apk/release/
```

- 两个 assemble 任务都 `finalizedBy("incrementVersion")`,构建结束会**递增 `version.properties`**。
- 因此刚产出的 APK 的版本**只能**读 `androidApp/build/outputs/apk/release/output-metadata.json`;不能读构建后的 `version.properties` 当作本包版本,也不要回滚或复用旧号——重号会静默覆盖已发布记录的 sha256,导致更新推不出去;跳号无害。
- 签名密钥(`taotao-release.jks`)与口令从本机 `local.properties` 读取(`TAOTAO_STORE_PASSWORD` / `TAOTAO_KEY_ALIAS` / `TAOTAO_KEY_PASSWORD`),不进版本库;云端 CI 未配置签名 Secrets 时自动退回 `assembleDebug` 仅验证工具链(见根 `.github/workflows/ci.yml` 的 `还原发布签名` 步骤)。
- `BuildConfig.VERSION_NAME` 会被拼进全局 User-Agent(`AppHttp.USER_AGENT`,形如 `TaotaoMusic/1.0.236`),全 App 请求共用。
- Release 启用 **R8 收缩 + 资源收缩**(`isMinifyEnabled = true`,APK 19.2MB → 8.0MB),与热修复插桩共存的三条边界:不混淆(补丁键是「类名#方法名#签名」精确匹配)、关闭优化(`-dontoptimize`——R8 没有 per 规则的禁内联,`-dontinline` 是 ProGuard 选项它不认)、插桩三包(data / player / update)整包 keep;完整规则见 `androidApp/proguard-rules.pro`。
- AGP 9 有两个**过渡开关**必须保持(`gradle.properties`):`android.builtInKotlin=false` 与 `android.newDsl=false`——KGP 的 `androidTarget()` 与 AGP 9 新 DSL 不兼容;这两笔债要在 AGP 10 之前还掉。

## 3. 包结构(代码全部在 `com.taotao.music` 层级下)

| 位置 | 职责 |
| --- | --- |
| `MainActivity.kt` / `TaotaoApplication.kt` | Activity 入口与应用初始化;`AppHttp.kt` 统一 HTTP 头;`TaotaoApplication.appState` 持有**进程级状态单例**(详情 Activity 与主界面共享同一份状态) |
| `crypto/` | 传输层加密:`CryptoTransport`(握手、逐块加解密、PSK 获取)、`NativeCrypto`(JNI 句柄封装)、`CryptoException`;硬件设备号在 `data/crypto/HardwareDeviceId` |
| `data/` | 业务数据层,详见下表 |
| `hotfix/` | 热修复补丁:`HotfixInstaller`/`HotfixLoader`/`HotfixStore`/`HotfixDiagnostics`/`PatchDispatcher`/`PatchEntry`;字节码插桩只作用于 data / player / update 包(build-logic 的 `com.taotao.hotfix` 插件),UI 层不碰 |
| `player/` | `AudioPlayer`(Media3 封装:队列增删、`removeQueueItem`、`keepOnlyCurrent`、上下曲、循环模式)、`PlaybackService`(常驻媒体通知、进程死亡后恢复播放、通知栏收藏)、`PlaybackMediaItem`(队列落盘快照,冷启动恢复)、`SleepTimer`/`SleepTimerPolicy`(定时播放纯规则) |
| `update/` | 全量更新:`UpdateManager`/`UpdateDownloader`/`UpdateInstaller`/`UpdateModels`/`AppUpdateApi`,对接 `/app/bootstrap` 与 APK 下载 |
| `ui/TaotaoMusicApp.kt` | **61 行主入口**:创建状态容器、强制更新门禁、登录门禁、副作用挂载,自身不写页面布局 |

`data/` 内部按职责分块,挑常用的(文件都在 `androidApp/src/main/java/com/taotao/music/data/`):

| 分块 | 文件 | 职责 |
| --- | --- | --- |
| 本地数据库 | `data/db/`(`TaotaoLocalDatabase` + 按域 `*Db`) | Room/SQLite 全量本地存储:22 张表按业务域建表(收藏、播放历史/队列快照/outbox、搜索历史、音质/外观/皮肤/定时等偏好、IM 凭据与对端、AI 会话、远程配置);`PrefsToRoomMigrator` 首启把旧 SharedPreferences / JSON 一次性迁入,`MirroredPreference` 提供可观察镜像 |
| API 客户端 | `TencentMusicApi.kt`(约 1700 行) | **唯一**后端客户端:NDJSON 搜索、联想/热搜、播放地址、歌词、歌单、在线歌单、排行榜、播放上报、单曲日记、分享、图片任务全在这 |
| 会话 | `AuthSession.kt` / `TokenProvider.kt` / `DeviceIdStore.kt` | 登录会话、令牌存取与设备号(`data/crypto/HardwareDeviceId` 提供硬件级来源) |
| 播放上报 | `PlaybackSyncCoordinator.kt` / `PlaybackSyncStore.kt` / `PlaybackStateStore.kt` / `PlaybackHistoryStore.kt` | 播放会话的落盘 outbox、账号分桶与历史记录 |
| 下载 | `OfflineDownloadManager.kt` / `DownloadedSongResolver.kt` / `DownloadNotifier.kt` / `SongCodec.kt` | 离线下载、本地文件解析、下载进度通知;`SongCodec` 负责 `Song` 与 JSON 互转(队列冷启动恢复 + MediaMetadata extras) |
| 偏好与缓存 | `FavoritesStore` / `SearchHistoryStore` / `QualityStore` / `AppearanceStore` / `RemoteConfigStore` / `SleepTimerStore` | 收藏、搜索历史、音质、外观、远程配置、定时器状态(均已改为 `data/db/` Room 表的门面) |
| IM | `data/im/`(`WukongImClient` 等)+ `ImPeerStore.kt` | 悟空 IM 客户端、对端缓存与通知 |
| 其他 | `AiChatStore` / `AiImageSaver` / `CrashReporter` / `GreetingFormatter` | AI 工作台会话与图片保存、未捕获异常记录(在 `TaotaoApplication` 注册)、首页问候语 |

`ui/` 下的应用状态层(`ui/app/`)与功能子包:

| 文件 / 包 | 职责 |
| --- | --- |
| `ui/app/TaotaoAppState.kt` | 全局状态容器(播放、收藏、外观、账号、导航子状态)与操作入口;经 `TaotaoApplication.appState` 成为进程级单例,主界面与详情 Activity 读同一份 |
| `ui/app/SearchState.kt` / `PlaylistState.kt` / `OnlinePlaylistPageState.kt` | 搜索域、云端歌单域与在线歌单详情页状态;歌单请求绑定账号代际,换号拒绝旧请求回写 |
| `ui/app/RankingState.kt` / `ArtistPageState.kt` / `AlbumPageState.kt` | 排行榜、歌手页、专辑页状态;榜单目录 + 分页详情,external 榜按 `detailKey` 寻址 |
| `ui/app/AppEffects.kt` | 登录前后副作用:通知权限、公告、outbox 补发、IM 前后台、**返回键**、热更新检查与补丁确认 |
| `ui/app/AppPageRouter.kt` | 把页面状态映射成页名并经 `AnimatedContent` 分发装配;MV 浮层也挂在这,详情 Activity 在场时让位(见第 4 节) |
| `ui/app/AppContent.kt` | 登录后主骨架:主题、可选更新提示、底部导航、迷你播放器、全局 Snackbar |
| `ui/app/AppDialogs.kt` | 弹窗与底部面板装配 |
| 其余 15 个功能子包 | `home`、`search`、`player`、`playlist`、`library`、`mine`、`auth`、`settings`、`account`、`ai`、`chat`、`update`、`album`、`artist`、`ranking` |

`ui/app` 加上这 15 个共 **16 个功能子包**;`ui/common`(歌曲行、拖动排序、歌曲身份、分享)与 `ui/theme`(主题、动画)是设施包。以 `androidApp/src/main/java/com/taotao/music/ui/` 实际目录为准;新增整页新建子包或放入现有包都可以,但导航三处(下节)必须同步。

## 4. 导航:新增一整页必须同步三处;播放详情页是独立 Activity

主界面页面导航是手写的 `AnimatedContent`(不是 Navigation 库),返回键也是手写 `BackHandler`。**漏任何一处,症状都是「点了底部标签却还停在原页面」或返回键行为错乱**:

1. **`switchTab`**:底部标签切换时把所有叠放页面的显示状态复位(搜索、设置、资料、歌单详情、日记、歌手/专辑/在线歌单、排行榜等),见 `ui/app/TaotaoAppState.kt`;离开聊天标签时还要清掉 IM 活动会话;同时 `closePlayerDetail()` 关掉详情 Activity。注意**排行榜是从首页进入的全局叠放页**,不从属于任何底部标签,换标签时若不收起,它会继续抢走路由。
2. **`AnimatedContent` 的 `targetState`**:`ui/app/AppPageRouter.kt` 里 `currentPage` 的 `when` 映射要加分支。顺序有讲究:歌手/专辑/在线歌单是搜索页的下级页,要排在 `search` 之前;榜单歌曲行还能把歌手/专辑页叠进来,所以后两者分支更靠上先被截获;排行榜从首页(when 链末尾的 home 宿主)进入,排在所有底部标签之前即可。
3. **`BackHandler`**:`ui/app/AppEffects.kt` 的返回键拦截要纳入新页面的收起逻辑,顺序与 `currentPage` 一致——先退 MV 浮层;日记记录是日记的下级页,先退记录再退日记;歌手/专辑/在线歌单是从搜索或榜单歌曲行进入的下级页,要先于搜索页/排行榜退出;排行榜两级退出(先详情回目录,再整页回首页)。`PlayerDetailPage` 内还有一个更深的 `BackHandler`(歌词分页),启用时优先生效。

**播放详情页不在 `AnimatedContent` 里**——已拆成独立 `ui/player/PlayerDetailActivity.kt`(PR#27/#28)。金标联盟「一镜到底」(SharedViewKit)的转场是**系统级**的,只发生在两个 Activity 之间:迷你播放器(主界面)与详情页必须分属两个 Activity,荣耀/OPPO/vivo/小米的支持机型上 ROM 才能接管开合动画;不支持机型自动降级为 `Theme_Taotao_PlayerDetail` 的底部滑入/滑出窗口动画,观感与原先 Compose 内的升起动效一致。要点:

- **状态共享**:详情 Activity 不经 Intent 传参,直读直写 `TaotaoApplication.appState`(进程级单例);关详情是事件通道语义——`TaotaoAppState.closePlayerDetail()` 发、详情 Activity 收到后 `finish()`。
- **标签栏常驻**:详情 Activity 挂 player-ui 的 `SharedMainLayout` + 共享的 `taotaoNavigationItems`,底部应用标签栏在详情页内照常显示,点击直接切主界面路由。
- **MV 浮层归属门控**:`playerDetailActive` 为真时 MV 浮层由详情组合渲染、主界面让位(`if (!state.playerDetailActive) MvPlayerOverlay(...)`)——两个组合读同一份 `mvSong`,都渲染会出现两份音频暂停/恢复记账。
- Compose UI 升 1.12 后 `boundsInWindow` 从属性变成了**扩展函数**,SharedViewKit 接线代码要注意写法。

坑:聊天页是唯一不走 `AnimatedContent` 的页面——直接挂载、切出即销毁,不与目标页并行布局;给它加子状态时不要套进通用页面过渡里。

## 5. 播放与队列链路

- 页面只调 `TaotaoAppState` 的操作(`playSong` / `playAdjacentSong` / `togglePlayback` / `moveQueueItem` 等),状态层转手给 `player/AudioPlayer.kt`;`AudioPlayer` 封装 Media3,宿主是 `PlaybackService`(`MediaSessionService`)。
- 队列的权威副本在状态层(`playbackSongs` + `selectedIndex`),界面(迷你播放器、详情页、队列面板)全部从它渲染;AudioPlayer 提供队列操作(`removeQueueItem`、`keepOnlyCurrent`、插入下一首)。
- 定时器通过 MediaController 会话命令(`SleepTimerContract.COMMAND`)下发到服务,服务端状态(剩余时长、等待态)经 extras 回读;规则计算放在无 Android 依赖的 `SleepTimerPolicy` 里,便于单测。
- **常驻媒体通知与进程恢复**(PR#30):暂停态也保留媒体通知,点播放键直接续播;任务被划掉且队列清空才结束服务。进程被系统回收后,媒体面板/通知卡片上的「继续播放」用 `PlaybackMediaItem` 落盘的队列快照还原——上游直链是限时的,恢复时按歌曲重新取址而不是复用快照里的地址。
- **通知栏收藏按钮**(PR#30):媒体通知自定义「收藏」是另一个会话命令(Android 13+ 媒体控件与各 ROM 媒体卡都渲染 session custom layout);收藏动作与图标状态都经进程级 appState 单例,与界面共用同一份收藏库和账号同步链路,切歌时图标要跟着刷。
- 播放事件的上报边界在 `PlaybackSyncCoordinator`(见第 6 节表格):落盘先行,网络补传异步。

## 6. 与后端的契约要点

安卓端是 30–37 各篇契约的**最大消费方**,这里只列客户端侧最容易踩的几条:

| 契约 | 客户端实现 | 后端篇 |
| --- | --- | --- |
| 访问令牌与 401 语义 | `TencentMusicApi.authorized()`:令牌被拒自动续期并**重放一次**;刷新失败按会话彻底失效处理——停播、清账号缓存、回登录页 | [31-api-auth-user.md](31-api-auth-user.md) |
| `/search` 裸 NDJSON | 逐行读 `type`;`onProgress` 回调传**当前累积的完整列表**而不是新增一首——401 重放会把 NDJSON 从头再读一遍,追加式回调必然产出重复条目 | [32-api-search-music.md](32-api-search-music.md) |
| 传输加密 | `CryptoTransport`:PSK 由后端 `GET /api/v1/crypto/psk` **动态下发**(需登录令牌),客户端不内嵌密钥;native 库缺失、握手失败一律**回退明文**,绝不因加密崩溃阻断功能;加密会话失效(409/4091)自动作废会话、重握手并重放一次 | [37-api-crypto.md](37-api-crypto.md) |
| 云端歌单 revision | 歌单模型带 `revision`/`updatedAt`,写操作成功后以服务端返回为准;排序必须提交**完整稳定键列表**(`source + songId`,mid-only 歌用 `mid`),缺歌多歌的旧请求会被服务端拒绝 | [33-api-playlists.md](33-api-playlists.md) |
| 播放上报 | `PlaybackSyncCoordinator`:播放结束/暂停/切歌**先同步落盘 outbox 再走网络**;队列按账号分桶隔离(换号后旧账号的快照绝不能用新账号令牌上传),冷启动补发;清空按服务端 `revision` 代际对齐,不信任设备本地时间 | [34-api-playback.md](34-api-playback.md) |
| 热更新 | `/app/bootstrap` 检查放在**登录门禁之前**——最需要强制更新的场景恰恰是上个版本把登录搞坏了;每个响应头里的 `X-Latest-Version-Code` 驱动「会话中途发现新版本」;灰度主体按用户/设备稳定哈希分桶,未命中拿不到更新属正常 | [61-release-android.md](61-release-android.md) |

客户端判断成功的唯一依据是响应体 `code === 0`;401/403 语义(401 清会话跳登录、403 是已认证但权限不足)不能在客户端做任何「宽容」处理。

## 7. 关键功能与实现位置

| 功能 | 位置 | 要点 |
| --- | --- | --- |
| 封面皮肤 | `player-ui/.../playerui/skin/`（共享框架 + 9 皮肤）；Android 平台件在 `ui/player/skin/`（coil 加载器、SharedPreferences 偏好） | 详情页封面区唯一入口 `CoverSkin(skin, …, imageLoader)`，按 `CoverSkinId`（黑胶/CD光碟/磁带机/光晕/圆角卡片/声波/潮汐/扇形卡叠/霓虹环）分派；**框架已下沉 player-ui 共享层，Android 与 Web 分享播放器共用**，唯一的平台差异是封面加载插槽 `CoverImageLoader`（Android=coil 可读本地 file:，Web=fetch+Skia）；旋转驱动用共享层 `rememberCoverRotationState(isPlaying, active, onCoverPage, restartKey)`；新增皮肤三步：`CoverSkinId` 枚举加值（中文 label + squareArt）→ 新建同签名控件 → `CoverSkin` 的 `when` 加分支，注册表测试在 `player-ui/src/commonTest/`（三端同跑）；偏好各端自持（Android=SharedPreferences `cover_skin`，Web=localStorage 同名键）；Web 端方卡类皮肤用 `squareArt` 标记选矩形插槽不被裁圆 |
| 拖动排序 | `ui/common/DragReorderList.kt` | 播放队列(`PlaybackQueueSheet`)与歌单详情(`PlaylistPages`)共用;长按拖把整行跟随、跨半行换位;拖动期间由本地副本驱动,外部数据只在空闲时同步;行身份用内部自增 id,同一首歌在队列出现两次也不丢手势。刻意放在 androidApp 而非 player-ui:它依赖 Android 侧 `LocalReduceMotion` 动画设施 |
| 歌曲行 | `ui/common/components.kt` 的 `SongRow` | 包装 player-ui 的 `SharedSongRow`;搜索、收藏、本地、历史、队列全部走它;拖把与更多菜单**并存**(详见 93 篇的坑) |
| 定时关闭 | `ui/player/SleepTimerDialog.kt` + `player/SleepTimer.kt`/`SleepTimerPolicy.kt` | 底部弹层;「播完整首歌再停止播放」只切换到期行为、不重置正在进行的倒计时;到期后进入 `WAITING_SONG_END` 等待态,**只有自然播到下一首(含单曲循环转圈)才算兑现,手动切歌不算**——等新歌自然播完再停(见 `PlaybackService`) |
| 详情页快捷行 | `ui/player/PlayerDetailPage.kt` | 顶栏(`headerActions`)只留收藏;下载 / 分享 / 加入歌单挪到进度条上方的 `quickActions` 插槽——否则三个按钮加 VIP 角标和音质标签,长歌名会被压到一两个字;音质 Chip 挂元数据尾部插槽,本地文件不可点 |
| 详情页 Activity | `ui/player/PlayerDetailActivity.kt` | 播放详情页的宿主,拆独立 Activity 接金标 SharedViewKit 一镜到底(原因与要点见第 4 节);不支持机型降级为窗口动画 |
| MV 播放页 | `ui/player/MvPlayerPage.kt` | 全屏视频浮层(替代旧 Dialog):盖在详情/主界面之上,路由层音频互斥;竖屏时控制块移到视频下方黑区、点视频=播放暂停;进度条复用 `AppleStyleSlider`(accent/inactive 配色可覆盖);浮层根节点必须 `pointerInput` 吞事件,否则触摸点透到下层页面 |
| 排行榜 | `ui/ranking/RankingPage.kt` + `ui/app/RankingState.kt` | 首页入口 → 模块分组榜单卡 → 榜单详情;详情真分页(页大小上限 100、`pn` 1 基,不要把上游分页当「无分页」);external 榜(腾讯音乐榜/巅峰潮流榜)按目录下发的 `detailKey` 走 `/rankings/external/:key` 原生详情——巅峰榜 300 首带 rank 可直接点播,腾讯音乐榜无原生详情则提示不支持;封面策略:全球榜固定 logo,其余用第 1 名专辑封面优先(目录 pic 是运营占位图,只做兜底) |
| 歌手 / 专辑页 | `ui/artist/` / `ui/album/` | 搜索结果、歌曲行更多菜单、榜单与歌单详情头部均可进入;`artistId`/`albumId` 要沿歌曲行到 `toUpstreamSong` 全链路透传,中间跳一层就丢 |
| 在线歌单详情 | `ui/playlist/OnlinePlaylistPage.kt` + `ui/app/OnlinePlaylistPageState.kt` | 搜索歌单标签点击直达;与账号云端歌单是两套东西,`sourceMarker` + `id` 寻址,详情参数走 GET query(见 [32-api-search-music.md](32-api-search-music.md) 第 9 节) |
| 单曲日记沉浸式顶部 | `ui/library/MineLibraryPages.kt`(`SongDiaryPage`/`DiaryRecordsPage`) | 顶部珊瑚渐变向下淡出,画到状态栏底下;宿主(`AppPageRouter`)对 `song-diary` / `diary-records` 两页放开统一顶部内边距,页面自己用 `statusBarsPadding` 让开 |
| 逐字歌词 | `ui/player/LyricPanel.kt` | 按 shared `Lyric` 的 `progressOf` 做字宽插值高亮;用户手动滚动后 2.5 秒内暂停自动跟随 |
| 音质选择 | `ui/player/QualitySheet.kt` | 一个面板三种用途:`QualitySheetKind`(当前歌 / 播放默认 / 下载默认);下载前选音质——「下载只花一次流量」;档位与降级展示名来自 shared 的 `AudioQuality` / `labelOfQuality` |
| 队列管理 | `ui/player/PlaybackQueueSheet.kt` | 三个 Tab:当前队列 / 最近播放 / 本地歌曲;正文视口高度固定,避免列表长短反复改变 BottomSheet 高度;当前队列支持拖动排序与「只保留当前」 |
| 单曲倒带日记 | `ui/library/MineLibraryPages.kt` + `TencentMusicApi.songDiary` | 按歌聚合的播放回顾画像与逐条记录;入口在歌曲更多菜单与收藏/历史列表,`onOpenDiary` 统一走 `openSongDiary` |
| 分享短链 | `ui/common/Share.kt` | 平台层生成短链(服务端 `/shares/songs`)后调系统分享面板;公开分享页免登录可开 |
| 分享页唤起(深链接) | `data/OpenSongLink.kt` + `ui/app/AppEffects.kt` 的 `TaotaoAppOpenLinkEffect` | 解析 `taotaomusic://open?...`(Web 分享页「打开桃桃音乐」按钮拼的)还原成 `Song` 接续进 `playSong`;**参数键与 webApp `Main.kt` 的 `openAppUrl` 一一对应,两端必须同步改**;`MainActivity` 是 `singleTask`(已开 App 时链接经 `onNewIntent` 送达),链接在登录门禁之后消费——登录完成的一刻自动接播,残缺参数(无 ID 无 mid、缺标题/歌手)静默忽略 |
| 收藏 / 歌单 / 最近播放 / 分享 | `ui/library/`、`ui/playlist/`、`ui/common/Share.kt` | 收藏本地优先 + 后台同步账号;歌单写操作走 `PlaylistState`(账号代际绑定,退出/换号取消旧请求并拒绝回写) |
| 搜索联想 | `ui/app/SearchState.kt` | 输入停止 250ms 后请求联想,上一关键词的在途协程由 `LaunchedEffect` 自动取消;热搜打开搜索页时刷新,失败保持空列表、不阻断正常搜索 |
| 下载 | `data/OfflineDownloadManager.kt` 等 | 下载完成后歌曲落库并关联音质档位(`Song.localQuality`);本地歌曲页删除走确认弹窗(`state.pendingDelete`) |
| 首页 | `ui/app/AppPageRouter.kt` 的 `HomePage` | 问候语(`GreetingFormatter`)、搜索入口、公告预览、今日推荐卡 |
| AI 工作台 | `ui/ai/AiStudioPage.kt` | 图片生成任务的创建与状态轮询(后端 `/draw` 接口域),保存经 `AiImageSaver` |
| 聊天 | `ui/chat/ChatPage.kt` + `ChatPageHost` | 悟空 IM 会话;唯一不走 `AnimatedContent` 的页面,离开标签立即取消活动会话 |
| 账号资料与公告 | `ui/account/AccountAndAnnouncementDialogs.kt` | 资料编辑页(`AccountProfilePage`)与公告弹窗 |
| 设置 | `ui/settings/SettingsPage.kt` | 播放/下载音质、外观、资料、定时关闭入口集中;复用 `QualitySheet` 的面板 |
| 更新门禁 | `ui/update/UpdateGate.kt` | 强制更新走 `ForceUpdatePage`(主入口拦截),可选更新走 `OptionalUpdateDialog`(主骨架内) |

## 8. 发布与热更新

- APK 构建 → 登记(原始字节 POST)→ Range 验证 → 灰度 → 抬最低版本,服务端操作全流程见 [61-release-android.md](61-release-android.md);云端 CI 出包与版本号收编见 [62-ci-cloud-build.md](62-ci-cloud-build.md)。
- 更新流程在客户端是一个状态机:`UpdateStage { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, READY, FAILED }`;`forced` 贯穿所有阶段——强制更新下即使下载失败也不能放行界面,只能重试,否则被判定必须升级的客户端会绕过门禁继续用旧版本(见 `update/UpdateModels.kt`)。强制更新页与可选更新弹窗都在 `ui/update/UpdateGate.kt`。
- 热修复补丁:`hotfix/` 装载补丁并按尝试计数自愈——界面组合后再等 5 秒仍活着才 `confirmPatch` 清零计数,否则下次启动按「加载后启动失败」回滚(见 `ui/app/AppEffects.kt`);确认 key 必须是 `activePatchVersion` 而不是 `Unit`,否则会话中途装的补丁尝试计数永远停在 1,「点了能生效,一重启就没了」。
- 版本号铁律、不能破坏的客户端契约的完整清单在项目根 [RELEASE.md](../../RELEASE.md);`version.properties` 由每次构建递增、云端构建成功后回写仓库,本地不要手工改它。

## 9. 测试

- Android 单元测试在 `androidApp/src/test/`(现有 `data`、`player` 两组,如 `SleepTimerPolicy` 这类纯规则),设备测试在 `androidApp/src/androidTest/`;跨平台共享逻辑的测试放 `shared/src/commonTest/`(见 94 篇)。
- 运行:

```powershell
.\gradlew.bat :androidApp:testDebugUnitTest
.\gradlew.bat :shared:allTests
```

## 10. 常见坑

- **登录页与更新页要自己套主题**:`ForceUpdatePage` 在主内容之前就 `return`,不套 `TaotaoTheme` 会拿到 Material 默认配色,暗色下白底白字;弹窗层在主 `Surface` 之后组合,同样要显式继承外观主题,否则暗色退回亮色。
- **本地存储已迁 Room,读走进程级镜像**:偏好类存储经 `MirroredPreference` 读进程级内存镜像(主线程高频读,Room 拒绝主线程访问)、写先改镜像再串行落库;需要触发重组的状态(如 `favoriteRevision`、`downloadedSongs.size`)仍要显式当参数传下去,读一下值让 Compose 感知变化。
- **Room 事务带返回值必须显式 `Callable`**:`runInTransaction` 的 `Runnable` 重载没有返回值,返回值 lambda 要走 `runInTransaction(Callable<T>)`,重载选错返回值静默丢失。
- **Media3 同批事件先逐个回调、最后才回调 `onEvents`**(PR#29):通知栏「暂停」直达 MediaSession、不经过 `AudioPlayer.pause()`,UI 侧「转场后继续播」的意图标志对它失明——自动转场后的缓冲窗口里按下暂停,会被 `onEvents` 里的自动续播撤回。修复是 `onPlayWhenReadyChanged` 里**任何暂停都立即失效**该标志(只有恢复为 true 才重置);控制器断连时也要清标志,残留 true 会在重连后把暂停态误判成转场意图。
- **下载前先定目标**:取歌要在主线程先拿到 `target` 再起后台任务,否则下载期间 `selectedIndex` 变了会下错歌或越界。
- **通知权限**:Android 13+ 的 `POST_NOTIFICATIONS` 决定锁屏媒体通知与下载进度通知是否可见,启动时申请一次、拒绝后不再骚扰(见 `AppEffects`)。
- **`/search` 的 source 语义**:搜索范围参数与歌曲自身来源是两回事;搜索默认源是酷我(`SEARCH_SOURCE_KUWO`),「全部」是服务端聚合白名单,不等于所有已接入音源——酷我就不在其中,只能显式指定。
- **歌单详情的去重候选顺序**:`knownSongs` 必须是「已下载列表 + 歌单候选」的拼接顺序(`state.downloadedSongs + state.playlistCandidates`),下载列表在前——这样歌单详情才能直接关联本机 `file:` 音频;调换顺序会丢本地关联。
- **歌单快照里的地址是限时的**:加歌单时 `audioUrl` 等会随快照保存,但直链有时效——播放链路要按 `MUSIC_CROSS_PLATFORM.md` 的约定经音乐接口重新解析,别假设快照里的地址永远有效。
- **单曲播放失败自动跳下一首**:`AudioPlayer.onPlayerError` 会把失败曲目记入失败集、找下一个可恢复的曲目自动跳过去并提示「已继续下一首」;不要在页面上另写一层失败重试,避免双重跳歌。
- **颜色不走 MaterialTheme 的硬编码**在暗色下必翻车:卡片用 `surface`、次要文字用 `onSurfaceVariant`,写死 `Color.White` / `Color.Gray` 就是白底白字。
