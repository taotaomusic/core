# 数据表:管理后台、凭据与图片任务

[返回文档中心](README.md)

最后更新:2026-09-30

管理域(admin 三表)、开放 Key、音源账号与图片任务的表。管理链路见 [80-admin-auth-login.md](80-admin-auth-login.md) 与 [81-admin-roles-audit.md](81-admin-roles-audit.md);图片任务见 [50-feature-image-generation.md](50-feature-image-generation.md);音源账号见 [53-feature-music-sources.md](53-feature-music-sources.md)。

## 1. `admin_users`

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

这张表独立于普通 `users`,管理员不是用户、用户也不是管理员,**不要试图合并**。

- `password_hash` 是 scrypt(`N=65536, r=8, p=1, maxmem=128MB`)的十六进制结果,`password_salt` 是 16 字节随机盐。校验用 `timingSafeEqual`,不用字符串比较。
- `totp_secret` 非空且 `totp_enabled = 1` 才表示 2FA 真正生效;只有密钥没有确认是中间态。
- `ip_whitelist` 是可空文本,按换行/逗号分隔;为空表示不限制。只做精确匹配,不支持 CIDR。
- `disabled_at` 是可空毫秒时间戳。会话校验每次回表检查它,所以禁用能立即生效。
- `created_by` 是 `ON DELETE SET NULL`:创建者被删掉后账号仍在,只是失去来源信息。
- `must_change_password`(`smallint` 0/1,默认 0,只有启动时自动创建的默认管理员置 1)与 `auth_source`(`local`/`ldap`,默认 `local`)两列由迁移以 `ADD COLUMN IF NOT EXISTS` 补齐,语义见 [80-admin-auth-login.md](80-admin-auth-login.md)。

## 2. `admin_sessions`

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

- **只存令牌的 SHA-256 哈希**,明文令牌只在登录响应里出现一次。有效期 24 小时(`SESSION_LIFETIME_MS`)。
- `revoked_at` 非空即失效。
- 删除管理员会级联删掉他的全部会话。
- 改密码调用 `revokeAllExcept(admin_id, keepTokenHash)`,撤销该管理员的其它会话、保留当前这条(体验取舍见 [82-admin-routes-data.md](82-admin-routes-data.md))。

## 3. `admin_audit_log`

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

- `admin_id` **可空是刻意的**:管理员被删除后历史审计必须保留,只是变成「无归属」。写成 `NOT NULL` 且无 `ON DELETE` 动作时,删除管理员会直接撞外键报 23503。
- 落库前仍统一过 `auditActorId()`:它现在的职责是**防御性收敛** —— 身份缺失或 `id` 不是正整数时一律写 `null`,不让非法值撞外键。
- `detail` 是 JSON 字符串,只放结构化摘要,不放长文本(例如公告正文不入审计)。

### 早期建表缺陷的迁移修正(可复用范例)

早期版本 `admin_audit_log.admin_id` 与 `admin_users.created_by` 建成了 `NOT NULL` + 无 `ON DELETE` 的外键,`CREATE TABLE IF NOT EXISTS` **不会**修正已存在的表定义。迁移里因此显式补了可重复执行的修正:

```sql
ALTER TABLE admin_audit_log ALTER COLUMN admin_id DROP NOT NULL;
ALTER TABLE admin_audit_log DROP CONSTRAINT IF EXISTS admin_audit_log_admin_id_fkey;
ALTER TABLE admin_audit_log ADD CONSTRAINT admin_audit_log_admin_id_fkey
  FOREIGN KEY (admin_id) REFERENCES admin_users(id) ON DELETE SET NULL;
ALTER TABLE admin_users DROP CONSTRAINT IF EXISTS admin_users_created_by_fkey;
ALTER TABLE admin_users ADD CONSTRAINT admin_users_created_by_fkey
  FOREIGN KEY (created_by) REFERENCES admin_users(id) ON DELETE SET NULL;
```

这是「给已有表收紧或放宽约束」的通用写法:先 `DROP CONSTRAINT IF EXISTS` 再 `ADD CONSTRAINT`,整段可重复执行。照抄这个模式,不要指望 `CREATE TABLE IF NOT EXISTS`。

迁移文件里的 SQL 注释不能出现反引号 —— 迁移 SQL 写在模板字符串里,反引号会提前终止字符串,报 `TS1005: ',' expected`。

## 4. `open_api_key`

只存 `sha256(key)`,明文 key 只在创建响应里返回一次;列表接口只回传 `keyPrefix`。生命周期与管理接口见 [36-api-open.md](36-api-open.md)。

## 5. `music_source_account`

音源账号凭据(酷我/波点等需要登录态的音源),由管理后台维护、播放链路按 `source` 取用。

| 字段 | 说明 |
| --- | --- |
| `id` | identity 主键 |
| `source` | 音源标识,`CHECK (source ~ '^[a-z0-9_-]{2,32}$')` |
| `label` / `phone` / `remark` | 展示与备注文本,默认空串 |
| `token` / `uid` | 登录凭据 |
| `enabled` | `smallint` 0/1(沿用全库布尔约定) |
| `last_status` | `CHECK (last_status IN ('unknown', 'ok', 'invalid'))`,最近一次探测结果 |
| `last_error` / `last_note` | 最近一次失败原因 / 探测到的真实音质(如「mp3 128kbps」) |
| `last_checked_at` | 可空 bigint |
| `created_at` / `updated_at` | bigint 毫秒时间戳,非空 |

- 全表**无外键**:凭据生命周期完全由应用层管理(音源下线不牵连用户数据)。
- 部分唯一索引 `(source, uid) WHERE uid <> ''` 保证同一音源同一份登录凭据只有一条;uid 为空表示尚未登录的占位记录,被索引跳过。
- **token 与 uid 只写不读**:管理接口一律只回掩码手机号,明文既不进响应体也不进审计表。任何把它们写进审计 `detail` 或查询结果的改动都是泄漏。

## 6. `api_key`

```sql
CREATE TABLE api_key (
  id      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  channel text NOT NULL,
  key     text NOT NULL,
  quota   integer NOT NULL DEFAULT 0,
  UNIQUE (channel, key)
);
```

实际迁移还约束 Key 非空、额度非负,并建立 `(channel, quota DESC)` 索引。当前唯一渠道是 `GPTIMAGE2`;扣额与轮换见 [50-feature-image-generation.md](50-feature-image-generation.md) 与 [44-database-operations.md](44-database-operations.md)。

## 7. `image_generation_task`

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

- 任务引用 Key 使用 `ON DELETE RESTRICT`,避免删除仍需轮询的 Key(删除被引用 Key 返回 404/4042)。
- `state` 默认 `'IN_PROGRESS'`,CHECK 限定 `IN_PROGRESS` / `COMPLETED` / `FAILED` 三种取值;`completed` 是 `smallint` 0/1 布尔约定;`consumed_quota` 必须 > 0。
- 当前**没有 `user_id` 字段**:知道合法 taskId 的任意已登录用户都能查询该任务;需要归属隔离必须先加用户外键并在创建和查询两条路径同时校验。
- 状态机与轮询语义见 [50-feature-image-generation.md](50-feature-image-generation.md)。
