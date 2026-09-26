# 故障排查

[返回文档中心](README.md)

## 1. 排查顺序

1. 查看服务是否监听目标端口。
2. 请求 `/health`。
3. 查看启动日志中的数据库状态。
4. 确认请求是否经过正确域名、反向代理和路径前缀。
5. 根据 HTTP 状态码和业务码定位模块。
6. 只在独立验证库复现数据层问题。

不要第一时间重置数据库或修改线上数据。

## 2. 服务无法启动

### 缺少 `DATABASE_URL`

表现：启动阶段直接报缺少数据库连接串。

处理：检查 `.env`、`ENV_FILE` 和进程管理器环境变量。连接串必须以 `postgres://` 或 `postgresql://` 开头。

### PostgreSQL 未就绪

表现：日志每秒输出一次连接失败，10 次后退出。

处理：

- 确认 PostgreSQL 服务状态。
- 检查端口、防火墙、用户名、密码和数据库名。
- 确认 systemd 启动顺序。
- 让进程管理器负责重启，不要把重试改成无限循环。

### NestJS 注入为 undefined

表现：启动时出现 `Cannot read properties of undefined` 或 Provider 构造参数为空。

原因：使用了 esbuild、tsx 或未生成 `emitDecoratorMetadata` 的工具。

处理：只使用 `npm run dev` 或 `npm run build`。

### 启动报 `UnknownDependenciesException`（守卫解析不到依赖）

表现：

```text
UnknownDependenciesException: Nest can't resolve dependencies of the AdminAuthGuard (?)
```

原因：某个模块的 Controller 用了 `AdminAuthGuard` 或 `RolesGuard`，但没有在自己的 `imports`
里加 `AdminAuthModule`。守卫的依赖是在**声明 Controller 的模块**里解析的，不是在提供守卫的
模块里。

处理：给该模块补 `imports: [AdminAuthModule]`。这是启动致命错误，进程完全起不来。

### 启动报「关系 admin_users 不存在」

表现：

```text
WARN [Bootstrap] 创建默认管理员失败：关系 "admin_users" 不存在
```

原因：初始化代码写在了 `main.ts` 或某个构造函数里。建表在 `DatabaseService.onModuleInit`，
而 `main.ts` 顶层代码更早执行。

处理：把需要写表的初始化移到 `onApplicationBootstrap`（`AdminBootstrapService` 就是这么做的）。

### 端口不合法或被占用

`PORT` 必须是 1–65535 的整数。确认没有旧验证实例仍监听 4500/4720。

## 3. 数据库错误

### `relation does not exist`

常见于重置验证库后没有重启服务。迁移只在应用启动时运行，先重置、再重启。

### 字段变成字符串

若 `createdAt`、`configVersion` 或 `apkSize` 类型异常：

- 检查列是否错误使用 bigint。
- 检查 int8 parser 是否仍注册。
- 普通大小和版本号应使用 integer。

### camelCase 字段为 undefined

检查 SQL 别名是否加双引号：

```sql
AS "songId"
```

### 并发注册偶发 502

检查唯一约束冲突是否在 `UsersRepository` 中翻译为 409/4090。不能只依赖插入前查重。

### 刷新令牌并发使用两次都成功

检查消费逻辑是否仍是单条 `UPDATE ... RETURNING`。拆成 SELECT + UPDATE 会产生竞态。

## 4. 鉴权错误

### 客户端不会自动续期

确认无效访问令牌返回 HTTP 401，而不是 403。客户端只对 401 触发续期重放。

### 用户被意外踢回登录页

检查：

- `/auth/refresh` 是否把数据库故障错误映射成 4xx。
- 音乐或图片上游 401 是否被直接透传。
- “没有歌词”“播放地址失败”等业务错误是否误用了 401。

### bootstrap 返回 401

这是热更新红线。`/app/bootstrap` 必须 `@Public()`，公开守卫只能尝试解析令牌，失败后继续放行。

### 开放搜歌返回 401/4014

按顺序检查：

