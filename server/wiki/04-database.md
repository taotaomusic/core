# 数据库设计与变更

[返回文档中心](README.md)

## 1. 连接与初始化

数据库连接由 `DatabaseService` 管理：

- 连接池最大 10。
- 空闲连接超时 30 秒。
- 建立连接超时 5 秒。
- 启动时最多重试 10 次，每次间隔 1 秒。
- 监听连接池空闲连接的 `error`，避免未处理事件终止进程。

`DATABASE_URL` 没有默认值，必须以 `postgres://` 或 `postgresql://` 开头。

## 2. 迁移机制

项目没有迁移版本表，使用启动时幂等 DDL：

```text
DatabaseService.onModuleInit
  → 等待 PostgreSQL
  → BEGIN
  → pg_advisory_xact_lock(913720001)
  → CREATE/ALTER/INDEX/约束等幂等 DDL
  → COMMIT
```

适合的变化：

- 新建表。
- 新建索引。
- 增加不破坏旧数据的幂等结构。

需要额外设计的变化：

- 删除或重命名列。
- 修改已有列类型。
- 给已有表增加无默认值的 NOT NULL 列。
- 大批量回填数据。

这类变化不能只写 `CREATE TABLE IF NOT EXISTS`，应先制定兼容和回滚方案。

当前迁移没有版本表，也没有“向下迁移”命令；`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`、回填和
约束补齐都集中在 `src/database/migrations.ts` 的同一事务中。迁移失败会回滚整组 DDL，服务不会
在数据库未完成初始化时开始监听端口。

## 3. 表清单

### `users`

| 字段 | 说明 |
| --- | --- |
| `id` | identity 主键 |
| `username` | 唯一用户名 |
| `password_hash` | scrypt 哈希 |
| `password_salt` | 随机盐 |
| `created_at` | timestamptz |
| `email` | 可空邮箱；部分唯一索引 |
| `nickname` | 可空昵称 |
| `avatar_url` | 可空 HTTPS 头像地址 |
| `disabled_at` | 可空 bigint；禁用时撤销刷新令牌 |
| `im_uid` | 可空 UUID 字符串；部分唯一索引 |

### `refresh_tokens`

只保存刷新令牌 SHA-256 哈希。`consume` 必须保持为单条 `UPDATE ... RETURNING`，避免并发刷新同一令牌时双双成功。

### `favorites`

唯一约束是 `(user_id, source, song_id)`。`song_id` 使用 text，因为客户端收藏模型要求字符串。

`created_at` 是不可变的首次收藏时间。取消收藏不删除行，而是写入 `is_favorite = 0`、
`deleted_at`、`updated_at` 并递增 `revision`；重新收藏只刷新 `favorited_at`，必须继续保留
原来的 `created_at`。普通收藏列表和搜索页批量判断都只查询 `is_favorite = 1`。

### `playlists` 与 `playlist_songs`

`playlists` 以 `(user_id, id)` 归属账号，保存名称、简介、封面、当前 `revision` 和时间戳；
删除用户时通过外键级联删除。`playlist_songs` 以 `(playlist_id, source, song_id)` 唯一标识
歌曲，`position` 在歌单内唯一并建立顺序索引。歌曲标题、歌手、专辑、封面、时长和链接是
可更新的展示快照，不作为身份判断依据。

添加、删除、完整替换和排序都在同一事务中先锁定所属歌单。排序交换位置前先把所有位置
整体平移到临时区，避开 PostgreSQL 立即唯一约束；删除后同样先平移再用窗口函数压紧位置。
排序接口要求键集合完全匹配当前数据库集合，防止旧设备同步时覆盖其它设备刚添加的歌曲。
单歌单最多 5,000 首，由 Repository 和 Controller 双重限制。

### `playback_sessions`

保存客户端按 `(user_id, session_id)` 幂等上报的播放会话累计快照。同一个会话的
`listened_ms`、`qualified`、`completed` 只能向前增长；重复或乱序请求不会重复累计。

### `user_song_stats`

