# shared 共享模块

[返回文档中心](README.md)

最后更新:2026-10-01

本篇讲 `shared/` 模块:跨端共享的数据模型、歌词(LRC/YRC)解析与音质规则,以及这些字段与后端接口响应的对应关系。组件层见 [93-player-ui.md](93-player-ui.md),安卓端整体见 [90-client-android.md](90-client-android.md)。本篇不讲网络层——`TencentMusicApi` 等请求实现属于 androidApp,不在这里。

## 1. 定位与边界

- 纯 Kotlin Multiplatform 模块,代码全部在 `shared/src/commonMain/kotlin/com/taotao/music/model/`,包名 `com.taotao.music.model`(Gradle namespace 是 `com.taotao.music.shared`,别混淆)。
- 目标平台:Android + wasmJs 浏览器(见 `shared/build.gradle.kts`)。**共享层不引入浏览器 API,也不引入 Android API**——Web 分享页只复用纯模型和业务规则,这是写进构建脚本注释的约束。
- 模块刻意保持小:三个源文件就覆盖了「歌是什么、歌词怎么解析、有哪些音质」三件事。小不是缺陷——每一行都在两个平台编译、被三端消费,膨胀的代价是双倍的。
- 共享三类东西:`Song` 数据模型、歌词解析、音质档位规则。歌单、播放记录等业务模型**不在** shared,由 androidApp 的 `TencentMusicApi` 内部持有(见第 6 节)。

## 2. 目录结构

```text
shared/src/commonMain/kotlin/com/taotao/music/model/
├── MusicModels.kt    # Song(歌曲)
├── Lyric.kt          # LyricWord / LyricLine / Lyric + LyricParser(LRC、YRC)
└── AudioQuality.kt   # AudioQuality 枚举 + labelOfQuality(上游全档位展示名)

shared/src/commonTest/kotlin/com/taotao/music/model/
├── LyricParserTest.kt
└── AudioQualityTest.kt
```

## 3. `Song` 数据模型

跨端歌曲身份与展示信息的唯一载体,字段与接口的映射:

| 字段 | 含义与契约 |
| --- | --- |
| `title` / `artist` / `album` / `duration` | 展示信息;加歌单时会随快照原样保存(见 33 篇) |
| `subtitle` / `releaseTime` | 补充展示字段,默认空串 |
| `source` | 音乐来源(默认 `"tencent"`)。**腾讯与网易的歌曲 ID 可能相同,所有云端操作都按来源区分**——歌曲身份始终是 `source + songId` |
| `remoteId` | 服务端数字 ID;`0` 表示上游只给了 `mid` |
| `mid` / `type` | 上游 songMID 与歌曲类型,解析播放地址时要**原样带回服务端**;`remoteId` 为 0 的歌缺了 `mid` 就永远拿不到地址 |
| `audioUri` / `coverUri` / `lyricUri` | 播放直链与资源地址,都是限时的;加歌单时展示信息随快照保存,但**播放时仍通过音乐接口重新解析短时直链**,不依赖快照里的旧地址 |
| `lyricWordsUri` | 逐字(yrc)时间轴的**本地**地址,只有离线歌曲用;在线播放的逐字数据随歌词 `?format=json` 一起取,不需要单独地址 |
| `vip` | 上游标记的付费/版权限制,搜索结果直接下发,界面据此加标记 |
| `playable` | **实测能不能拿到播放地址**,与 `vip` 是两件事;`false` 时列表置灰标注、点击不发起播放。酷我上大量正版热门曲(周杰伦全系列等)属于「上游标它付费或实测下线」——搜索**照常列出**,只是不可播。默认 `true`:只有服务端明确下发 `false` 才置灰,缺字段按可播处理 |
| `favorited` | 是否已收藏,搜索结果里由服务端下发的权威值 |
| `refrainStartMs` / `refrainEndMs` | 歌曲高潮区间(毫秒),来自上游 `payInfo.refrain_*` 经服务端透传,搜索与详情解析时保留;缺失为 null,不伪造默认值。进度条红细线标记用,见 [95-playback-refrain.md](95-playback-refrain.md) |
| `localQuality` | 已下载本地文件对应的音质档位;null 表示不是本地文件,或是旧版本下载时没记录 |
| `color` | 封面占位色(Long),无图时的兜底 |