- 请求头是否带了 `X-API-Key: tt_...`（优先）或 `Authorization: Bearer tt_...`，两者都缺就是 401/4014。
- key 是否已被禁用（`PATCH .../enabled`）或吊销（`DELETE .../:id`）；库里按 `sha256(key)` 匹配，手工改过明文会导致永远对不上。
- 是否误用了**用户 access token**（`payload.signature` 形状、不以 `tt_` 开头）或管理员会话令牌 —— 它们都不能当开放 key，同样回 401/4014。
- 若返回的是 429/4290 而不是 401，那是 `open-api` 限流桶（每 key 120 次 + 每来源地址 600 次/15 分钟）先命中，等窗口过去再试。

401/4014 **不会**退化成 403；看到 403 说明请求没走到 `ApiKeyGuard`，先核对路径是否真是 `/open/**`。

## 4.1 管理后台登录

### 所有 `/admin/auth/login` 请求都返回 401/4013

第一嫌疑：`@UseGuards(AdminAuthGuard, RolesGuard)` 被挂在了 `AdminAuthController` 类上。类级
守卫对 `login` 同样生效，而登录时用户还没有凭据，于是登录请求先被自己的守卫拦掉。

处理：改成方法级装饰器 `@AdminGuarded()`，只挂在需要会话的方法上。这不是类型错误，`tsc`
查不出来。

### 登录返回 401/4011，但密码确实是对的

按顺序检查：

- 该账号是否 `disabled_at` 非空（禁用后本地登录直接失败）。
- 配了 LDAP 时，目录是否明确拒绝了这个账号。LDAP 返回 `denied`（用户不存在、目录口令错、
  本地已禁用）时**不会**回落到本地密码，这是刻意设计。
- LDAP 返回 `skipped`（目录不可达、没配 LDAP、目录里没这个人）时才会用本地口令。
- 是否触发了 `auth:admin-login` 限流（每 IP 10 次/15 分钟），此时是 429 而不是 401。

注意 401/4011 不区分“用户名不存在”和“密码错”，这是防用户名枚举的刻意行为，不要试图改成
更精确的提示。

### 第二步 2FA 报 401/4011「验证已过期，请重新登录」

`temp_token` 只在进程内存里活 5 分钟，且用后即焚：

- 超过 5 分钟、或者已经用过一次，都会过期。
- 服务重启过（票据不持久化）。
- 多实例部署时，第二步落到了没有这张票的那个实例上。

处理：重新走第一步拿新票据。前端在 `AdminLogin.vue` 里遇到这种情况会清空票据并退回第一步。

### 第二步报「验证失败」而不是「动态码错误」

说明票据本身有问题，不是动态码算错：

- 请求体里的 `admin_id` 与票据绑定的 `admin_id` 不一致。
- 该账号在两步之间被禁用、删除，或 `totp_enabled` 被关掉。

### 登录成功但发布/公告/用户页面报 401

先确认**不是**守卫问题：所有管理控制器（`/app/admin/**`、`/desktop/admin/**`、`/admin/auth/**`）
挂的都是同一个 `AdminAuthGuard`，它只接受 `Authorization: Bearer <会话令牌>`，登录后直接就能
操作发布和公告页面。

如果确实报 401，检查：

- 会话是否已过期（24 小时）或已被 `revokeOtherSessions` 撤销（改密码会踢掉其它设备）。
- 请求头是否是 `Authorization: Bearer <token>`。**静态 `X-Admin-Token` 通道已整体移除**，
  带这个头一律 401/4013。

> **`AdminTokenGuard` 早已删除。** `common/guards/admin-token.guard.ts` 曾经有一个只认
> `X-Admin-Token` 的守卫，但它**没有任何引用**，是纯死代码。历史文档把它写成
> “发布接口的守卫”是错的，照着它排查会走偏。

### 管理员登录返回 403/4031

「首次登录必须先修改初始密码」。默认管理员（以及任何由超管重置过密码的账号）带
`must_change_password` 标记，改密前除 `me` 与 `change-password` 外所有管理接口都会被拒。
先调 `POST /api/v1/admin/auth/change-password` 完成改密，或者用超管在
`PATCH /api/v1/admin/auth/users/:id` 里重置该账号的口令。