按 `(user_id, source, song_id)` 保存首次/最后播放时间、有效播放次数、完整播放次数、
累计听歌毫秒数。歌曲元信息不入库，最近播放接口只返回来源与歌曲 ID；客户端按 ID 补全
展示数据。最近播放直接查询这张汇总表，不扫描全部播放会话；部分索引
`(user_id, last_history_at DESC, source, song_id) WHERE last_history_at IS NOT NULL` 与筛选和
稳定排序完全一致。

### `playback_history_state`

保存用户清空最近播放时推进的 `cleared_before` 边界。清空操作不删除 `user_song_stats`，
因此听歌次数和累计时长仍然保留，离线设备补传边界之前的旧会话也不会恢复已清空列表。

### `playback_history_clear_operation`

按 `(user_id, marker)` 保存每次清空操作的 `revision`、`cleared_before` 和 `cleared_at`。
它不是冗余日志：客户端清空响应丢失后重试必须返回第一次结果，即使另一台设备已经再次清空，
否则重试会错误地推进第三个代际。

### `app_release`

保存 APK 文件名、大小、sha256、灰度比例、最低 SDK 和发布状态。

`enabled` 是 `smallint` 0/1，不能改成 PostgreSQL boolean。`UNIQUE (channel, version_code)` 用于登记同版本时更新元数据。

### `app_channel`

保存每个发布渠道的 Android `min_supported_version_code`、桌面
`desktop_min_supported_version_code` 和更新时间；两端最低版本独立判断。

### `app_config`

保存按客户端版本范围生效的远程配置。

### `api_key`

```sql
CREATE TABLE api_key (
  id      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  channel text NOT NULL,
  key     text NOT NULL,
  quota   integer NOT NULL DEFAULT 0,
  UNIQUE (channel, key)
);
```

实际迁移还约束 Key 非空、额度非负，并建立 `(channel, quota DESC)` 索引。

### `image_generation_task`

```sql
CREATE TABLE image_generation_task (
  task_id        text PRIMARY KEY,
  prompt         text NOT NULL,
  consumed_quota integer NOT NULL,
  channel        text NOT NULL,
  state          text NOT NULL,
  completed      smallint NOT NULL,
  image_url      text,
  api_key_id     integer NOT NULL REFERENCES api_key(id) ON DELETE RESTRICT
);
```

任务引用 Key 使用 `ON DELETE RESTRICT`，避免删除仍需轮询的 Key。

### `song_share`

保存分享 token、用户、来源、稳定歌曲身份和元数据快照。`token` 为 8–24 位短码，
`(user_id, source, song_id)` 唯一；**不存任何音频文件路径** —— 试听是转发上游音频，不是缓存，
也不能保存上游限时直链（旧库的 `preview_file` 已在迁移里删除）。
`access_count`、`enabled` 和时间字段用于公开分享统计与失效控制。

### `app_announcement`

保存公告标题、正文、启用状态、置顶状态和发布时间。`enabled`/`pinned` 是 `smallint` 0/1，
`idx_app_announcement_visible` 支持公开接口按置顶和发布时间读取。置顶切换由 Repository 的
顾问锁保证同一时间只有一条置顶公告。

### `im_device_session`

保存用户、悟空设备类型、设备 ID 哈希、Token 哈希、过期/撤销和更新时间；主键是
`(user_id, device_flag)`，同类设备新登录覆盖旧凭据。消息正文和同步游标不在本库。

### `app_patch`

Android 补丁按 `(channel, target_version_code, patch_version)` 唯一，保存文件大小、sha256、
灰度比例、启用状态和说明。补丁只对指定宿主 `target_version_code` 有效，不能当成“高版本 APK”处理。

### `desktop_release`、`desktop_jar`、`desktop_patch`

- `desktop_release` 以 `(channel, architecture, version_code)` 唯一，保存入口、灰度、启用和说明。
- `desktop_jar` 保存安装目录相对路径、分类、内容寻址对象名、大小和 sha256；同一发布内路径唯一。
- `desktop_patch` 保存来源版本、路径、源/目标 sha256、算法（`courgette`/`bsdiff`）、对象、大小和启用状态；
  `(release_id, from_version_code, path, algorithm)` 唯一。

桌面最低版本使用 `app_channel.desktop_min_supported_version_code`，Android 继续使用同表中的
`min_supported_version_code`，两者不能混用。

