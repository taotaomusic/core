# 数据库运维:并发 SQL、Key 运维、额度一致性与索引

[返回文档中心](README.md)

最后更新:2026-09-30

本文是数据层的操作手册:并发路径的 SQL 范例、Key 运维 SQL、图片额度一致性边界、索引清单。表结构见 41–43 各篇。

## 1. 需要原子 SQL 的路径

以下路径**不能拆成「先 SELECT、再 UPDATE」**,也不要跨网络请求持有数据库连接:

- 刷新令牌消费:单条 `UPDATE ... RETURNING`。
- 图片 Key 配额:锁定候选 Key 后单条 `UPDATE ... RETURNING`。
- 并发注册:依赖数据库唯一约束兜底。
- 发布版本登记:依赖 `ON CONFLICT DO UPDATE`。
- 播放清空 marker:在 `playback_history_clear_operation` 的主键冲突上保持幂等。
- 播放会话上报:同一事务里先取「用户×历史」与「用户×会话」两把顾问锁,再 `FOR UPDATE` 读旧快照,最后 `ON CONFLICT (user_id, session_id) DO UPDATE` 只累加增长量。
- 公告置顶:使用独立顾问锁串行化「取消旧置顶 + 设置新置顶」。

## 2. 并发 SQL 范例

### 刷新令牌消费

消费必须保持为单条 `UPDATE ... RETURNING`。拆成 SELECT + UPDATE 会产生竞态:同一令牌被两台设备并发使用时双双成功。

### Key 原子扣额

```sql
WITH candidate AS (
  SELECT id
  FROM api_key
  WHERE channel = $1 AND quota >= $2
  ORDER BY quota DESC, id
  FOR UPDATE
  LIMIT 1
)
UPDATE api_key AS target
SET quota = target.quota - $2
FROM candidate
WHERE target.id = candidate.id
RETURNING target.id, target.key;
```

锁只持续当前 SQL 语句,不跨 ApiSweet 网络请求。失败时用另一条 UPDATE 归还额度。

### 并发注册

「先查用户名、再插入」不能防并发,最终依赖 `users.username` 唯一约束。Repository 必须把唯一冲突翻译为 409/4090,不能漏成 502。

### 播放会话上报与清空

两个事务都用同一把「账号×历史」顾问锁串行,防止清空刚推进 `revision` 时另一事务仍按旧代际把会话写成可见:

```sql
SELECT pg_advisory_xact_lock(hashtextextended($1, 0)); -- key: '<userId>:playback-history'
```

会话上报在此之上再按 `(userId, sessionId)` 取一把会话锁(两个实例同时收到同一离线会话的重试时,避免双方都读到「尚不存在」而把完整时长重复累加),随后 `SELECT ... FOR UPDATE` 旧快照、计算增量、`INSERT ... ON CONFLICT DO UPDATE`。清空侧则在锁内先查 `playback_history_clear_operation` 命中同一 marker 就直接返回原结果。

## 3. Key 运维 SQL

添加 Key:

```sql
INSERT INTO api_key (channel, key, quota)
VALUES ('GPTIMAGE2', '替换为真实Key', 100)
ON CONFLICT (channel, key)
DO UPDATE SET quota = excluded.quota;
```

安全查看额度:

```sql
SELECT id, channel, quota
FROM api_key
ORDER BY channel, quota DESC, id;
```

追加额度:

```sql
UPDATE api_key
SET quota = quota + 100
WHERE id = 1;
```

**不要在终端历史、工单、日志或聊天中执行会回显 `key` 列的查询。** 新增 Key 应 INSERT 新行,不要覆盖旧行的 Key 内容(原因见 [50-feature-image-generation.md](50-feature-image-generation.md) 的轮换一节)。

## 4. 图片额度一致性边界

正常路径:

```text
quota 100
  → 原子预扣到 99
  → 上游创建成功
  → 任务落库
  → 最终 99
```

失败路径:

```text
quota 100
  → 原子预扣到 99
  → 上游失败
  → 补偿 UPDATE
  → 恢复 100
```

极端情况:服务在预扣后、补偿前崩溃,本地可能少 1 额度;上游已成功但任务落库失败时,也可能产生无法由客户端轮询的孤儿任务。当前没有分布式事务或预扣流水表,运维需要结合上游账单和任务表人工核对。

建议核对 SQL:

```sql
SELECT api_key_id, count(*) AS task_count, sum(consumed_quota) AS recorded_consumption
FROM image_generation_task
GROUP BY api_key_id
ORDER BY api_key_id;
```

## 5. 索引清单

当前迁移声明的显式索引(主键和 UNIQUE 约束产生的隐式索引未重复列出):

| 索引 | 服务查询 |
| --- | --- |
| `idx_users_email_unique`(部分唯一) | 邮箱查重和绑定 |
| `idx_users_im_uid_unique`(部分唯一) | 悟空 IM UID 查重 |
| `idx_favorites_user` | 收藏按创建时间读取 |
| `idx_favorites_active_user`(部分) | 当前有效收藏列表和搜索批量判断 |
| `idx_playlists_user_updated` | 歌单按更新时间读取 |
| `idx_playlist_songs_order` | 歌单按 position 读取 |
| `idx_song_share_lookup` | 分享 token/用户歌曲查找 |
| `idx_user_song_stats_recent`(部分) | 最近播放按 last_history_at 排序 |
| `idx_app_release_lookup` | 更新候选版本 |
| `idx_app_announcement_visible` | 公开公告按置顶/发布时间读取 |
| `idx_api_key_available` | 图片 Key 按渠道和额度选择 |
| `idx_image_generation_task_key` | 按 Key 汇总和外键相关操作 |
| `idx_im_device_session_active`(部分) | 查找未撤销且未过期 IM 凭据 |
| `idx_app_patch_lookup` | Android 补丁候选版本 |
| `idx_admin_sessions_active`(部分) | 按 `admin_id` 查未撤销会话;`WHERE revoked_at IS NULL` |
| `idx_admin_audit_admin` | 审计按管理员和时间倒序读取 |
| `idx_admin_audit_action` | 审计按 action 和时间倒序筛选 |
| `idx_music_source_account_identity`(部分唯一) | 同一音源同一凭据 uid 去重;`WHERE uid <> ''` 跳过未登录占位行 |
| `idx_music_source_account_enabled` | 播放链路按音源取启用账号 |
| `idx_open_api_key_enabled` | 开放 API Key 按 `enabled, created_at DESC` 读取 |

新增查询先确认过滤列和排序列是否匹配现有索引;不要为低频管理查询盲目增加索引。