### 管理员登录返回 429/4291

该**账号**被登录失败退避锁定了：连续失败 5 次触发，首次锁 5 分钟，之后每轮翻倍、30 分钟封顶。
换 IP 没有用（这正是它和 4290 的区别，4290 才是来源地址限流）。等待退避期过去，或由超管在
`PATCH /api/v1/admin/auth/users/:id` 里重置口令。注意退避状态是**进程内 Map**，重启服务即清空。

### 管理员接口返回 403/4030

这是“已认证但角色不够”，不是登录失效：

- `viewer` 读管理员列表或审计日志会 403（`PRIVILEGED_READ_ROLES` 不含观察者）。
- `viewer` 读**用户列表或听歌历史**也会 403：这两处返回 `email` 与逐首歌的播放记录，
  同样用 `PRIVILEGED_READ_ROLES`。前端已把「用户与统计」页签对观察者隐藏，所以正常操作下
  不会碰到；如果碰到了，说明是直接调接口。
- `viewer` 对**业务管理接口的任何写操作**都会 403：发布、补丁、放量、抬高下限、改远端配置、
  Windows 发布、公告增删改、禁用或删除用户、导入或删除图片 Key。观察者只能读
  发布、补丁、Windows 发布、公告、图片 Key 这五类运营数据。
- `admin` 做后台自身的写操作（创建/编辑/删除管理员、改 IP 白名单）会 403，这些需要 `super_admin`。
- 「当前 IP 不在白名单中」也是 403/4030，来自 `assertIpAllowed`。

角色矩阵的唯一出处是 `admin-roles.ts`，排查时先看那里，不要在控制器里找手写的角色数组。

**不要**把它改成 401：前端收到 401 会清本地会话并跳登录页，等于因为权限不足被登出。

### 写操作成功了但审计日志里查不到

按顺序检查：

- 控制器是否真的注入了 `AdminAuditService` 并 `await this.audit.record(...)`。**漏写不会报错**，
  接口照常返回 2xx，只是 `admin_audit_log` 里没有记录。
- `record()` 是否放在 `await` 业务动作**之后**。放在之前的话，操作抛异常时也会留一条“做了”的
  假记录。
- `record()` 的调用是否真的被 `await` 了。没 await 的话请求返回后异步写入可能被进程回收掉。
- 该模块是否 `imports: [AdminAuthModule]`（`AdminAuditService` 由它导出）。

`admin_id` 为 `null` 是正常的：那表示该管理员后来被删除了（外键 `ON DELETE SET NULL`），
历史审计必须保留为「无归属」。这是当前唯一会产生无归属记录的原因。

### 白名单里明明有我的 IP 却还是 403

按顺序检查：

- `TRUST_PROXY` 是否开启。关闭时服务端用 `socket.remoteAddress`，**忽略** `X-Forwarded-For`；
  写在白名单里的必须是服务端实际看到的地址。
- 地址格式。白名单只做精确字符串匹配，不支持 CIDR；`::ffff:192.168.1.1` 和 `192.168.1.1`
  不相等，写哪个取决于服务端实际看到哪个。
- 反向代理是否覆写而不是追加 `X-Forwarded-For`（追加时取第一个值，可能是客户端伪造的）。

### 删除管理员报外键错误（23503）

`admin_audit_log.admin_id` 和 `admin_users.created_by` 必须可空且带 `ON DELETE SET NULL`。
早期版本的库把 `admin_id` 建成了 `NOT NULL` + 无 `ON DELETE`，而 `CREATE TABLE IF NOT EXISTS`
不会修正已存在的表。迁移里有可重复执行的 `ALTER` 补齐，重启服务让迁移跑一次即可。

另外，落库前统一过 `auditActorId()`：身份缺失或 `id` 不是正整数时折成 `null`，不让非法值
撞这个外键。

### 忘记默认管理员密码

