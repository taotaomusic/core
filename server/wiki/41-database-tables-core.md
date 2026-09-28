# 数据表:用户、收藏、歌单、播放与分享

[返回文档中心](README.md)

最后更新:2026-09-27

用户域与播放域的表。字段以 `src/database/migrations.ts` 为准;并发实现见 [44-database-operations.md](44-database-operations.md)。

## 1. `users`

| 字段 | 说明 |
| --- | --- |
| `id` | identity 主键 |
| `username` | 唯一用户名 |
| `password_hash` | scrypt 哈希 |
| `password_salt` | 随机盐 |
| `created_at` | timestamptz |
| `email` | 可空邮箱;部分唯一索引 |
| `nickname` | 可空昵称 |
| `avatar_url` | 可空 HTTPS 头像地址 |
| `disabled_at` | 可空 bigint;禁用时撤销刷新令牌 |
| `im_uid` | 可空 UUID 字符串;部分唯一索引 |

删除用户会级联刷新令牌、收藏、歌单、分享、播放和 IM 设备凭据。

## 2. `refresh_tokens`

只保存刷新令牌 SHA-256 哈希。`consume` 必须保持为单条 `UPDATE ... RETURNING`,避免并发刷新同一令牌时双双成功(见 [44-database-operations.md](44-database-operations.md))。

## 3. `favorites`

唯一约束是 `(user_id, source, song_id)`。`song_id` 使用 text,因为客户端收藏模型要求字符串。

| 字段 | 语义 |
| --- | --- |
| `created_at` | 不可变的首次收藏时间 |
| `is_favorite` | `smallint` 0/1;取消收藏是软删除标记 |
| `deleted_at` / `updated_at` | 取消收藏时写入 |
| `revision` | 每次变化递增 |
| `favorited_at` | 当前轮次的收藏时间;重新收藏时刷新 |
| `mutation_id` | text,可空;客户端 mutation 幂等标识 |

软删除语义:

- 取消收藏不删除行,而是写入 `is_favorite = 0`、`deleted_at`、`updated_at` 并递增 `revision`。
- 重新收藏只刷新 `favorited_at`,必须继续保留原来的 `created_at`。
- 普通收藏列表和搜索页批量判断都只查询 `is_favorite = 1`。

接口契约见 [30-api-conventions.md](30-api-conventions.md) 与 [32-api-search-music.md](32-api-search-music.md)(搜索里的 `favorited` 由一次批量查询填充)。

## 4. `playlists` 与 `playlist_songs`

- `playlists` 以 `(user_id, id)` 归属账号,保存名称、简介、封面、当前 `revision` 和时间戳;删除用户时通过外键级联删除。
- `playlist_songs` 以 `(playlist_id, source, song_id)` 唯一标识歌曲,`position` 在歌单内唯一并建立顺序索引。
- 歌曲标题、歌手、专辑、封面、时长和链接是**可更新的展示快照**,不作为身份判断依据。

添加、删除、完整替换和排序都在同一事务中先锁定所属歌单:

- 排序交换位置前先把所有位置整体平移到临时区,避开 PostgreSQL 立即唯一约束。
- 删除后同样先平移再用窗口函数压紧位置。
- 排序接口要求键集合完全匹配当前数据库集合,防止旧设备同步时覆盖其它设备刚添加的歌曲。
- 单歌单最多 5,000 首,由 Repository 和 Controller 双重限制。

## 5. `playback_sessions`

保存客户端按 `(user_id, session_id)` 幂等上报的播放会话累计快照。同一个会话的 `listened_ms`、`qualified`、`completed` 只能向前增长;重复或乱序请求不会重复累计。

## 6. `user_song_stats`

按 `(user_id, source, song_id)` 保存首次/最后播放时间、有效播放次数、完整播放次数、累计听歌毫秒数。

- 歌曲元信息不入库,最近播放接口只返回来源与歌曲 ID;客户端按 ID 补全展示数据。
- 最近播放直接查询这张汇总表,不扫描全部播放会话。
- 部分索引 `(user_id, last_history_at DESC, source, song_id) WHERE last_history_at IS NOT NULL` 与最近播放的筛选和稳定排序完全一致(见 [44-database-operations.md](44-database-operations.md))。

## 7. `playback_history_state`

保存用户清空最近播放时推进的 `cleared_before` 边界。

- 清空操作不删除 `user_song_stats`,因此听歌次数和累计时长仍然保留。
- 离线设备补传边界之前的旧会话也不会恢复已清空列表。

## 8. `playback_history_clear_operation`

按 `(user_id, marker)` 保存每次清空操作的 `revision`、`cleared_before` 和 `cleared_at`。

它**不是冗余日志**:客户端清空响应丢失后重试必须返回第一次结果,即使另一台设备已经再次清空,否则重试会错误地推进第三个代际。幂等语义见 [34-api-playback.md](34-api-playback.md)。

## 9. `song_share`

保存分享 token、用户、来源、稳定歌曲身份和元数据快照。

- `token` 为 8–24 位短码,`(user_id, source, song_id)` 唯一。
- **不存任何音频文件路径** —— 试听是转发上游音频,不是缓存,也不能保存上游限时直链(旧库的 `preview_file` 列已在迁移里删除)。
- `access_count`、`enabled` 和时间字段用于公开分享统计与失效控制。
- `source` 的 CHECK 已扩到 `('tencent', 'netease', 'kuwo')`。旧库的约束是建表时的旧白名单,`CREATE TABLE IF NOT EXISTS` 改不动它 —— 迁移里用条件 DO 块查 `pg_constraint`,确认旧定义确实不含 `kuwo` 时才 `DROP` 后重建(「修改已有表约束」的第二个实际用例,见 [40-database-overview.md](40-database-overview.md))。
