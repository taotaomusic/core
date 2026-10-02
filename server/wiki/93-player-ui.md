# player-ui 跨端播放组件库

[返回文档中心](README.md)

最后更新:2026-10-01

本篇讲 `player-ui/` 模块:定位与实际消费方、主题体系、核心组件清单、各端接入点,以及插槽注入的设计约定。安卓端怎么用这些组件见 [90-client-android.md](90-client-android.md),模型层(`Song` 等)见 [94-shared-module.md](94-shared-module.md)。本篇不讲页面业务逻辑,也不讲后端接口。

## 1. 定位与实际消费方

- Kotlin Multiplatform 库(`player-ui/build.gradle.kts`),目标平台只有 **Android + wasmJs 浏览器**;依赖 `:shared` 与 Compose Multiplatform(M3 + material-icons-extended),不依赖任何平台图片库、持久化或播放引擎。
- 代码分三个包:`com.taotao.music.playerui`(组件与状态模型)、`...playerui.theme`(token 与苹果风格控件)、`...playerui.layout`(主骨架);Gradle namespace 为 `com.taotao.music.playerui`。commonMain 合计约 2000 行 Kotlin,体量小到可以通读——改组件前先把目标文件完整读一遍是最低成本。
- 装的是「播放主题、歌曲行、迷你播放器、布局骨架」这一层视觉与交互骨架:三样东西进来(状态、回调、插槽),其余一切平台差异留在外面。
- **当前消费方是 `androidApp` 与 `webApp` 两端**。桌面端(`desktop/`,Tauri 2 + React + TypeScript)不复用 Kotlin 组件,有独立的 React UI——这与根目录 `MUSIC_CROSS_PLATFORM.md` 的描述一致。player-ui 部分源码注释里残留的「三端 / Windows」是历史措辞,**以构建脚本的实际 target 为准**;`PlayerWideLayout` 就是为宽屏端预留的,目前两端都没有调用方。

## 2. 目录结构(`player-ui/src/commonMain/kotlin/com/taotao/music/playerui/`)

| 文件 | 内容 |
| --- | --- |
| `PlayerTheme.kt` | `TaotaoPlayerTheme`:品牌色 + M3 亮/暗配色方案 + 排版 + 形状 |
| `PlayerUiModels.kt` | `PlayerUiState`、`PlayerActions`、`PlayerCapabilities`、`PlayerRepeatMode`,以及进度↔时间、空白文案归一化的内部工具 |
| `PlayerSurface.kt` | 播放详情部件族:`PlayerArtworkSlot`(封面插槽)、`PlayerSongHeader`、`PlayerPlaybackDetails`、`PlayerProgress`、`PlayerTransportControls` |
| `PlayerCompactLayout.kt` | 移动端紧凑纵向播放详情布局,所有公共部件在一个容器里;标题/元数据/顶栏/快捷操作/控制条两端都留插槽 |
| `PlayerWideLayout.kt` | 宽屏布局(预留,暂无调用方) |
| `SharedSongRow.kt` | 歌曲行视觉骨架:封面/操作走插槽,不可播置灰 |
| `SharedMiniPlayer.kt` | 迷你播放器(状态 + 动作 + 整行点击 + 封面插槽) |
| `SharedMainLayout.kt`(layout/) | 主骨架:底部导航(`SharedNavigationItem`)+ 内容区 + 迷你播放器插槽,内边距以 `PaddingValues` 交给页面 |
| `SharedContentState.kt` | 加载 / 空数据 / 失败三态视图(`SharedContentStateType`),带无障碍 live region 语义 |
| `SharedCard.kt` / `SharedSectionHeader.kt` / `SharedBackButton.kt` | 卡片、分区标题、返回按钮。分区标题收敛为 `SharedSectionLevel` 三档(`PAGE` 页面主标题 / `SECTION` 区块标题 / `CARD` 卡片分组),样式抽成接收 `Typography` 的纯函数——既尊重主题覆盖(webApp 会换字体),commonTest 也能直接断言 |
| `theme/TaotaoShapes.kt` | 五级圆角刻度 + 胶囊 + 语义别名 + `TaotaoStroke` 描边刻度 |
| `theme/TaotaoTypography.kt` | 自建 10 级字号刻度(`TaotaoTypeScale`)与 M3 `Typography` 映射 |
| `theme/TaotaoSpacing.kt` / `TaotaoSizes.kt` / `TaotaoElevation.kt` | 间距、尺寸(含封面三档)、海拔与阴影色刻度 |
| `theme/AppleStyleComponents.kt` | 苹果风格的滑杆与播放按钮(播放详情的控制件) |
| `theme/AppleStyleTheme.kt` | **已废弃**的圆角兼容别名,只作迁移期过渡 |