删除该行后重启服务，`AdminBootstrapService` 会重新创建 `admin`，口令取
`ADMIN_INITIAL_PASSWORD`（未设置则随机生成并在日志里打印一次），并重新带上强制改密标记：

```sql
DELETE FROM admin_users WHERE username = 'admin';
```

只在开发/验证库这么做。正式库应改密码而不是删账号 —— 删账号会连带删掉他的会话，审计里的
`admin_id` 会变成 `null`。

## 5. 搜索、播放和歌词

### 搜索显示 0 首但服务返回 200

检查响应是否被成功信封包裹。`/search` 首行必须直接包含 `type: song`，Content-Type 必须包含 `application/x-ndjson`。

### 搜索迟迟不展示

检查 `X-Accel-Buffering: no` 是否存在，以及 nginx 是否仍缓冲响应。

### 搜索歌曲不可播放

搜索只返回元信息。检查客户端是否随后请求 `/songs/{id}/link`，并携带搜索结果里的 `mid` 和 `type`。

### 播放请求得到上游 401

后端不能透传。检查 StreamService 是否把所有上游非 2xx 统一映射为 502。

### 歌词变成 JSON 文本

旧客户端默认需要裸文本。只有显式 `format=json` 时才返回 JSON。

## 6. 图片生成

### 创建返回 503/5032

原因：没有 `GPTIMAGE2` Key，或所有 Key 额度不足。

安全查询：

```sql
SELECT id, channel, quota
FROM api_key
WHERE channel = 'GPTIMAGE2'
ORDER BY quota DESC, id;
```

不要查询或输出 `key` 列。

### 创建返回 502/5021

检查：

- ApiSweet 服务状态。
- Key 是否有效、过期或被禁用。
- IP 白名单。
- 上游账户余额。
- `APISWEET_BASE_URL`。

第三方 401/402 被转换为 502 是预期行为。

### 创建失败但额度减少

查看日志是否出现“图片生成额度归还失败”。正常失败路径会按 `api_key.id` 归还预扣额度；若数据库同时故障，退款 SQL 可能失败，需要人工核对。

### 轮询返回 404/4042

本地 `image_generation_task` 没有该任务。只允许轮询通过当前后端成功创建并落库的任务。

### 轮询一直 IN_PROGRESS

检查上游任务状态和客户端总超时。不要无限提高轮询频率；建议间隔约 3 秒，并设置最大等待时间。

### 轮询提示任务不属于当前 Key

检查任务的 `api_key_id` 是否仍指向创建时的 Key。不要覆盖旧 Key 内容，应向 Key 池 INSERT 新行。

### 完成但没有图片

上游 `COMPLETED` 必须同时提供 `result.image_url`。缺失会按无效上游响应返回 502。

## 7. 热更新

### 登记后客户端看不到版本

检查：

- `rollout_percent` 是否仍为 0。
- 发布是否 `enabled = 1`。
- 客户端 versionCode 是否小于发布版本。
- SDK 是否满足 `min_sdk`。
- 灰度主体是否命中。

### 安装后仍反复提示更新

登记时可能错误读取了构建后的 `version.properties`。实际 APK 版本必须取自 `output-metadata.json`。

### APK 下载永久卡住

核对数据库 `apk_size` 与文件真实字节数；检查 Range 206、`Content-Range` 和越界 416。

### X-Latest-Version-Code 不更新

只有 `rollout_percent = 100` 且启用的最高版本进入响应头。调整放量后确认缓存已失效。

## 8. 契约验证失败

先区分：

- 代码真的破坏契约。
- 外部腾讯音乐接口临时波动。
- 验证库未正确重置或服务未重启。
- 4720 端口运行的是旧进程。
- 未显式调大 `ADMIN_RATE_LIMIT`（如 `1000`）：脚本一次运行要打上百次管理接口，管理端限流桶
  （默认 60 次/15 分钟）会在中途命中 4290，失败位置随请求顺序漂移，看起来像业务坏了。

推荐顺序：

1. 停止旧验证实例。
2. 重置 `music_verify`。
3. 用最新源码启动 4720。
4. 再运行契约脚本。
5. 只针对失败分组定位，不修改正式库。

