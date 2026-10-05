# 播放高潮区间(refrain)字段透传与进度条标记

[返回文档中心](README.md)

最后更新:2026-10-05

歌曲高潮区间(refrain)是一条纯透传字段:上游给什么就下发什么,客户端在播放进度条上画出这段范围。本文记录它的完整数据链路、生效条件与各端接入现状。

## 1. 字段与语义

- 上游(酷我系)响应的 `payInfo.refrain_start` / `payInfo.refrain_end`,服务端原样映射为歌曲对象的 `refrainStartMs` / `refrainEndMs`。**上游值本身就是毫秒,服务端只做改名透传,不做任何单位换算**。
- 字段缺失时不伪造值:上游没给,服务端响应里就没有这两个键(JSON 无该字段),客户端解析结果为 `null`。分享快照同理:song_share 表里落 `NULL`,metadata 响应里两个键**一起缺席**,不许 0 占位。
- 实测歌曲「再见」(酷我 musicId=112051)上游返回 `refrain_start=39482`、`refrain_end=69719`,服务端对应 `refrainStartMs=39482`、`refrainEndMs=69719`。

## 2. 数据链路(逐段实测)

```text
上游 payInfo.refrain_start/end
  → server/src/upstream/bodian.client.ts(酷我源由波点协议承载)
  → server/src/upstream/kuwo.client.ts(酷我适配层透传)
  → server/src/music/song.mapper.ts(/search 响应)
  → server/src/music/music.controller.ts(/songs/:id/info 与 /songs/batch-info 响应)
  → shared Song.refrainStartMs/refrainEndMs
  → androidApp TencentMusicApi 解析(搜索 toSong 与详情 songFromInfo 两处)
  → player-ui PlayerUiState → PlayerSurface 进度条标记

分享链路(同源透传):
  → server/src/shares/song-share.service.ts(create 时从 requestSongInfo 取值落快照)
  → song_share 表 refrain_start_ms / refrain_end_ms 两列
  → GET /public/shares/:token metadata(非 null 才下发)
  → webApp ShareModels 解析 → PlayerUiState → 复用 player-ui 的 PlayerProgress
```

逐段说明:

- **上游解析**(`server/src/upstream/bodian.client.ts`):`searchSongs()` 与 `getMusicInfo()` 分别从搜索条目和单曲详情的 `payInfo` 里取 `refrain_start` / `refrain_end`,以 `Number.isFinite(Number(...))` 守卫——非数字一律落 `undefined`,不硬造 0。
- **酷我适配层**(`server/src/upstream/kuwo.client.ts`):酷我源协议不在本文件(见文件头说明,签名加密都在传输层),它把 `BodianClient` 的搜索与详情结果翻译成统一模型时原样带上这两个字段(搜索 `toUpstreamSong`、详情构造 `UpstreamSongInfo` 两处)。
- **其他音源**:
  - `tencent.client.ts`:`UpstreamSong` / `UpstreamSongInfo` 接口**声明了**这两个可选字段,但从未赋值——QQ 音乐源恒缺失,JSON 里不出现。
  - `netease.client.ts` 与 `kpk.util.ts`:完全没有 refrain 字样,网易云源同样不提供。
- **服务端响应**(`server/src/music/song.mapper.ts` 与 `music.controller.ts`):`/api/v1/search`、`GET /songs/:id/info`、`GET /songs/batch-info` 三个通道都下发这两个字段,缺失即键不存在。
- **分享链路**(`server/src/shares/`):创建分享时服务端自己调 `requestSongInfo` 解析歌曲,快照把区间一并落进 `song_share.refrain_start_ms / refrain_end_ms`(integer 可空,其他音源为 NULL);`GET /public/shares/:token` 的 metadata 在非 null 时才下发 `refrainStartMs` / `refrainEndMs`。契约脚本 `server/tools/verify-contract.mjs` 对此有三项检查:非酷我源两键一起缺席、酷我分享按下发正毫秒且与搜索透传值一致、短链可创建。
- **共享模型**(`shared/src/commonMain/kotlin/com/taotao/music/model/MusicModels.kt`):`Song` 的 `refrainStartMs: Long? = null`、`refrainEndMs: Long? = null` 两个可空字段,字段模型见 [94-shared-module.md](94-shared-module.md) §3。
- **客户端解析**(`androidApp/src/main/java/com/taotao/music/data/TencentMusicApi.kt`):搜索结果的 `toSong()` 与详情补全的 `songFromInfo()` 都用 `optLong("refrainStartMs").takeIf { it > 0L }`——字段缺失时 `optLong` 得 0,`takeIf` 落 `null`,与「不伪造」对齐。
- **持久化**(`androidApp/src/main/java/com/taotao/music/data/SongCodec.kt`):队列/收藏缓存/播放历史/MediaMetadata extras 共用的编解码器以 0 为哨兵把两个字段一并落盘(与网络解析同一套 `> 0` 判定),冷启动恢复、收藏缓存与本地历史因此都保留区间;旧版本缓存没有这两个键时 `optLong` 返回 0,自然落 `null`,无需迁移。回归测试见 `androidApp/src/test/java/com/taotao/music/data/SongCodecTest.kt`。
- **客户端补拉**(对标行为:只在**当前播放这一首**缺区间时补一次,失败静默):
  - `TaotaoAppState.playSong`:播放入队时对缺区间的歌调 `requestSongInfoForPlayback` 补齐并回写队列;
  - `PlayerDetailPage`:详情页发现区间缺失时补拉,拉到后经 `onRefrainResolved` 回调 `TaotaoAppState.applyResolvedRefrain` 回写队列并落盘——只回填缺失值,上游没给时不把已有值覆盖成 null;
  - 桌面端 `desktop/src/state/AppState.tsx`:开始播放时对缺区间的歌调 `/songs/:id/info` 补一次并更新状态(歌单/最近播放走服务端快照、本身不带区间,补拉正是为它们兜底)。
