# 云端歌单契约

[返回文档中心](README.md)

最后更新:2026-09-29

歌单是多端同步的核心:所有顺序操作都在事务内锁定歌单,顺序变更要求键集合与服务端完全一致。表结构细节见 [41-database-tables-core.md](41-database-tables-core.md) 的 `playlists` 一节;并发实现见 [44-database-operations.md](44-database-operations.md)。跨平台功能边界另见项目根目录 `MUSIC_CROSS_PLATFORM.md`。

## 1. 基本语义

- 所有歌单接口都需要 `Authorization: Bearer <accessToken>`,成功响应遵循统一信封。
- 歌曲身份由 `source + songId` 组成,`songId` 传**字符串**以兼容数字 ID 与上游 `mid`。
- 服务端同时保存展示快照(标题、歌手、专辑、封面、时长),快照可更新但**不作为身份判断依据**。
- 歌单详情返回按 `position` 升序的歌曲数组。
- 目前 `source` 允许 `tencent`、`netease` 和 `kuwo`。当腾讯歌曲的数字 `id` 为 `0` 或缺失时,必须传非空 `mid`;服务端以 `mid` 代替无效数字 ID 建立稳定键。

## 2. 接口清单

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/playlists` | 当前账号的歌单摘要 |
| `POST` | `/api/v1/playlists` | 创建歌单,JSON `name` 必填 |
| `GET` | `/api/v1/playlists/{id}` | 歌单详情与歌曲 |
| `PATCH`/`PUT` | `/api/v1/playlists/{id}` | 更新名称、简介或封面 |
| `DELETE` | `/api/v1/playlists/{id}` | 删除歌单,返回 204 |
| `POST` | `/api/v1/playlists/{id}/songs` | 添加/更新一首歌曲快照 |
| `DELETE` | `/api/v1/playlists/{id}/songs/{source}/{songId}` | 移除歌曲 |
| `PATCH`/`PUT` | `/api/v1/playlists/{id}/songs/order` | 用完整键列表调整顺序(`songs`、`songIds` 或 `order`) |
| `PUT` | `/api/v1/playlists/{id}/songs` | 完整替换歌曲集合 |

## 3. revision 与排序一致性

- 歌单每次发生资料、歌曲或顺序变化都会递增 `revision`。
- 排序要求提交的集合与服务端当前集合**完全一致**,否则返回 `400/4004`;客户端应重新读取详情后重试。服务端不会静默合并 —— 这是防止旧设备同步时覆盖其它设备刚添加的歌曲。
- 单歌单上限为 **5,000 首**,由 Repository 和 Controller 双重限制。
- 歌单不存在或不属于当前账号返回 404/4045(不区分两种情况,避免探测他人歌单)。

## 4. 客户端对接要点

- 本地排序操作后先提交 order,失败(400/4004)就重新拉取详情再对账,不要在本地维护「乐观 revision」。
- `songId` 永远以字符串传输与存储;客户端把数字 ID 直接当 number 用会在 `mid` 场景(腾讯数字 ID 缺失)下出错。
- 多端并发添加同一首歌时,`(playlist_id, source, song_id)` 唯一约束保证只落一行,表现为「添加成功但只出现一次」。

## 5. 封面兜底与读取路径自愈

- 列表与详情接口的 `coverUrl` 都是读取期计算:`COALESCE(歌单自身 cover_url, 歌单内按 position 顺序第一张非空歌曲封面)`(见 `playlists.repository.ts` 的 `PLAYLIST_COLUMNS`),客户端不需要逐首拉详情补图。
- 早期客户端加歌时没存封面,存量快照 `cover_url` 整列为空时兜底无图可取。两条修复路径,**口径一致、互不冲突(都只写仍为空的行,幂等,不动 revision 与 updated_at)**:
  - **读取路径自愈**(`playlists-cover.service.ts`,随读取自动发生):列表接口给「整单无封面」的歌单回源首曲封面(同步部分受 1.2s 预算约束,超出转后台);详情接口给缺封面的曲目逐首回源(1.5s 预算,超出后台补,下次打开可见)。上游请求进程内串行 + 250ms 限流 + 单次 3s 超时,失败静默留空等下次再试,绝不影响接口响应。
  - **一次性全量工具**(`node dist/tools/backfill-playlist-covers.js`,支持 `--dry-run` / `--playlist=N`):在部署目录执行,批量补齐所有空快照。自愈上线后只在想跳过渐进过程时才需要它。
- 新增音源时两条路径都走 `MusicSourceRegistry` 分派,不需要改代码。