测试在 `src/commonTest/`:`PlayerActionsTest`(动作出口与进度换算)、`SharedComponentsTest`、`theme/TaotaoTokensTest`(token 刻度)。与其他 KMP 模块一样走 `allTests` 聚合任务:

```powershell
.\gradlew.bat :player-ui:allTests
```

## 3. 主题体系

`TaotaoPlayerTheme(darkTheme) { ... }` 是品牌主题的唯一入口,排版与形状默认取本项目的 token,**不再使用 Material 3 的默认值**:

- **颜色**:珊瑚色 `PlayerCoral`(亮)/ `PlayerCoralDark`(暗)为主色;亮暗两套 `lightColorScheme`/`darkColorScheme` 都**必须覆盖 `surfaceContainer` 一族**——M3 基线的容器色是带紫/蓝灰调的灰,弹窗、下拉菜单、底部抽屉都从这族取底色,不覆盖就会和应用暖色脱离。层级越高越深(亮色),`surfaceContainerHigh` 复用 `surfaceVariant`,token 数量不膨胀。
- **排版**:`TaotaoTypography` 是 11–40sp 的 10 级字号刻度(从 `minorTitle` 到 `hero`),不沿用 M3 默认值——默认排版为拉丁字母设计(正字距、行距偏紧、最大字号 57sp),中文观感不对。
- **形状**:`TaotaoShapes` 是 6/10/14/20/28dp 五级圆角刻度 + 胶囊,并给出语义别名(`badge`/`button`/`artwork`/`card`);禁止再引入第六套圆角语言。
- 间距、尺寸、描边、投影分别走 `TaotaoSpacing` / `TaotaoSizes` / `TaotaoStroke` / `TaotaoElevation`:`TaotaoSpacing` 是 0–40dp 的九级刻度(`xxs` 4dp 到 `xxxl` 40dp,`screenHorizontal` 复用 `lg`);`TaotaoSizes` 收敛了图标(16/18/24/30dp)、封面(`artworkRow` 48 / `artworkGrid` 112 / `artworkHero` 132)与播放按钮等刻度——封面历史上分档过细,现已收敛。公共组件里写死数值(10dp、14dp 之类不成刻度的数)是回退行为,评审要拦。
- **主题参数(typography / shapes)保留可覆盖**,是为了单端做局部实验不必改公共主题;正式页面一律用默认值,覆盖属于例外并要说明理由。
- androidApp 的全局 `TaotaoTheme`(`ui/theme/Theme.kt`)**直接包着 `TaotaoPlayerTheme`**,再叠加 `LocalReduceMotion`(减弱动态效果);webApp 分享页也用 `TaotaoPlayerTheme` 起主题。两端进入时不会突然换色,就是这里保证的。

token 刻度速查(改组件时对号入座,不要自创新档):

| token 族 | 刻度 |
| --- | --- |
| `TaotaoSpacing` | 0 / 4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 dp 九级(`xxs`→`xxxl`);`screenHorizontal` 复用 `lg` |
| `TaotaoShapes` | 6 / 10 / 14 / 20 / 28 dp 五级 + `pill`;语义别名 `badge` / `button` / `artwork` / `card` |
| `TaotaoStroke` | 0.5 / 1 / 2 / 3 dp(hairline / thin / medium / thick) |
| `TaotaoTypeScale` | 11–40sp 十档字号(hero 40 / display 34 / headline 26 / title 22 / subtitle 20 / sectionTitle 18 / cardTitle 16 / minorTitle 14 / body 16…) |
| `TaotaoSizes`(图标) | 16 / 18 / 24 / 30 dp,`iconButton` 36dp |
| `TaotaoSizes`(封面) | `artworkRow` 48 / `artworkGrid` 112 / `artworkHero` 132;`artworkBrand` 88、`avatar` 64 |
| `TaotaoElevation` | 海拔与阴影色随亮暗主题走(`taotaoShadowColors`),不写死黑色半透明 |

## 4. 核心组件与数据流

公共组件只吃三样东西,全部单向:

| 模型 | 作用 |
| --- | --- |
| `PlayerUiState` | 歌曲信息来自 shared `Song`,加上播放中、缓冲、进度、时长、循环模式与错误文案;**不依赖 Media3 或任何平台播放器**,各端在适配层把自己的播放器枚举转成 `PlayerRepeatMode` |
| `PlayerActions` | 单向事件出口:`onTogglePlaying` / `onSeek` / `onSeekFinished` / `onToggleRepeat` / `onPrevious` / `onNext`;页面业务不通过 UI 组件反向读取播放器对象 |
| `PlayerCapabilities` | 平台按能力裁剪 UI(是否显示上下曲 / 循环 / 进度条),避免 Web 为了复用 UI 被迫实现没有的能力(如下载、队列) |