一个 `Song` 对象在客户端有五个构造来源,字段完整度各不相同:搜索结果解析(最全,含 `playable`/`vip`/`favorited`)、歌单快照回放(展示字段与稳定键,直链要重解析)、分享元数据(webApp 侧只给展示字段)、下载落库(带 `localQuality`,地址是本地 `file:`)、以及队列冷启动恢复(`SongCodec` 序列化回来)。给 `Song` 加字段时逐个来源过一遍,别假设每个来源都有值——默认值就是为此存在的。

## 4. 歌词解析(`Lyric.kt`)

- 模型三层:`LyricWord`(逐字单元,`timeMs`/`durationMs`/`text`,一个 text 可以是一个汉字或一个英文单词)→ `LyricLine`(行;`durationMs` 为 0 表示时长未知,`words` 为空表示没有字级时间轴)→ `Lyric`(`synced` 为 false 表示纯文本,不高亮也不可跳转)。
- **`LyricParser.parse(lrc, yrc)` 优先逐字**:yrc 解析成功就用它(同时含行时间和字时间),失败或为空才退回 lrc;两者都没有返回 `Lyric.EMPTY`,界面据此显示「暂无歌词」。
- 支持的脏数据:LRC 一行多时间戳(副歌复用,每个时间戳生成一行)、`[offset:±ms]` 整体平移(yrc 的行时间与字时间同样要平移)、纯文本歌词去元信息标签后按行保留、只有时间没有文字的间奏行丢弃;LRC 本身没有行时长,用下一行的起始时间补出来,供插值用。
- 时间戳格式兼容 `[mm:ss.xx]`、`[mm:ss.xxx]`、`[mm:ss]`,也兼容冒号分隔毫秒的 `[mm:ss:xx]`;小数位数决定单位——一位是十分之一秒、两位是百分之一秒、三位是毫秒,解析结果统一夹非负。
- 三个查询入口:**`hasWords`** 决定界面按逐字还是整行渲染(只要有一行带字级数据就按逐字);**`indexAt(positionMs)`** 二分查当前应高亮的行(避免每帧线性扫描);**`progressOf(lineIndex, positionMs)`** 算某行已唱到的比例——有字级数据按字宽累计插值(长音的字平滑推进而不是整字跳变),没有则按行时长线性插值,连行时长都没有就整行算已唱完。安卓逐字歌词(`LyricPanel.kt`)的高亮裁切宽度就用它。
- 不变量:`Lyric` 的行**按时间升序**返回(`indexAt` 的二分依赖它);「没有歌词」统一用 `Lyric.EMPTY` 表示,界面判 `isEmpty` 即可;`synced=false` 的纯文本歌词同样有行序列,但 `indexAt` 恒返回 -1、不做高亮。
- 取用流程(安卓侧):播放时请求 `GET /api/v1/songs/{id}/lyrics?format=json`(必须显式带 format,服务端默认返回纯文本以兼容旧客户端;旧服务端忽略该参数时会拿到纯 LRC 文本,客户端已兼容)→ 得到 `lrc` / `yrc` 两个字段 → `LyricParser.parse(lrc, yrc)` → 界面按 `hasWords` 选择逐字或整行渲染。
- 坑:YRC 字级时间组是 `(起始ms,时长ms)`(尾部可能还带一个用途不明的数字),匹配时间组的正则**不能**写成「文本 + 时间组」的捕获形式——那样文本捕获组必须排除 `(`,歌词里字面的左括号永远匹配不上而被丢掉(实测「雨爱 - 杨丞琳 (Rainie Yang)」丢过括号)。改动 `YRC_TIMING` 正则前先看源码里这段注释。

## 5. 音质规则(`AudioQuality.kt`)

- `AudioQuality` 枚举只暴露适合当播放档位的 5 档,`value` 就是后端 `quality` 参数的取值,与上游 `/song/info` 返回的 `qualityInfo[].type` 一一对应(已用同一首歌逐档核对):

| 枚举 | value | 展示名 | 无损 |
| --- | --- | --- | --- |
| `STANDARD` | 4 | 标准 | 否 |
| `HIGH` | 8 | HQ 高音质 | 否 |
| `LOSSLESS` | 10 | SQ 无损 | 是 |
| `HIRES` | 11 | Hi-Res | 是 |
| `MASTER` | 14 | 臻品母带 | 是 |

