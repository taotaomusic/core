# 播放高潮区间(refrain)字段透传与进度条标记

[返回文档中心](README.md)

最后更新:2026-10-02

歌曲高潮区间(refrain)是一条纯透传字段:上游给什么就下发什么,客户端在播放进度条上画出这段范围。本文记录它的完整数据链路、生效条件与各端接入现状。

## 1. 字段与语义

- 上游(酷我系)响应的 `payInfo.refrain_start` / `payInfo.refrain_end`,服务端原样映射为歌曲对象的 `refrainStartMs` / `refrainEndMs`。**上游值本身就是毫秒,服务端只做改名透传,不做任何单位换算**。
- 字段缺失时不伪造值:上游没给,服务端响应里就没有这两个键(JSON 无该字段),客户端解析结果为 `null`。
- 实测歌曲「再见」(酷我 musicId=112051)上游返回 `refrain_start=39482`、`refrain_end=69719`,服务端对应 `refrainStartMs=39482`、`refrainEndMs=69719`。

## 2. 数据链路(逐段实测)

```text
上游 payInfo.refrain_start/end
  → server/src/upstream/bodian.client.ts(酷我源由波点协议承载)
  → server/src/upstream/kuwo.client.ts(酷我适配层透传)
  → server/src/music/song.mapper.ts(/search 响应)
  → shared Song.refrainStartMs/refrainEndMs
  → androidApp TencentMusicApi 解析
  → player-ui PlayerUiState → PlayerSurface 进度条标记
```

逐段说明:

- **上游解析**(`server/src/upstream/bodian.client.ts`):`searchSongs()` 与 `getMusicInfo()` 分别从搜索条目和单曲详情的 `payInfo` 里取 `refrain_start` / `refrain_end`,以 `Number.isFinite(Number(...))` 守卫——非数字一律落 `undefined`,不硬造 0。
- **酷我适配层**(`server/src/upstream/kuwo.client.ts`):酷我源协议不在本文件(见文件头说明,签名加密都在传输层),它把 `BodianClient` 的搜索与详情结果翻译成统一模型时原样带上这两个字段(搜索 `toUpstreamSong`、详情构造 `UpstreamSongInfo` 两处)。
- **其他音源**:
  - `tencent.client.ts`:`UpstreamSong` / `UpstreamSongInfo` 接口**声明了**这两个可选字段,但从未赋值——QQ 音乐源恒缺失,JSON 里不出现。
  - `netease.client.ts` 与 `kpk.util.ts`:完全没有 refrain 字样,网易云源同样不提供。
- **服务端响应**(`server/src/music/song.mapper.ts`):`toSong()` 把 `item.refrainStartMs` / `refrainEndMs` 透传进 `/api/v1/search` 的歌曲对象。注意:**`GET /songs/:id/info` 与 `GET /songs/batch-info` 的响应体目前没有这两个字段**(`music.controller.ts` 的 `songInfo()` 只映射标题/歌手/档位等);详情侧的 refrain 只有客户端解析预留,当前数据实际来自搜索结果随队列带入。
- **共享模型**(`shared/src/commonMain/kotlin/com/taotao/music/model/MusicModels.kt`):`Song` 增加 `refrainStartMs: Long? = null`、`refrainEndMs: Long? = null` 两个可空字段,字段模型见 [94-shared-module.md](94-shared-module.md) §3。
- **客户端解析**(`androidApp/src/main/java/com/taotao/music/data/TencentMusicApi.kt`):搜索结果的 `toSong()` 与详情补全的 `songFromInfo()` 都用 `optLong("refrainStartMs").takeIf { it > 0L }`——字段缺失时 `optLong` 得 0,`takeIf` 落 `null`,与「不伪造」对齐。
- **播放 UI**(`player-ui/src/commonMain/kotlin/com/taotao/music/playerui/`):`PlayerUiState` 默认从 `song` 取这两个字段(适配层无需逐处传递),`PlayerSurface` 把它们交给公共进度条 `PlayerProgress`。状态模型见 [93-player-ui.md](93-player-ui.md) §4。

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
- **样式**:与轨道同高(4dp)的圆头强调色段,颜色取 `MaterialTheme.colorScheme.primary`,随明暗主题走。历史上曾写死 `#FF6B6B` 且比轨道粗(5dp),后改为主题色——把「高潮标记」从「变色的轨道」里区分出来。

## 4. 各端接入现状(实测)

| 端 | 现状 |
| --- | --- |
| Android | **已接入**:搜索结果 → 队列 → `PlayerDetailPage` / `AppContent` 构造 `PlayerUiState` → `PlayerProgress` 画标记 |
| Windows 桌面(desktop/src) | **暂未接入**:整个 `desktop/src` 无任何 refrain 引用,React 侧的歌曲模型不含这两个字段 |
| Web 分享播放器(webApp/src) | **暂未接入**:整个 `webApp/src` 无任何 refrain 引用 |

桌面与 Web 要接入时的最小路径相同:各自的 HTTP 客户端模型补 `refrainStartMs` / `refrainEndMs` 两个可空字段即可,服务端 `/search` 响应已经带(酷我源)。

MV 信息接口见 [32-api-search-music.md](32-api-search-music.md)。

相关篇目:字段模型见 [94-shared-module.md](94-shared-module.md) §3;搜索接口字段见 [32-api-search-music.md](32-api-search-music.md) §3;进度条渲染见 [93-player-ui.md](93-player-ui.md) §4。
