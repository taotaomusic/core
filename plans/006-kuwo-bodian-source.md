# 006 — 接入酷我「波点」音源

- **状态**：TODO
- **严重度**：中（增量音源，不改动 QQ / 网易云既有链路）
- **类别**：音源接入、上游抽象解耦、跨端契约、数据库约束
- **预计范围**：6 个阶段；服务端为主，桌面端改动小，Android 需先决策是否暴露入口
- **前置产物**：`server/bodian/index.ts`（反编译波点 App 后复刻的传输层客户端，当前不在构建范围内）

## 结论摘要

传输层**已经跑通**，但**不能直接接**。实测结论：

1. **搜索、音频直链、逐字歌词、封面全部可用**，无需登录。签名算法（`kuwotest` 盐 + URL 骨架 md5）复刻正确。
2. **音质只有一个档次**。未登录状态下请求 `flac` / `zp` / `320k` 会被**静默降级**成同一个 128k mp3 文件（响应里的 `bitrate` 字段如实写 128）。项目现有的 0–18 音质档位模型对酷我无意义。
3. **正版热门曲大量不可播**。`七里香`(94237)、`告白气球`(7149583)、`兰亭序`(440616) 等一律返回 `code 20012 歌曲已下线`；能播的多是伴奏、Remix、翻唱和网友改编。搜「稻香」首屏是 `稻香 (原版伴奏)`、`稻香 (Remix摇滚版)`。
4. **歌词有编码缺陷**。LRCX 是 **GBK** 编码，现有实现按 UTF-8 解，中文全部变成 `\uFFFD`。
5. **架构上有一个硬阻塞**。现有「多音源」是 3 处 `source === "netease" ? netease : tencent` 三元表达式硬编码的，**默认分支是 QQ**。直接加第三个源，所有未改到的三元都会把酷我 ID 发给 QQ 上游，且不会报错。

所以接入的正确顺序是：**先修传输层缺陷 → 再抽音源分派 → 最后才接酷我**。

## 实测数据

探针脚本直接调用 `server/bodian/index.ts`，逐项验证（脚本已用完删除）。

### 可用性

| 能力 | 结果 | 备注 |
| --- | --- | --- |
| `searchSongs` | 可用 | 关键字含 `&` / 空格时**静默返回错误结果**，不报错 |
| `getAudioUrl` | 可用 | 主机 `bd-er.kuwo.cn` / `bd-lv.kuwo.cn` |
| 直链 https | 可用 | `http → https` 改写后 206，`content-type: audio/mp4`，首字节 `ftypmp42` 合法 |
| Range | 支持 | 单主机、无重定向，可直接复用 `StreamService.proxy` |
| `getLrcxLyrics` | 可用但乱码 | GBK 内容按 UTF-8 解码 |
| `getLyrics` | 不稳定 | 同一 ID 首次返回 `null`、复测成功；无重试 |
| `getMusicInfo` | 对已下线歌返回 `null` | 可用歌曲的信息含 `lrc_info.lrcx`，可预判有无逐字歌词 |

### 音质实测（歌曲 51685507）

| 请求档位 | 返回 format | 返回 bitrate | 返回 size | 实际 URL |
| --- | --- | --- | --- | --- |
| `aac / 48kaac` | aac | 48 | 1.65Mb | `…C200003….m4a` |
| `mp3 / 128k` | mp3 | 128 | 3.41Mb | `…M500003….mp3` |
| `mp3 / 320k` | mp3 | 128 | 3.41Mb | **与上一行完全相同** |
| `flac / flac` | mp3 | 128 | 3.41Mb | **与上一行完全相同** |
| `zp / 20000` | mp3 | 128 | 3.41Mb | **与上一行完全相同** |

搜索结果里的 `audios` 数组**声明**了 320k / zp / mgg / dtsx 等档位，但 `payInfo.feeType.vip = "1"`，未登录拿不到。**因此不能把 `audios` 当作可用档位下发**，否则客户端选了无损只会静默拿到 128k。

### 搜索结果可用字段

`index.ts` 目前只取了 `id / songName / artist / album`，丢弃了大量有用字段：