## 9. 日志安全

禁止输出：

- API Key。
- `DATABASE_URL` 完整连接串。
- 用户密码。
- 访问令牌和刷新令牌。
- 管理后台会话令牌（`ADMIN_SESSION_TOKEN` / `localStorage.taotao_admin_token`）。

允许输出：

- Key 的数据库 ID。
- 渠道名。
- 剩余额度。
- 上游 HTTP 状态码。
- 不含凭据的任务 ID 和错误码。

## 10. 状态码快速定位

| HTTP/业务码 | 首先检查 |
| --- | --- |
| 400/4001 | 搜索关键词 |
| 400/4005 | DTO、ParseIntPipe、query 参数 |
| 400/4006 | 桌面模块校验、播放会话字段；传输加密的头格式、AAD 构造或解密失败，握手的 `clientHello` 非法同理 |
| 400/4007 | 图片模型、提示词、URL、taskId；音源账号字段（酷我 uid 必须纯数字等） |
| 400/4013 | 加密握手被拒绝（PSK 未配置或不匹配、`device_id` 派生不一致、报文非法） |
| 401/4010 | Authorization 是否缺失或过期 |
| 401/4011 | 普通登录密码错；管理员登录失败或 2FA 票据过期 |
| 401/4012 | 刷新令牌是否已轮换、撤销或过期 |
| 401/4013 | 管理员会话不被接受（`Authorization: Bearer` 缺失、过期、已撤销，或用了已移除的 `X-Admin-Token`） |
| 401/4014 | 开放 API Key 缺失、无效、禁用或吊销 |
| 403/4030 | 管理员角色不足，或当前 IP 不在白名单中 |
| 404/4040 | 路径和全局 `/api/v1` 前缀 |
| 404/4041 | Android/桌面版本、补丁或发布对象不存在 |
| 404/4042 | 图片任务、图片 Key 或本地桌面对象不存在 |
| 404/4043 | 公告不存在 |
| 404/4044 | 后台用户不存在 |
| 404/4045 | 歌单或分享短链不存在 |
| 409/4090 | 用户名唯一约束 |
| 409/4091 | 最低版本守卫和全量发布；加密会话失效（不存在或已过期，客户端重新握手即可） |
| 409/4092–4094 | 邮箱已注册、绑定状态或换绑状态 |
| 409/4095 | 播放会话身份冲突 |
| 409/4096 | 播放会话 historyRevision 领先服务端 |
| 429/4290 | 本地通用限流桶 |
| 429/4291 | ApiSweet 上游限流 |
| 502/5020 | 腾讯/网易音乐、IM、流式代理或未知内部错误 |
| 502/5021 | ApiSweet Key、余额或上游错误 |
| 503/5031 | IM 未启用；传输加密链路未启用（`/crypto/handshake` 收到 503/5031 表示按预期降级明文，不是故障） |
| 503/5032 | 发布记录读写失败或 GPTIMAGE2 无 Key/额度不足 |

## 11. 只读诊断命令

检查端口：

```powershell
Get-NetTCPConnection -State Listen | Where-Object LocalPort -In 4500,4720
```

检查 Node 进程：

```powershell
Get-Process node -ErrorAction SilentlyContinue
```

检查健康响应：

```powershell
curl.exe -i http://127.0.0.1:4500/health
```

检查数据库连通性：

```powershell
psql "$env:DATABASE_URL" -c "SELECT 1"
```

检查所需表是否存在，不读取业务数据：

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

检查图片任务统计，不读取提示词和 Key：

```sql
SELECT channel, state, count(*) AS count
FROM image_generation_task
GROUP BY channel, state
ORDER BY channel, state;
```

## 12. 何时停止自行处理

遇到以下情况应暂停写操作并先备份或请求确认：

- 连接串可能指向正式库，但无法确认。
- 需要 DROP、TRUNCATE、删除 APK 或删除 Key。
- 正式库表结构与当前迁移定义不一致。
- 同一个 taskId 对应多个外部账单或疑似重复扣费。
- 热更新最低版本已抬高但没有可下载的全量 APK。
- 回滚需要恢复数据库结构而不仅是替换 `dist/`。