- 上游实际有 0–18 共 19 档,其余的是特殊形态:12 杜比全景声是 mp4 容器、15–17 是 AI 消音与 AI 钢琴的试验档、18 是 nac 私有格式,都不适合放进「默认播放音质」让用户随便选。
- 默认档 `AudioQuality.Default = HIGH`(音质与流量的平衡点,也在后端历史默认值附近);**`of(value)` 对未知取值退回默认档**,避免存进偏好的脏数据让播放彻底不可用。
- 播放音质与下载音质是两个独立偏好(安卓侧分别记忆,见 90 篇的 `QualitySheetKind`),但取值共用这一套枚举——新增档位只改这里,两个偏好同时受益。
- `labelOfQuality(value)` 覆盖上游**全部** 0–18 档的中文名(0 试听、1–2 有损、12 杜比全景声、13 臻品全景声、15–17 AI 试验档、18 NAC 等)。它与枚举分开,是因为**降级后可能落在枚举之外的档位**——枚举管「用户能选什么」,这个函数管「服务端实际给了什么」,界面仍要能说出名字。

## 6. 与后端契约的对应关系

shared 模型是「接口响应 → 客户端 UI」的翻译层,字段命名直接对应 30–37 各篇定义的响应体:

| 模型字段 | 后端来源与语义 | 篇目 |
| --- | --- | --- |
| `Song.playable` / `vip` / `favorited` | 搜索结果逐首下发;服务端**不过滤**不可播歌曲,由客户端置灰并标注 | [32-api-search-music.md](32-api-search-music.md) |
| `Song.mid` / `type` | 播放地址解析必须原样回传,否则 mid-only 歌曲取不到直链 | [32-api-search-music.md](32-api-search-music.md) |
| `AudioQuality.value` | 搜索、播放地址、下载请求的 `quality` 参数;上游不可用时的降级链路见该篇 | [32-api-search-music.md](32-api-search-music.md) |
| 歌词 `?format=json` | 返回 `lrc` / `yrc` 两个字段,喂给 `LyricParser.parse`;不带 format 时是纯文本(旧客户端兼容) | [32-api-search-music.md](32-api-search-music.md) |
| `Song.source` + `remoteId`/`mid` | 云端歌单的歌曲稳定键(`source + songId`,`songId` 可以是 mid);`Song` 的展示字段随歌单快照保存与回放。客户端缺省来源(`DEFAULT_SOURCE = "tencent"`)与服务端对缺失来源的归一化各有一套,拼稳定键时先补默认值再比较 | [33-api-playlists.md](33-api-playlists.md) |
| `Song.lyricWordsUri` | 逐字时间轴只对**离线**歌曲单独落地址;在线播放走歌词接口的 `yrc` 字段,不依赖这个 URI | [32-api-search-music.md](32-api-search-music.md) |
| `Song` 在分享元数据里的投影 | 公开分享页的 `title/artist/coverUrl/duration` 等字段由 webApp 的 `ShareModels.kt` 解析进 `Song` | [35-api-shares.md](35-api-shares.md) |

注意:歌单 `revision`、播放上报的清空代际、播放会话快照等**业务模型不在 shared**——它们目前只被 androidApp 消费,定义在 `androidApp/src/main/java/com/taotao/music/data/TencentMusicApi.kt` 内部。哪天 Web 或其他端要消费这些接口,把模型下沉到 shared,而不是复制粘贴。

shared 与端上网络层的分工线:**shared 只回答「一首歌长什么样、歌词怎么解析、有哪些音质」**;「去哪个域名、带什么令牌、怎么加密、怎么重试」全部属于端上实现(安卓是 `TencentMusicApi` + `CryptoTransport`,Web 分享页是自己的解析与取流)。反向的约束是 shared 不 import 端上任何类,依赖只能从端指向 shared。

## 7. 一首歌从接口到界面

把三个文件串起来看一首歌的完整路径(安卓侧):

1. **搜索**:NDJSON 逐行解析成 `Song`(含 `playable`/`vip`/`favorited`)→ `SongRow` 渲染,不可播置灰。
2. **播放**:带 `mid`/`type` 请求播放地址,`audioUri` 回填后进队列;`color` 兜底封面占位。
3. **歌词**:`?format=json` 拿 `lrc`/`yrc` → `LyricParser.parse` → `LyricPanel` 按 `hasWords` 逐字或整行高亮。
4. **收藏 / 歌单**:稳定键 `source + songId`(或 `mid`)提交服务端;展示字段随快照保存。
5. **下载 / 复听**:`localQuality` 记档位、`lyricWordsUri` 存逐字时间轴本地地址,离线也能逐字渲染。