- `albumPic` — 封面，`https://img3.kuwo.cn/...`（客户端 `coverUrl` 是必需字段，必须补上）
- `duration` — 秒
- `subtitle` — 副标题（如「《声生不息》综艺」）
- `audios` — 声明档位（见上，不可信）
- `payInfo.listen_fragment` — **实测与「拿不到地址」严格对应**，可用于搜索阶段预筛
- `online` / `preOnline` / `cannotOnlinePlay` — 实测全部为正常值，**无法**识别已下线

预筛验证（搜「七里香」，8 条样本）：

| id | 歌名 | `listen_fragment` | 音频可取 |
| --- | --- | --- | --- |
| 301742217 | 七里香 (DJ阿智 remix) | 0 | 是 |
| 359481604 | 七里香 (cover: 何灵珑) | 0 | 是 |
| **94236** | 借口 | **1** | **否** |
| 6239982 | 七里香 | 0 | 是 |
| **94232** | 止战之殇 | **1** | **否** |

`listen_fragment === "1"` 与不可播完全吻合，且 `nplay === "111111111111"` 同样吻合。样本仅 8 条，实现时需用更大样本复核。

## 接入前必须修的缺陷

都在 `server/bodian/index.ts`，**属于传输层自身的 bug，与接入方式无关**：

1. **LRCX 编码**（`getLrcxLyrics`，L380-381）：`xorDecode` 之后必须按 **GBK** 解码。当前 `.toString('utf-8')` 让所有中文歌词变成乱码。服务端已有 `iconv-lite` 可用。
2. **参数未编码**（`signedGet`，L152-156）：`${k}=${v}` 直接拼接。关键字含 `&` 会切坏 query 并**静默返回别的歌**（实测传「A & B 测试」返回 3 条无关结果，不报错）；含空格/中文时签名基于未编码串、实际发送的是编码后 URL，签名可能对不上。应改用 `URLSearchParams` 并让签名与实际发送的 URL 一致。
3. **下载文件名清洗**（`downloadSong`，L468）：`[^a-zA-Z0-9 -_.]` 会把中文歌名全替换成 `_`，`晴天 - 周杰伦.flac` → `__ - __.flac`，大量歌曲互相覆盖。应只过滤 `\/:*?"<>|` 等非法字符。
4. **流与容错**（`download`，L438-457）：不处理背压、`end()` 不 await、异常分支泄漏 fd、不创建目标目录。
5. **无超时**：所有 `fetch` 都没有 timeout，上游挂起会永久阻塞。

另外 `server/bodian/index.ts` **不在 `server/tsconfig.json` 的 `include`（`src/**/*.ts`）里**，不会被 `tsc` 编译，目前是游离代码。接入时必须挪进 `server/src/`。

## 现有音源抽象的瓶颈

`UpstreamModule` 只注册了两个 provider，而「选哪个上游」的逻辑散落在 3 个地方，全部是硬编码三元表达式：

```
server/src/music/search.service.ts:46     itemSource === "netease" ? this.netease : this.upstream
server/src/music/stream.service.ts:79     source === "netease" ? this.netease : this.upstream
server/src/music/music.controller.ts:89   if (selectedSource === "netease") { … }
music.controller.ts:185 / 247             source === "netease" ? … : …
server/src/shares/song-share.service.ts:39 / 148 / 199
```

两个客户端也**没有共同接口**，签名还不一致：`NeteaseClient.requestLyric(id)` 收的是 `number`，`TencentClient.requestLyric(key)` 收的是 `{id, mid}`；`resolveLink` 一个 2 参一个 4 参。

后果很具体：`music.controller.ts:299` 的 `sourceOf()` 一旦放行 `"kuwo"`，`link` / `info` / `lyrics` 三个端点都会落到 `else` 分支，**把酷我 ID 当成 QQ ID 发出去**。`sourceOf` 的默认值也正是 `"tencent"`。这类错误不报错、不进日志，只表现为「这首歌没声音」。

`playlists.controller.ts:26` 的注释已经预判了这一点：

> 歌单项必须能被当前音乐路由解析；新增来源时先补齐 music 模块和双端客户端。

## 目标

- 把「选上游」从三元表达式收敛成一个注册表，新增音源只需注册一个 provider。
- 让 `BodianClient` 保持为纯传输层（签名、加密、解码），领域映射交给独立的适配器。
- 接入酷我后，QQ / 网易云的搜索、播放、歌词、收藏、歌单行为**完全不变**。
- 酷我音质按实际返回值如实上报，不虚标。
- 桌面端可显式选酷我；Android 的默认聚合搜索行为需要先决策。