### `admin_users`

```sql
CREATE TABLE admin_users (
  id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  username      text NOT NULL UNIQUE,
  password_hash text NOT NULL,
  password_salt text NOT NULL,
  display_name  text NOT NULL DEFAULT '',
  email         text,
  role          text NOT NULL DEFAULT 'viewer'
                CHECK (role IN ('super_admin', 'admin', 'viewer')),
  totp_secret   text,
  totp_enabled  smallint NOT NULL DEFAULT 0 CHECK (totp_enabled IN (0, 1)),
  ip_whitelist  text,
  last_login_at bigint,
  last_login_ip text,
  disabled_at   bigint,
  created_at    timestamptz NOT NULL DEFAULT now(),
  created_by    integer REFERENCES admin_users(id) ON DELETE SET NULL
);
```

这张表独立于普通 `users`，管理员不是用户、用户也不是管理员，不要试图合并。

- `password_hash` 是 scrypt（`N=65536, r=8, p=1, maxmem=128MB`）的十六进制结果，`password_salt`
  是 16 字节随机盐。校验用 `timingSafeEqual`，不用字符串比较。
- `totp_secret` 非空且 `totp_enabled = 1` 才表示 2FA 真正生效；只有密钥没有确认是中间态。
- `ip_whitelist` 是可空文本，按换行/逗号分隔；为空表示不限制。只做精确匹配，不支持 CIDR。
- `disabled_at` 是可空毫秒时间戳。会话校验每次回表检查它，所以禁用能立即生效。
- `created_by` 是 `ON DELETE SET NULL`：创建者被删掉后账号仍在，只是失去来源信息。

### `admin_sessions`

```sql
CREATE TABLE admin_sessions (
  id         integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  admin_id   integer NOT NULL REFERENCES admin_users(id) ON DELETE CASCADE,
  token_hash text NOT NULL UNIQUE,
  expires_at bigint NOT NULL,
  created_at bigint NOT NULL,
  revoked_at bigint,
  login_ip   text,
  user_agent text
);
```

**只存令牌的 SHA-256 哈希**，明文令牌只在登录响应里出现一次。有效期 24 小时。
`revoked_at` 非空即失效。删除管理员会级联删掉他的全部会话。

改密码调用 `revokeAllExcept(admin_id, keepTokenHash)`，撤销该管理员的其它会话、保留当前这条。

### `admin_audit_log`

```sql
CREATE TABLE admin_audit_log (
  id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  admin_id    integer REFERENCES admin_users(id) ON DELETE SET NULL,
  action      text NOT NULL,
  target_type text,
  target_id   text,
  detail      text,
  ip_address  text,
  user_agent  text,
  created_at  bigint NOT NULL
);
```

`admin_id` **可空**是刻意的：管理员被删除后历史审计必须保留，只是变成“无归属”。写成
`NOT NULL` 且无 `ON DELETE` 动作时，删除管理员会直接撞外键报 23503。

落库前仍统一过 `auditActorId()`：它现在的职责是**防御性收敛** —— 身份缺失或 `id` 不是正整数时
一律写 `null`，不让非法值撞外键。（历史上的另一个原因「`X-Admin-Token` 兼容身份 `id = 0`」
随该通道移除而消失。）

早期版本这两张表建成了 `NOT NULL` + 无 `ON DELETE` 的外键，`CREATE TABLE IF NOT EXISTS`
**不会**修正已存在的表定义。迁移里因此显式补了可重复执行的修正：

```sql
ALTER TABLE admin_audit_log ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_admin_id_fkey;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_admin_id_fkey
  FOREIGN KEY (admin_id) REFERENCES admin_users(id) ON DELETE SET NULL;
ALTER TABLE admin_users DROP CONSTRAINT IF EXISTS admin_users_created_by_fkey;
ALTER TABLE admin_users ADD CONSTRAINT admin_users_created_by_fkey
  FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE SET NULL;
```

这是“给已有表收紧或放宽约束”的通用写法：先 `DROP CONSTRAINT IF EXISTS` 再 `ADD CONSTRAINT`，
整段可重复执行。修改已有表约束时照抄这个模式，不要指望 `CREATE TABLE IF NOT EXISTS`。