## 13. 桌面更新与内容寻址文件

### `desktop/bootstrap` 返回无更新

按顺序确认：

1. `channel`、`architecture` 和客户端 `versionCode` 与后台发布记录一致。
2. 目标 `desktop_release.enabled = 1`，灰度比例命中当前用户/设备哈希。
3. 每个 `desktop_jar` 的对象确实存在于 `DESKTOP_RELEASE_DIR`，sha256 和文件大小一致。
4. 客户端是否低于桌面最低版本；强制更新只能使用同架构 100% 放量版本。

无效或过期 Authorization 不应导致 bootstrap 401；若出现 401，先检查 `AccessTokenGuard` 的
`@Public()` 元数据和反向代理是否篡改了路径。

### 桌面上传 400/4006 或下载 404/4042

上传接口接收原始字节和 query `sha256`，不能发送 JSON/base64/multipart。代理层检查：

- `client_max_body_size` 是否超过模块大小（单模块最多 500 MiB）；
- 是否启用了压缩/转码导致字节改变；
- `DESKTOP_RELEASE_DIR` 运行用户是否可写；
- 发布清单里的相对路径是否包含 `..`、反斜杠或重复项。

数据库有记录但对象不存在时不要手工改 sha256；重新上传同一对象或重新发布清单，并保留旧对象供
正在下载的客户端完成请求。

### 差分没有出现

只有上一版本同路径文件存在、源 sha256 匹配且生成结果小于目标完整模块 90% 时才会下发差分。
Courgette 失败会回退 bsdiff-wasm；两者都失败或差分过大时返回完整模块是预期行为。

## 14. IM、头像和邮件

### IM 返回 503/5031

检查 `IM_ENABLED=true` 是否通过环境校验，重启后确认 `IM_INTERNAL_API_BASE_URL` 可访问。启用时：

- 内部 API 必须是 `http://`/`https://`；外部 Gateway 必须是 `tcp://`；
- `IM_SESSION_LIFETIME_SECONDS` 必须在 60–86400；
- 5001 只允许本机/私网访问，5100 才是客户端 TCP 端口。

### IM 返回 502/5020 或同步格式错误

从 NestJS 主机执行悟空 IM `/health`，检查 HTTP 超时和服务端 Token。不要把悟空原始响应完整写日志，
只记录 HTTP 状态、用户 ID 和无敏感字段的错误摘要。聊天消息正文不在 PostgreSQL，不能用 SQL 查“消息是否丢失”。

### 头像上传失败

`POST /auth/avatar` 必须是 multipart 字段 `file`，MIME 以 `image/` 开头且不超过 5 MiB；服务端必须
配置 `LSKY_API_KEY`。Lsky 非 2xx 或返回结构不完整会映射为 400/4000，不能把图床令牌错误当成用户 401。

### 注册验证码发送失败

确认 `SMTP_HOST`、`SMTP_USER`、`SMTP_PASSWORD`、`SMTP_FROM` 四项完整，`SMTP_PORT` 在 1–65535；
同邮箱 60 秒内不能重复发码。契约测试可在 `NODE_ENV=test` 使用六位
`EMAIL_VERIFICATION_TEST_CODE`，普通开发/生产进程填写该变量会在启动时拒绝。

## 15. 分享试听和公共链接

### 分享元数据 404/4045

检查 token 是否符合 8–24 位规则、记录 `enabled` 是否为 1，以及 `PUBLIC_BASE_URL` 是否拼出正确
域名。公开元数据不需要登录；若反向代理把 `/api/v1/public/shares` 重写到需要 Authorization 的
位置，会表现为 401 而不是 404。

### 试听 502/5020 或 Range 异常