## 非目标

- 不改 `StreamService.proxy` 的转发实现（酷我直链单主机、支持 Range，现有实现可直接复用）。
- 不重构客户端 `source` 的传递方式 —— 客户端已把它当**不透明字符串**（`source.ifBlank { "tencent" }`、`"$source:$id"` 复合键），不需要改成枚举。
- 不为酷我实现音质选择器。它只有一档，做了也是假的。
- 不引入短信登录。见「待决策」。

## 接入点清单

### 服务端

| 文件 | 位置 | 改动 |
| --- | --- | --- |
| `upstream/bodian.client.ts` | 新增 | 由 `server/bodian/index.ts` 迁入并修复 5 处缺陷 |
| `upstream/music-source.client.ts` | 新增 | `MusicSourceClient` 接口 + `MusicSourceRegistry` |
| `upstream/kuwo.client.ts` | 新增 | Nest 适配器，实现接口，做领域映射 |
| `upstream/upstream.module.ts` | L7-8 | 注册 `KuwoClient` 与注册表 |
| `music/search.service.ts` | L9 / L42 / L46 | 类型扩三值、聚合列表、改走注册表 |
| `music/song.mapper.ts` | L34 / L71 | `source` 类型扩三值 |
| `music/stream.service.ts` | L79 | 改走注册表 |
| `music/music.controller.ts` | L89 / L185 / L247 / L299 | 改走注册表；`sourceOf` 放行 `kuwo` |
| `config/app-config.service.ts` | L153 | 媒体转发白名单加 `bd-er.kuwo.cn`、`bd-lv.kuwo.cn` |
| `database/migrations.ts` | L141 | `song_share` 的 `source` CHECK 加 `kuwo` |
| `shares/song-share.controller.ts` | L57 | 白名单加 `kuwo` |
| `shares/song-share.service.ts` | L16 / L39 / L148 / L199 | 类型与分派 |
| `shares/song-share.repository.ts` | L7 | 类型 |
| `playlists/playlists.controller.ts` | L26 | `SUPPORTED_SOURCES` 加 `kuwo` |
| `tools/verify-contract.mjs` | L510 / L569 | 补酷我用例；`source: "unknown"` 断言应保持拒绝 |

**媒体白名单要精确列举，不要放 `*.kuwo.cn` 通配**：`bd-api.kuwo.cn` 是 API 主机，`img3.kuwo.cn` 是图床，通配会把它们一并纳入转发范围，等于开了一个任意 URL 代理。CDN 主机名是 `bd-<区域>.kuwo.cn` 模式，新增区域需补白名单，建议在代码里写明。

`song_share` 的约束是建表时内联写的，Postgres 自动命名为 `song_share_source_check`；对已有库 `CREATE TABLE IF NOT EXISTS` 不会重跑，必须单独 `ALTER`，写法沿用同文件 L505-509 的 `DROP … IF EXISTS` + `ADD`。

### 客户端

| 端 | 文件 | 改动 |
| --- | --- | --- |
| 桌面 | `DesktopShell.kt:605` | 加 `SourceChip("酷我", "kuwo", …)` |
| 桌面 | `DesktopShell.kt:1302` | `sourceLabel()` 加 `"kuwo" -> "酷我"` |
| 桌面 | `DesktopMusicApi.kt:712`、`DesktopPlayer.kt:484`、`Main.kt:1153/1241/1336/2056` | 这些是 `source.equals("netease")` 的音质特判，酷我会落到 QQ 分支，需逐条确认 |
| Web | `ShareModels.kt:34` | 已是不透明字符串，**无需改动** |
| Android | `TencentMusicApi.kt:205` | 搜索不传 `source` → 服务端默认 `all`，**酷我会自动混入 Android 搜索结果** |

Android 这一点是最大的行为风险：没有音源选择 UI，一旦服务端把酷我加进聚合搜索，Android 用户会立刻在结果里看到一批伴奏 / Remix / 翻唱。缓解手段是搜索阶段用 `listen_fragment` 预筛不可播项，但**内容质量问题筛不掉**。

## 分阶段执行

### 阶段 0 — 修传输层（独立提交，可单独验证）