循环模式是典型的「跨端枚举 + 各端转换」:公共层只有 `PlayerRepeatMode { OFF, ALL, ONE }`,安卓详情页在切换时把它映到 Media3 的 `REPEAT_MODE_OFF/ALL/ONE`;Web 端按自己的播放器转换。公共组件不认识任何一家的播放器。

`PlayerSurface.kt` 里的播放详情部件族:

| 部件 | 职责 |
| --- | --- |
| `PlayerArtworkSlot` | 封面插槽:统一阴影与裁切,内容由调用方注入(安卓是黑胶唱片机,Web 是静态封面) |
| `PlayerSongHeader` | 歌名 + 歌手行,VIP 角标等走 `titleTrailingContent` 一类尾部插槽 |
| `PlayerPlaybackDetails` | 标题、元数据、进度、控制键的纵向组装 |
| `PlayerProgress` | 进度条(苹果风格滑杆),时间换算用 `PlayerUiModels` 的公共函数;时长与区间完整时以红细线(#FF6B6B)标出高潮区间(`refrainStartMs`/`refrainEndMs`,见 [95-playback-refrain.md](95-playback-refrain.md)) |
| `PlayerTransportControls` | 播放/上下曲/循环等传输控制,按 `PlayerCapabilities` 裁剪 |

`SharedSongRow` 的参数即插槽全集:`song`(数据)、`artworkContent`(封面)、`onClick`(整行点击)、`active`(正在播放高亮)、`subtitle` / `downloaded` / `durationLabel`(展示变体)、`supportingContent` 与 `trailingContent`(行内附加内容)。安卓侧 `SongRow` 就是在 `trailingContent` 里补了拖把与更多菜单。

`SharedSongRow` 的关键行为:**不可播的歌(`song.playable == false`)整体降透明度、显示「版权不可播」标记且不响应点击**。判断做在共用组件里而不是让调用方各自处理:服务端从 2026-09 起不再过滤这类歌(与波点 App 行为对齐),**每一处列表都会出现它们**,放这里一次覆盖所有调用方,漏改也不会漏出「点了没反应也没解释」的条目。点击直接禁用而不弹提示——标记就写在行上,用户点之前已看到原因。

## 5. 状态模型里的公共工具

`PlayerUiModels.kt` 里还有几处两端必须一致的纯函数,谁都不许各写一份:

- `normalizedPlayerProgress` / `playerPositionForProgress`:进度条与播放器时间的双向换算,未知时长统一按 0 处理并夹在合法区间。
- `normalizedDisplayText`:列表元数据可能带换行或连续空白,统一压缩成单个空格再展示。

## 6. 各端接入点

| 端 | 接入位置 | 用到什么 |
| --- | --- | --- |
| androidApp | `ui/theme/Theme.kt` | `TaotaoPlayerTheme` 作为全局主题的底座 |
| androidApp | `ui/app/AppContent.kt` | `SharedMainLayout`(底部导航四个标签 + 迷你播放器插槽)+ `SharedMiniPlayer` |
| androidApp | `ui/common/components.kt` | `SongRow` 包装 `SharedSongRow`,平台侧补封面(`AlbumArt`)、拖把与更多菜单、`AlbumArt` 也复用 token 判尺寸 |
| androidApp | `ui/player/PlayerDetailPage.kt` | `PlayerCompactLayout` + `PlayerArtworkSlot`(内容是自绘的黑胶唱片机 `VinylDisc`),快捷操作、顶栏、控制条走插槽 |
| androidApp | 其余页面 | `SharedCard`、`SharedContentState`、`SharedBackButton`、`SharedSectionHeader` 与各 token 散用(搜索页、设置页、资料弹窗、库页都在用) |
| webApp | `src/wasmJsMain/kotlin/com/taotao/music/web/Main.kt` | 分享播放器整页:`TaotaoPlayerTheme` + `PlayerArtworkSlot` + `PlayerCompactLayout` + `SharedContentState`;歌曲模型直接用 shared `Song`,并用 `TaotaoTypography.withFontFamily(...)` 换 Web 字体 |

坑:webApp 是 wasmJs 目标——给公共组件写示例代码或接入逻辑时,不要顺手 import JVM-only 的库;公共层能用的 API 以 commonMain 为界,平台能力进不了这一层。

`PlayerCompactLayout` 的插槽清单(安卓详情页的实际用法):`titleTrailingContent`(VIP 角标)、`metadataTrailingContent`(音质 Chip)、`headerActions`(顶栏收藏)、`quickActions`(下载/分享/加入歌单)、`controlLeadingContent` / `controlTrailingContent`(队列入口等)。页面能力全部从这些口子进来,组件本身不知道「收藏」「下载」是什么。

## 7. 与上下层的分工

```text
androidApp / webApp   ← 业务、平台能力(令牌、播放引擎、下载、图片加载)
        ↓ 依赖
     player-ui        ← 视觉与交互骨架(本模块):主题、token、歌曲行、播放详情、主骨架
        ↓ 依赖
      shared          ← 数据与规则(Song、Lyric、AudioQuality)
```

- 分配一条新 UI 需求时的判断顺序:涉及**业务语义或平台能力**的留在端上;**两个以上页面共用的视觉骨架**进 player-ui;**字段与规则**进 shared。
- 主题的亮暗由调用方判定后传入(`TaotaoPlayerTheme(darkTheme = ...)`),公共组件不自己读系统设置——安卓的外观模式(跟随系统/浅色/深色)是应用级偏好,Web 分享页有自己的判断。

## 8. 设计约定

- **组件参数、回调、状态边界要明确**:状态从页面传入、事件从回调传出;组件内部不持有业务状态、不读全局单例,尽量单向数据流。
- **平台差异一律用插槽**:`artworkContent`、`trailingContent`、`supportingContent`、`headerActions`、`quickActions`、`titleTrailingContent` 这类参数是唯一注入点;禁止在公共组件里 import 平台库或 Coil——封面加载、下载进度、菜单、音量、定时器都由调用方注入。
- **两个以上页面使用的组件必须抽到这里**(AGENTS.md 的模块化规则);抽之前先确认它真的与平台无关。`DragReorderList` 就因为依赖 Android 侧 `LocalReduceMotion` 与弹簧动画设施而**留在 androidApp**,是刻意的反向例子——桌面端要用时把动画设施一起上移,而不是硬塞进公共库。
- **数值全部走 token**:字号、圆角、间距、描边、海拔都有刻度;`SharedSongRow` 的文档明确记录了「此前写死 10dp/14dp/3dp/6dp 被全部换成 token」的历史,新代码不要开倒车。

新增公共组件前按这个检查单走一遍(顺序即优先级):

1. 先确认它真的与平台无关——依赖动画设施、图片加载或持久化状态的组件应该留在端上(参考 `DragReorderList` 的去向说明)。
2. 参数里区分「数据」「回调」「插槽」三类,默认值给足,调用方零配置就能渲染。
3. 字号、圆角、间距全部对齐 token 刻度;新增刻度要先改 token 文件并补 `TaotaoTokensTest`。
4. 补 `commonTest` 用例(纯函数与状态行为),不必拉起 Compose 运行时。
5. 改完过一遍 androidApp 与 webApp 两处调用点,确认亮暗两套主题都不走样。

## 9. 常见坑

- **`SongRow` 的拖把与更多菜单**:androidApp 侧历史上「传了 dragHandle 就隐藏整个更多菜单」,结果播放队列里删除、收藏的回调传了却没有任何入口。现在两者并存渲染,确实没有可用操作时才只显示拖把或什么都不显示;给 `SongRow` 加新槽位时不要复刻旧的吞事件写法。
- **`PlayerArtworkSlot` 的 shape 必须与内容一致**:shape 同时作用于阴影和裁切。历史上固定 20dp 圆角矩形投影 + 圆形封面内容,圆封面背后露出一圈直角矩形阴影带(每边多出约 30dp);默认已是 `CircleShape`,画方封面时才显式传 shape。阴影颜色也随亮暗主题走,不要再写死黑色半透明。
- **`AppleStyleTheme` 已废弃**:圆角规格迁到 `TaotaoShapes`(数值有收敛:ButtonShape 12→14dp、CardShape 32→28dp),只作迁移期别名,新代码禁止引用。
- **surfaceContainer 一族别省**:改配色时漏掉这族,弹层底色会静默变回 M3 基线的紫灰,亮暗两套都要覆盖。
- **改公共组件要过两端调用点**:androidApp 与 webApp 的用法都要看一眼;`PlayerWideLayout` 目前无人调用,动它不影响现网,但也不要顺手删(宽屏端预留)。
- **分区标题别再手写字号**:`SharedSectionHeader` 出现前,页面里散着五种手写的区块标题字号(21/18/17sp…),只有一种落在字号刻度上;现在收敛为 `SharedSectionLevel` 三档,新页面直接取档。
- **封面尺寸同理**:列表封面历史上分档过细,肉眼「说不出哪里不对但就是不整齐」,已收敛为 `artworkRow` 一档给列表用;`artworkGrid` / `artworkHero` 留给网格与大图场景。