试听是**转发上游音频**，不再裁剪也不再落盘，所以没有 ffmpeg / 磁盘相关故障。
取不到地址一律归 502/5020（上游风控、歌曲下架），**绝不能是 401** —— 那会让客户端
把上游故障当成自己的令牌失效去续期。接口支持 200/206/416，必须保留 `Range`、
`Content-Range` 和 `Accept-Ranges`，不要由代理层缓存成 JSON 错误页。
上游偶发 `110001` 风控时 `resolveLink` 会回退到 v2 低码率试听链（约 60 秒），
此时分享页听到的是片段而不是整首 —— 这是全站播放路径共用的既有兜底，不是试听接口特有的问题。

## 16. 代码索引与文档漂移

发现文档写了不存在的路由或漏掉新模块时，先运行：

```powershell
codegraph index server
codegraph status server --json
codegraph query --path server --kind route --limit 200 --json ""
```

再对照 [00-code-index.md](00-code-index.md) 的路由和表清单（含开放搜歌 8 条新路由与 `open_api_key` 表）。
不要通过 `grep` 猜测 Controller
是否已注册，也不要在未确认索引状态时直接修改契约文档。

## 17. 传输加密

加密链路的具体业务码以 `server/src/crypto/` 下 `crypto.controller.ts`、`crypto.middleware.ts`
和 `native-loader.ts` 的实际实现为准，本节按当前实现描述。

### 握手返回 503/5031

`POST /api/v1/crypto/handshake` 返回 503/5031 表示加密链路未启用，客户端应回退明文：

- 加密产物缺失（启动日志出现「当前平台 … 无对应加密产物，传输加密降级为明文」或
  「未找到 taotao_crypto.node，先执行 tools/fetch-crypto.ps1 拉产物；传输加密降级为明文」）。
- 产物协议版本过低（「加密产物协议版本 … 低于设备绑定要求（>=2），降级为明文」）。
- 加载产物本身抛异常（`ERROR` 级「加载 taotao_crypto.node 失败，降级为明文」）。

部署上这是预期的优雅降级，不影响其它功能；需要启用加密时补齐产物即可。

### 握手返回 400/4013「握手被拒绝」

- `CRYPTO_PSK_ID` / `CRYPTO_PSK_HEX` 未配置或与客户端不一致（未配置时启动日志另有
  「握手将全部失败，链路保持明文」的 WARN）。
- `device_id` 与 PSK 派生不匹配 —— `device_id` 折进握手密钥，两侧派生输入不一致（例如客户端
  重装或换机导致设备号变化）时 MAC 失配，握手被拒。
- ClientHello 报文非法。`clientHello` 字段缺失或 base64 格式错误则是 400/4006。

### 请求返回 400/4006

加密头格式错误、AAD 上下文构造失败、请求体解密失败或读取失败统一收敛为 400/4006。
检查客户端 `X-Taotao-Crypto` 头格式，以及 AAD 是否按请求方法 + 路径（含 query）构造。

### 请求返回 409/4091「加密会话失效，请重新握手」

加密会话不存在或已过期。客户端重新握手即可，不是业务故障；长期运行的服务由会话清理定时器
每分钟回收过期会话。

## 18. 音源账号（酷我/波点）

### probe 失败但不知道错在哪

连通性探测失败会把 `music_source_account.last_status` 落库为 `invalid`，错误信息写入
`last_error`；成功时记录 `last_note`（探测到的真实音质）。先看管理列表的这两列，再决定是否
重新登录，不要凭感觉重复探测。

### 「搜得到放不出」

常见根因是酷我账号 `uid` 非纯数字 —— 酷我要求纯数字 uid，服务端写入时会以 400/4007 拦截
（「账号 ID 必须是纯数字」）；绕过校验写入的旧数据会让播放链路取到坏凭据。修正方式是在
管理后台重新登录该音源账号。

### 改了凭据但播放还在用旧凭据

播放链路对音源凭据有进程内缓存，最长 30 秒 TTL 自愈；管理接口的写操作（创建、PATCH、
启停、登录）会主动清缓存，正常情况下立即生效。若超过 30 秒仍不生效，确认改的账号
`enabled = 1`，以及客户端请求的 `source` 与账号的 `source` 一致。
