# 数据表:用户、收藏、歌单、播放、分享与头像

[返回文档中心](README.md)

最后更新:2026-09-30

用户域与播放域的表(含头像二进制与 IM 设备凭据)。字段以 `src/database/migrations.ts` 为准;并发实现见 [44-database-operations.md](44-database-operations.md)。

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

删除用户会级联刷新令牌、收藏、歌单、歌单曲目、分享、播放会话/统计/清空状态、IM 设备凭据和头像二进制。

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

- `history_revision` 是会话**首次落库时固化**的清空代际,之后不可变:旧设备在别的设备清空后补传,统计照常累计,但不会把已清空的会话复活回最近列表。
- 上报路径用 `(user_id, session_id)` 的会话锁 + `playback_history_state` 行锁串行,见 [44-database-operations.md](44-database-operations.md)。

## 6. `user_song_stats`

按 `(user_id, source, song_id)` 保存首次/最后播放时间、有效播放次数、完整播放次数、累计听歌毫秒数。

- 歌曲元信息不入库;最近播放接口从这张表读取,除来源与歌曲 ID 外还带统计字段(次数、时长),仍不含标题、歌手等展示元信息,客户端按 ID 补全展示数据。
- `last_history_at` 只有单次会话听满 3 秒才更新,误触后立刻切歌不会顶到列表最前;`last_history_revision` 是它所属的清空代际,与 `playback_history_state.revision` 比较决定可见性,**不再以客户端墙钟和清空时间戳比较**。
- 部分索引 `(user_id, last_history_at DESC, source, song_id) WHERE last_history_at IS NOT NULL` 与最近播放的筛选和稳定排序完全一致(见 [44-database-operations.md](44-database-operations.md))。

## 7. `playback_history_state`

保存用户清空最近播放时推进的 `cleared_before` 边界与权威代际 `revision`。

- `clear_marker` 记录最近一次清空请求的幂等标识;但幂等重试的依据是 `playback_history_clear_operation` 表(每个 marker 一条),不是这列。
- 清空操作不删除 `user_song_stats`,因此听歌次数和累计时长仍然保留。
- 离线设备补传边界之前的旧会话也不会恢复已清空列表。

## 8. `playback_history_clear_operation`

按 `(user_id, marker)` 保存每次清空操作的 `revision`、`cleared_before` 和 `cleared_at`。

它**不是冗余日志**:客户端清空响应丢失后重试必须返回第一次结果,即使另一台设备已经再次清空,否则重试会错误地推进第三个代际。幂等语义见 [34-api-playback.md](34-api-playback.md)。

## 9. `song_share`

保存分享 token、用户、来源、稳定歌曲身份和元数据快照。

- `token` 为 8–24 位短码,`(user_id, source, song_id)` 唯一。
- `refrain_start_ms` / `refrain_end_ms`(integer 可空):创建分享时从上游快照落库的高潮区间毫秒值,只有酷我源会有值;NULL 即上游没给,metadata 下发时两键一起缺席,不伪造 0。
- **不存任何音频文件路径** —— 试听是转发上游音频,不是缓存,也不能保存上游限时直链(旧库的 `preview_file` 列已在迁移里删除)。
- `access_count`、`enabled` 和时间字段用于公开分享统计与失效控制。
- `source` 的 CHECK 已扩到 `('tencent', 'netease', 'kuwo')`。旧库的约束是建表时的旧白名单,`CREATE TABLE IF NOT EXISTS` 改不动它 —— 迁移里用条件 DO 块查 `pg_constraint`,确认旧定义确实不含 `kuwo` 时才 `DROP` 后重建(「修改已有表约束」的第二个实际用例,见 [40-database-overview.md](40-database-overview.md))。

## 10. `user_avatars`

头像二进制。此前转存第三方图床,图床可用性与外链寿命都不可控,改为服务器自存。

| 字段 | 说明 |
| --- | --- |
| `id` | **下载 token**,128 位随机十六进制,同时是主键 |
| `user_id` | 所属用户,`NOT NULL UNIQUE`,`ON DELETE CASCADE` |
| `content_type` | 上传时按文件魔数嗅探出的 MIME |
| `bytes` | 图片字节,`bytea` |
| `byte_size` | 字节数(上传上限 5 MiB) |
| `created_at` | bigint 毫秒时间戳 |

- 存 Postgres `bytea` 而不是磁盘文件:运行时镜像无状态(没有挂卷),容器更新不能丢用户数据。
- **覆盖式保存**(`ON CONFLICT (user_id) DO UPDATE`):每次上传生成新 token 并覆盖旧行,一名用户只保留最新一张;token 变化即 URL 变化,客户端与代理对旧地址的缓存天然失效,也不会堆积历史头像。
- 下载走公开的 `GET /files/avatars/:token`(匿名、不可枚举),响应带不可变缓存头;清除头像(`PATCH /auth/profile` 传 `avatarUrl: null`)会删掉整行,不留孤儿。上传契约见 [31-api-auth-user.md](31-api-auth-user.md)。

## 11. `im_device_session`

悟空 IM 的连接凭据。消息正文和同步游标由悟空 IM 保存;本库只保存业务账号与设备凭据的可撤销映射。

- 主键 `(user_id, device_flag)`:悟空 IM 当前按 `device_flag` 管理同类设备 Token,一名用户同一类设备只保留最新一份凭据,新的 Android 登录会使旧 Android 设备重连失败。
- 只存 `token_hash` 与 `device_id_hash`,不存明文;`revoked_at` 非空即失效。
- 部分索引 `(expires_at) WHERE revoked_at IS NULL` 服务过期凭据清理与有效凭据查找。接口契约见 [51-feature-wukongim.md](51-feature-wukongim.md)。