- **播放 UI**(`player-ui/src/commonMain/kotlin/com/taotao/music/playerui/`):`PlayerUiState` 默认从 `song` 取这两个字段(适配层无需逐处传递),`PlayerSurface` 把它们交给公共进度条 `PlayerProgress`;后者把区间换算成归一化坐标后传给 `AppleStyleSlider`(`theme/AppleStyleComponents.kt`)在轨道内自绘,不再有叠在滑块上层的独立 Canvas。状态模型见 [93-player-ui.md](93-player-ui.md) §4。

## 3. 生效条件与边界

`PlayerProgress`(`PlayerSurface.kt`)的绘制判定:

```kotlin
val start = (refrainStartMs ?: -1L).toFloat() / durationMs.coerceAtLeast(1L)
val end = (refrainEndMs ?: -1L).toFloat() / durationMs.coerceAtLeast(1L)
if (start in 0f..1f && end > start) { /* 画区间 */ }
```

- **必须两个条件同时成立**:歌曲时长有效且区间完整(`start` 与 `end` 都有值、`start` 落在 [0,1] 且 `end > start`)。任一字段缺失(负数兜底)、`end` 不大于 `start`、或时长无效导致比例越界,都不画。
- `end` 按比例夹到 1.0 为上限,区间尾部不会画出轨道。
- **不影响 seek**:拖动进度经 `playerPositionForProgress(progress, durationMs)` 换算,用的始终是**整首时长**,与高潮区间无关;区间标记只是轨道上的一段覆盖,不参与进度计算。
- **样式(2026-10-05 重做)**:高潮段不再是叠在滑块上层的 Canvas,而由 `AppleStyleSlider`(`theme/AppleStyleComponents.kt`)在自绘轨道里分层绘制——自下而上依次是「底轨(onSurface 20%)→ 高潮段(primary)→ 已播段(onSurface 实色)」。播放/拖动进度从高潮段**上面扫过**,高潮段只预告尚未播到的部分,不再是压住播放进度的贴片。更早历史:曾写死 `#FF6B6B` 且比轨道粗(5dp),后改主题色同高,再改成分层自绘。
- **按压动画(iOS 式)**:按住拖动期间整条轨道 4dp→7dp 变粗、拇指放大 1.35 倍,松手回弹(spring、无弹跳)。按压状态取自 Slider 回调:`onValueChange` 即视为按下,`onValueChangeFinished`(拖动结束与点击跳转都会调)即视为松开。track 自绘在固定高度画布上做线宽动画,不触发每帧重布局。
- **进入高潮段的即时反馈**:播放头/拇指落在区间内时,该段从 55% 透明度的 primary 点亮为实色——拖动或播放经过高潮段时有「进入」的确认感,离开即回落。
- **可点跳转标签**:进度条下的「高潮 mm:ss–mm:ss」从纯文字改为可点 chip(badge 圆角、12% primary 底、▶ 图标),点击连调 `onSeek(refrainStartMs)` + `onSeekFinished()` 直接跳到高潮起点——安卓端 `onSeek` 只暂存目标位置、`onSeekFinished` 才真正 seek,所以必须连调;webApp 的 `onSeekFinished` 是默认空实现,同样兼容。标签显示条件与轨道标记一致(区间归一化起点落在 [0,1] 内)。
- **迷你播放器**(`SharedMiniPlayer`)只有一条细进度线,不画区间标记;标记只在详情页与播放器大布局里出现。
- **分享播放器的坐标系**:webApp 进度条分母是 60 秒试听时长(`durationMs = previewDurationSeconds × 1000`),但 `positionMs` 与高潮区间本就是同一套整曲毫秒坐标(试听守的正是整曲前 60 秒),所以区间原始值直接落轨道、无需换算;高潮整体落在试听窗口之外(起点比例 > 1)时按收敛规则不画;2026-10-05 起跳转标签与轨道标记同条件,窗外同样不显示——标签是跳转入口,点了跳不过去就不该出现。

## 4. 各端接入现状(实测)

| 端 | 现状 |
| --- | --- |
| Android | **已接入**:搜索结果 → 队列 → `PlayerDetailPage` / `AppContent` 构造 `PlayerUiState` → `PlayerProgress` 自绘标记(含按压动画与进入点亮);进度条下有可点跳转标签,点击直达高潮起点;入队与详情页双补拉、`SongCodec` 持久化,冷启动恢复不再丢区间 |
| Windows 桌面(desktop/src) | **已接入**:`Song` 类型带两个可空字段,搜索结果直接渲染;播放时对缺区间的歌(歌单/最近播放来的快照)调 info 接口补拉;`PlayerBar` 画标记,`PlayerDetail` 加「高潮 mm:ss–mm:ss」小字 |
| Web 分享播放器(webApp/src) | **已接入**:分享快照落库、metadata 下发,`ShareModels` 解析后经 `PlayerUiState` 复用 player-ui 的 `PlayerProgress`,与 Android 同一套绘制与交互;高潮在 60 秒试听窗外时标记与跳转标签均不显示 |

相关篇目:字段模型见 [94-shared-module.md](94-shared-module.md) §3;搜索接口字段见 [32-api-search-music.md](32-api-search-music.md) §3;进度条渲染见 [93-player-ui.md](93-player-ui.md) §4;分享接口见 [35-api-shares.md](35-api-shares.md)。