修上述 5 处缺陷，把文件迁到 `server/src/upstream/bodian.client.ts`，补中文注释说明 `parseLrcx` 里的 `offsetFactor / offset2Factor` 经验值来源。此阶段不改任何调用方。

验证：搜索中文关键字、含 `&` 的关键字、取音频直链、取 LRCX 并确认中文不再是 `\uFFFD`。

### 阶段 1 — 抽音源分派（纯重构，行为不变）

- 新增 `MusicSourceClient` 接口：`readonly source`、`searchSongs`、`requestSongInfo`、`resolveLink`、`requestLyric`，签名统一成 `{id?, mid?}` 入参。
- `NeteaseClient.requestLyric` 需要一层薄适配（当前收 `number`）。
- 新增 `MusicSourceRegistry`，把 3 处三元表达式全部替换。
- `MusicSource` 类型扩成三值，但**聚合列表暂时仍只放 `tencent` 和 `netease`**。

验证：`verify-contract.mjs` 必须与改动前**同样全绿**，行为零变化。

### 阶段 2 — 接入酷我

- `KuwoClient implements MusicSourceClient`，映射到现有领域模型：
  - `searchSongs` → `UpstreamSong`（补 `cover = albumPic`、`interval = duration`、`subtitle`），丢弃 `listen_fragment === "1"` 的条目
  - `requestSongInfo` → `UpstreamSongInfo`，`tiers` **只声明实测可取的两档**，`songMID` 留空
  - `resolveLink` → 用响应里的 `bitrate` / `format` **回填真实档位**，让 `/link` 的 `fallback` 正确置位
  - `requestLyric` → 把 LRCX 逐字数据转换成客户端已有的 `yrc` 格式（`[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`），客户端零改动
- 音质入参映射：`quality >= 10` → `mp3/128k`，否则 `aac/48kaac`。
- 白名单、`sourceOf` 放行。
- 聚合搜索是否纳入酷我 —— 见「待决策」，建议先只允许显式 `source=kuwo`，观察一段时间再决定是否进 `all`。

### 阶段 3 — 持久化与分享

`song_share` 约束迁移 + 两处白名单 + 契约用例。收藏、歌单、播放历史、听歌统计四张表的 `source` 是自由文本（无 CHECK），**不需要迁移**。

### 阶段 4 — 客户端

桌面加音源 chip 与 label，逐条审查音质特判；Android 是否暴露入口见「待决策」。

### 阶段 5 — 验证

`server/tools/verify-contract.mjs` 全绿（检查项数量会随脚本变化，以实际输出为准），双端手测搜索 / 播放 / 歌词 / 收藏 / 歌单 / 分享。

## 风险

- **内容质量**：接入后酷我带来的主要是伴奏、Remix、翻唱。如果产品预期是「补全 QQ 缺失的正版曲库」，实际收益可能远低于预期 —— 建议阶段 2 结束后先做一次人工抽样，再决定是否进聚合搜索。
- **静默降级**：酷我不报错、只降级。如果不按响应里的 `bitrate` 回填，客户端会把 128k 当无损存进下载记录。
- **默认分支陷阱**：阶段 1 若漏改任何一处三元，酷我会被当成 QQ。建议阶段 1 完成后 `grep '"netease"'` 复查一遍，确认只剩白名单校验和类型定义。
- **无超时**：阶段 0 若只修编码不修超时，上游挂起会拖住整个搜索（`Promise.allSettled` 要等最慢的那个）。

## 待决策

1. **是否引入登录态**。未登录只有 128k mp3 / 48k aac，且正版曲大量下线；`sendSms` / `loginSms` 链路已在传输层实现但**从未实测**。若要走登录，需要定：token 存哪、多用户如何隔离、过期怎么续、风控怎么处理。这直接决定这个音源的最终价值。
2. **聚合搜索是否纳入酷我**。纳入则 Android 立刻可见（无选择器），不纳入则只有桌面能用到。
3. **Android 是否加音源选择 UI**。桌面已有三 chip，Android 目前完全没有。
4. **是否支持分享酷我歌曲**。需要改 `song_share` 的 CHECK 约束并跑迁移，是本次改动里唯一动数据库的部分。
5. **`listen_fragment` 预筛是否可信**。当前样本仅 8 条，需要在实现时用更大样本复核，否则会误杀正常歌曲。