迁移文件里的 SQL 注释不能出现反引号 —— 迁移 SQL 写在模板字符串里，反引号会提前终止字符串，
报 `TS1005: ',' expected`。

## 4. 类型规则

### 时间

- `Date.now()`：`bigint`。
- PostgreSQL 原生时间：`timestamptz`。
- 其它普通整数：`integer`。

`pg` 默认把 int8 返回成字符串。本项目注册了 `INT8 → Number` 解析器，前提是 bigint 只保存安全范围内的毫秒时间戳。

### 布尔值

历史数据模型用 `smallint` 0/1。新增与已有逻辑交互的布尔字段继续使用该约定，不要局部改成 boolean。

### 字段别名

PostgreSQL 会把未加引号的标识符折叠成小写：

```sql
-- 错误：返回 songid
SELECT song_id AS songId FROM favorites;

-- 正确：返回 songId
SELECT song_id AS "songId" FROM favorites;
```

所有返回给 TypeScript camelCase 类型的 SQL 别名都必须加双引号。

## 5. Repository 规则

- Repository 只处理 SQL 和行映射，不处理 HTTP Response。
- 参数全部使用 `$1、$2...`，不能拼接用户输入。
- 多行读取使用 `DatabaseService.all`。
- 单行或 `RETURNING` 使用 `first`。
- 写操作只关心影响行数时使用 `run`。
- 需要多条 SQL 原子提交时使用 `DatabaseService.transaction`，回调内不要再自行获取连接。
- 构造函数不能查询数据库。
- 不直接暴露 Pool，避免调用方跨网络请求持有连接。

## 6. 并发 SQL 示例

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

锁只持续当前 SQL 语句，不跨 ApiSweet 网络请求。失败时用另一条 UPDATE 归还额度。

### 并发注册

“先查用户名、再插入”不能防并发，最终依赖 `users.username` 唯一约束。Repository 必须把唯一冲突翻译为 409/4090，不能漏成 502。

## 7. Key 运维 SQL

添加 Key：

```sql
INSERT INTO api_key (channel, key, quota)
VALUES ('GPTIMAGE2', '替换为真实Key', 100)
ON CONFLICT (channel, key)
DO UPDATE SET quota = excluded.quota;
```

安全查看额度：

```sql
SELECT id, channel, quota
FROM api_key
ORDER BY channel, quota DESC, id;
```

追加额度：

```sql
UPDATE api_key
SET quota = quota + 100
WHERE id = 1;
```

不要在终端历史、工单、日志或聊天中执行会回显 `key` 列的查询。

## 8. 数据层验证

数据层变化必须：

1. 使用名称包含 `verify` 或 `test` 的独立数据库。
2. 重置验证库。
3. 重启服务，让迁移重新执行。
4. 运行生产构建。
5. 执行契约验证，并确保脚本输出全部通过。
6. 对新增并发路径做专项并发验证。

重置后不重启服务会得到“关系不存在”，因为建表只在服务启动时运行一次。

## 9. 表结构变更流程

### 新增表

1. 在 `runMigrations` 的同一事务中添加 `CREATE TABLE IF NOT EXISTS`。
2. 同步添加必要的唯一约束、CHECK、外键和索引。
3. 新建 Repository，不让业务层直接拼 SQL。
4. 在独立空库启动一次，验证全新安装。
5. 在带旧表的验证库启动一次，验证升级安装。

### 给已有表新增列

安全的分阶段方式：

```text
第一版：新增 nullable 列或带默认值的列，代码兼容旧值
  → 回填历史数据
第二版：确认无 NULL 后再收紧约束
```

不要直接给大表新增无默认值的 `NOT NULL` 列，也不要假设 `CREATE TABLE IF NOT EXISTS` 会修改已有表定义。

### 删除或重命名字段

先发布同时兼容新旧字段的代码，再迁移数据，最后才能删除旧字段。线上仍有旧后端进程或回滚产物时，立即删除字段会让回滚失效。

### 修改已有列的可空性或外键动作

