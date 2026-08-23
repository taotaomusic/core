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
  → pg_advisory_xact_lock
  → CREATE TABLE/INDEX IF NOT EXISTS
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

## 3. 表清单

### `users`

| 字段 | 说明 |
| --- | --- |
| `id` | identity 主键 |
| `username` | 唯一用户名 |
| `password_hash` | scrypt 哈希 |
| `password_salt` | 随机盐 |
| `created_at` | timestamptz |

### `refresh_tokens`

只保存刷新令牌 SHA-256 哈希。`consume` 必须保持为单条 `UPDATE ... RETURNING`，避免并发刷新同一令牌时双双成功。

### `favorites`

唯一约束是 `(user_id, source, song_id)`。`song_id` 使用 text，因为客户端收藏模型要求字符串。

### `app_release`

保存 APK 文件名、大小、sha256、灰度比例、最低 SDK 和发布状态。

`enabled` 是 `smallint` 0/1，不能改成 PostgreSQL boolean。`UNIQUE (channel, version_code)` 用于登记同版本时更新元数据。

### `app_channel`

保存每个发布渠道的最低支持版本号。

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
5. 执行 88 项契约验证。
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

当前关键索引：

| 索引 | 服务查询 |
| --- | --- |
| `users(username)` 唯一 | 登录、注册查重 |
| `refresh_tokens(token_hash)` 唯一 | 刷新令牌消费 |
| `favorites(user_id, source, song_id)` 唯一 | 收藏写入和批量判断 |
| `idx_app_release_lookup` | 更新候选版本 |
| `idx_api_key_available` | 图片 Key 按渠道和额度选择 |
| `image_generation_task(task_id)` 主键 | 轮询任务 |
| `idx_image_generation_task_key` | 按 Key 汇总和外键相关操作 |

新增查询先确认过滤列和排序列是否匹配现有索引；不要为低频管理查询盲目增加索引。
