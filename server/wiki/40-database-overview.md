# 数据库总则:连接、迁移、类型与事务

[返回文档中心](README.md)

最后更新:2026-09-27

改任何数据层代码前先读本文;各表的字段语义在 41–43 各篇,运维 SQL 与索引在 [44-database-operations.md](44-database-operations.md)。

## 1. 连接与初始化

数据库连接由 `DatabaseService` 管理:

- 连接池最大 10。
- 空闲连接超时 30 秒。
- 建立连接超时 5 秒。
- 启动时最多重试 10 次,每次间隔 1 秒。
- 监听连接池空闲连接的 `error`,避免未处理事件终止进程。

`DATABASE_URL` 没有默认值,必须以 `postgres://` 或 `postgresql://` 开头(校验见 [21-configuration.md](21-configuration.md))。

不能跨上游 HTTP 请求持有 `pool.connect()` 得到的连接,否则慢上游会耗尽连接池。`DatabaseService` 只暴露五个方法,不直接暴露 Pool:

| 方法 | 用途 |
| --- | --- |
| `first` | 取第一行 |
| `all` | 取多行 |
| `run` | 执行写操作并返回影响行数 |
| `transaction` | 在同一连接上执行带提交/回滚的事务回调;歌单排序、发布清单替换和需要行锁的多步写入必须使用它 |
| `ping` | 健康探测 |

## 2. 迁移机制

项目没有迁移版本表,使用启动时幂等 DDL:

```text
DatabaseService.onModuleInit
  → 等待 PostgreSQL
  → BEGIN
  → pg_advisory_xact_lock(913720001)
  → CREATE/ALTER/INDEX/约束等幂等 DDL
  → COMMIT
```

- 迁移失败会回滚整组 DDL,服务不会在数据库未完成初始化时开始监听端口。
- 没有「向下迁移」命令;`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`、回填和约束补齐都集中在 `src/database/migrations.ts` 的同一事务中。
- 多实例同时启动依赖顾问锁串行建表。

适合直接写进迁移的变化:新建表、新建索引、增加不破坏旧数据的幂等结构。

### 表结构变更流程

**新增表**:

1. 在 `runMigrations` 的同一事务中添加 `CREATE TABLE IF NOT EXISTS`。
2. 同步添加必要的唯一约束、CHECK、外键和索引。
3. 新建 Repository,不让业务层直接拼 SQL。
4. 在独立空库启动一次,验证全新安装。
5. 在带旧表的验证库启动一次,验证升级安装。

**给已有表新增列**:安全的分阶段方式——

```text
第一版:新增 nullable 列或带默认值的列,代码兼容旧值
  → 回填历史数据
第二版:确认无 NULL 后再收紧约束
```

不要直接给大表新增无默认值的 `NOT NULL` 列,也不要假设 `CREATE TABLE IF NOT EXISTS` 会修改已有表定义。

**删除或重命名字段**:先发布同时兼容新旧字段的代码,再迁移数据,最后才能删除旧字段。线上仍有旧后端进程或回滚产物时,立即删除字段会让回滚失效。

**修改已有列的可空性或外键动作**:`CREATE TABLE IF NOT EXISTS` 对已存在的表完全不起作用,`ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 也只能加列、改不了约束。放宽 `NOT NULL` 或换外键动作要用可重复执行的组合:

```sql
ALTER TABLE t ALTER COLUMN c DROP NOT NULL;
ALTER TABLE t DROP CONSTRAINT IF EXISTS t_c_fkey;
ALTER TABLE t ADD CONSTRAINT t_c_fkey FOREIGN KEY (c) REFERENCES other(id) ON DELETE SET NULL;
```

先 `DROP ... IF EXISTS` 再 `ADD` 才能重复执行,否则第二次启动会因为约束已存在而失败。`admin_audit_log.admin_id` 和 `admin_users.created_by` 就是这么修的(实例见 [43-database-tables-admin.md](43-database-tables-admin.md));`song_share.source` 的 CHECK 扩白名单用条件 DO 块查 `pg_constraint` 后重建,是同一思路的另一个用例。

**迁移 SQL 注释不能出现反引号** —— 迁移 SQL 写在模板字符串里,反引号会提前终止字符串,报 `TS1005: ',' expected`。

## 3. 类型规则

### 时间与整数

- `Date.now()` 语义:`bigint`(毫秒时间戳)。
- PostgreSQL 原生时间:`timestamptz`。
- 其它普通整数(版本、大小、百分比):`integer`。

`pg` 默认把 int8 返回成字符串。本项目注册了 `INT8 → Number` 解析器,前提是 bigint 只保存安全范围内的毫秒时间戳。

### 布尔值

历史数据模型用 `smallint` 0/1(`enabled`、`completed`、`pinned` 等)。新增与已有逻辑交互的布尔字段继续使用该约定,不要局部改成 boolean。

### 字段别名

PostgreSQL 会把未加引号的标识符折叠成小写:

```sql
-- 错误:返回 songid
SELECT song_id AS songId FROM favorites;

-- 正确:返回 songId
SELECT song_id AS "songId" FROM favorites;
```

所有返回给 TypeScript camelCase 类型的 SQL 别名都必须加双引号。

## 4. Repository 规则

- Repository 只处理 SQL 和行映射,不处理 HTTP Response。
- 参数全部使用 `$1、$2...`,不能拼接用户输入。
- 多行读取使用 `DatabaseService.all`;单行或 `RETURNING` 使用 `first`;写操作只关心影响行数时使用 `run`。
- 需要多条 SQL 原子提交时使用 `DatabaseService.transaction`,回调内不要再自行获取连接。
- 构造函数不能查询数据库(时序见 [13-startup-lifecycle.md](13-startup-lifecycle.md))。
- 唯一约束冲突要在 Repository 翻译成业务码(如 409/4090),不能漏成 502。

## 5. 事务边界

应该放在一个数据库事务中的操作:

- 多条 SQL 必须全部成功或全部失败。
- 中间状态不能被其它请求观察。
- 需要行锁保护读取后写入。

不应该放在数据库事务中的操作:

- 腾讯音乐或 ApiSweet HTTP 请求。
- 文件上传和大文件哈希。
- 密码 scrypt 计算。
- 任何可能等待数秒的外部调用。

图片创建因此采用「原子预扣 → 释放数据库锁 → 请求上游 → 失败补偿」的 Saga(补偿事务)方式,而不是跨网络持有事务(细节见 [44-database-operations.md](44-database-operations.md) 与 [50-feature-image-generation.md](50-feature-image-generation.md))。

## 6. 数据层验证

数据层变化必须(操作细节见 [22-contract-verification.md](22-contract-verification.md)):

1. 使用名称包含 `verify` 或 `test` 的独立数据库。
2. 重置验证库。
3. 重启服务,让迁移重新执行。
4. 运行生产构建。
5. 执行契约验证,并确保脚本输出全部通过。
6. 对新增并发路径做专项并发验证。

重置后不重启服务会得到「关系不存在」,因为建表只在服务启动时运行一次。