`CREATE TABLE IF NOT EXISTS` 对已存在的表完全不起作用，`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`
也只能加列、改不了约束。放宽 `NOT NULL` 或换外键动作要用可重复执行的组合：

```sql
ALTER TABLE t ALTER COLUMN c DROP NOT NULL;
ALTER TABLE t DROP CONSTRAINT IF EXISTS t_c_fkey;
ALTER TABLE t ADD CONSTRAINT t_c_fkey FOREIGN KEY (c) REFERENCES other(id) ON DELETE SET NULL;
```

先 `DROP ... IF EXISTS` 再 `ADD` 才能重复执行，否则第二次启动会因为约束已存在而失败。
`admin_audit_log.admin_id` 和 `admin_users.created_by` 就是这么修的。

## 10. 事务边界

应该放在一个数据库事务中的操作：

- 多条 SQL 必须全部成功或全部失败。
- 中间状态不能被其它请求观察。
- 需要行锁保护读取后写入。

不应该放在数据库事务中的操作：

- 腾讯音乐或 ApiSweet HTTP 请求。
- 文件上传和大文件哈希。
- 密码 scrypt 计算。
- 任何可能等待数秒的外部调用。

图片创建因此采用“原子预扣 → 释放数据库锁 → 请求上游 → 失败补偿”的 Saga（补偿事务）方式，而不是跨网络持有事务。

## 11. 图片额度一致性边界

正常路径：

```text
quota 100
  → 原子预扣到 99
  → 上游创建成功
  → 任务落库
  → 最终 99
```

失败路径：

```text
quota 100
  → 原子预扣到 99
  → 上游失败
  → 补偿 UPDATE
  → 恢复 100
```

极端情况：服务在预扣后、补偿前崩溃，本地可能少 1 额度；上游已成功但任务落库失败时，也可能产生无法由客户端轮询的孤儿任务。当前没有分布式事务或预扣流水表，运维需要结合上游账单和任务表人工核对。

建议核对 SQL：

```sql
SELECT api_key_id, count(*) AS task_count, sum(consumed_quota) AS recorded_consumption
FROM image_generation_task
GROUP BY api_key_id
ORDER BY api_key_id;
```

## 12. 索引检查

当前迁移声明的显式索引（主键和 UNIQUE 约束产生的隐式索引未重复列出）：

| 索引 | 服务查询 |
| --- | --- |
| `idx_users_email_unique`（部分唯一） | 邮箱查重和绑定 |
| `idx_users_im_uid_unique`（部分唯一） | 悟空 IM UID 查重 |
| `idx_favorites_user` | 收藏按创建时间读取 |
| `idx_favorites_active_user`（部分） | 当前有效收藏列表和搜索批量判断 |
| `idx_playlists_user_updated` | 歌单按更新时间读取 |
| `idx_playlist_songs_order` | 歌单按 position 读取 |
| `idx_song_share_lookup` | 分享 token/用户歌曲查找 |
| `idx_user_song_stats_recent`（部分） | 最近播放按 last_history_at 排序 |
| `idx_app_release_lookup` | 更新候选版本 |
| `idx_app_announcement_visible` | 公开公告按置顶/发布时间读取 |
| `idx_api_key_available` | 图片 Key 按渠道和额度选择 |
| `idx_image_generation_task_key` | 按 Key 汇总和外键相关操作 |
| `idx_im_device_session_active`（部分） | 查找未撤销且未过期 IM 凭据 |
| `idx_app_patch_lookup` | Android 补丁候选版本 |
| `idx_desktop_release_lookup` | 桌面更新候选版本 |
| `idx_desktop_jar_sha256` | 内容寻址模块引用检查 |
| `idx_desktop_patch_manifest` | 桌面差分按版本/路径查找 |
| `idx_desktop_patch_sha256` | 内容寻址差分引用检查 |
| `idx_admin_sessions_active`（部分） | 按 `admin_id` 查未撤销会话；`WHERE revoked_at IS NULL` |
| `idx_admin_audit_admin` | 审计按管理员和时间倒序读取 |
| `idx_admin_audit_action` | 审计按 action 和时间倒序筛选 |

新增查询先确认过滤列和排序列是否匹配现有索引；不要为低频管理查询盲目增加索引。