`AudioQuality` 决定第 2 步请求哪个档位、下载面板显示哪些体积,以及降级落到枚举之外的档位时界面怎么称呼它。

第 3 步还有一条隐藏路径:有时间轴的歌词可以点击跳转(`indexAt` 给出落点,行时间做 seek 依据);纯文本歌词明确没有这条能力——这是 `synced` 字段存在的意义。

## 8. 跨端消费点

| 消费方 | 用法 |
| --- | --- |
| androidApp | 全部 UI 与数据层:`Song` 贯穿搜索、队列、歌单、收藏、下载;歌词面板直接消费 `Lyric`;音质选择与降级提示消费 `AudioQuality`/`labelOfQuality` |
| player-ui | `SharedSongRow` / `PlayerUiState` 直接依赖 `Song`;进度条、迷你播放器展示的时长字段也来自它 |
| webApp | `ShareModels.kt` 的 `parseShareSong` 把分享元数据解析成 `Song`(兼容信封与裸对象两种响应),分享页 UI 用它渲染;分享场景字段不全,靠默认值兜底 |

三端看到的「同一首歌」就是 shared `Song` 里的同一组字段——这是跨端视觉与行为一致的底线,字段含义改了三端一起变。

依赖方向是单向的:`androidApp` / `player-ui` / `webApp` → `shared`,shared 不 import 任何端上模块,player-ui 也不反向进入 shared。要在公共组件里加新字段时,字段定义落在 shared、视觉消费落在 player-ui,两层各改各的。

## 9. 测试规范

- 共享逻辑的测试放 `shared/src/commonTest/`,命名 `*Test.kt`;歌词解析、音质规则这类纯函数改动必须补用例。提交前先跑一遍全量共享测试再接线到端上。现有覆盖:
  - `LyricParserTest`(约 220 行):LRC/YRC 解析、offset 平移、多时间戳、脏数据边界;
  - `AudioQualityTest`:档位与上游取值对应、按值反查、未知取值回退、无损标记、展示名覆盖全部 19 档。
- 测试名可以直接用中文反引号命名(如 `fun `未知取值退回默认档`()`),便于表达业务语义。
- 运行:

```powershell
.\gradlew.bat :shared:allTests
```

测试失败先别碰端上代码:shared 的用例是纯 JVM 断言,失败信息比 Compose 界面直接得多;端上行为异常而共享测试全绿,问题几乎一定在端上的接线层。

- player-ui 与 androidApp 的测试规范见各自篇目与根目录 [AGENTS.md](../../AGENTS.md);共享规则先在 commonTest 验证,再在端上接线。

## 10. 改动守则

- 新增跨端数据模型或纯业务规则先放这里;放进去的前提是**不依赖任何平台 API**——Web 侧参与 commonMain 编译,混进平台调用会在 wasm 编译期直接报错,这是底线而非风格建议。
- 字段语义变化 = 客户端契约变化:改 `Song`、`Lyric`、`AudioQuality` 的任何对外语义前,先核对根目录 [RELEASE.md](../../RELEASE.md) 的契约清单与 30–37 各篇,确认旧版客户端不会被静默破坏。
- 注释里写清「为什么」:枚举为什么只留 5 档、`playable` 与 `vip` 为什么是两件事、YRC 正则为什么不能那样写——这些是踩过坑的结论,重构时不要丢掉。
- 新增字段优先给默认值并写明缺失时的行为(`playable` 缺省按可播、`favorited` 缺省按未收藏),旧版客户端拿不到新字段时不能崩、也不能反向误解语义。
- 一个需求拆三层的示例:`Song.playable` 的**判断规则**放 shared(模型语义),**置灰样式**放 player-ui(`SharedSongRow`),**点击不发请求的拦截**与提示放端上(行为)——每一层只做自己那件事。
- 三个「不要」:
  - **不要在端上复制解析副本**——LRC/YRC、音质名的逻辑只在 shared 有一份,复制一份到某端迟早漂移;
  - **不要把 UI 概念塞进 shared**——`Lyric` 只到「数据 + 查询」为止,渲染方式属于 player-ui / 各端;
  - **不要改字段含义却不动契约文档**——`Song`/`AudioQuality` 的字段名就是接口响应的映射,改动要同步 30–37 对应篇与 `RELEASE.md`。
