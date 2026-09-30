# 排障:鉴权与管理后台登录

[返回文档中心](README.md)

最后更新:2026-09-27

普通用户侧与管理员侧的登录链路见 [31-api-auth-user.md](31-api-auth-user.md) 与 [80-admin-auth-login.md](80-admin-auth-login.md);业务码语义见 [74-error-codes.md](74-error-codes.md)。

## 1. 普通用户侧

### 客户端不会自动续期

确认无效访问令牌返回 HTTP 401,而不是 403。客户端只对 401 触发续期重放。

### 用户被意外踢回登录页

检查:

- `/auth/refresh` 是否把数据库故障错误映射成 4xx(必须保持 5xx)。
- 音乐或图片上游 401 是否被直接透传。
- 「没有歌词」「播放地址失败」等业务错误是否误用了 401。

### bootstrap 返回 401

这是热更新红线。`/app/bootstrap` 必须 `@Public()`,公开守卫只能尝试解析令牌,失败后继续放行。

### 开放搜歌返回 401/4014

按顺序检查:

- 请求头是否带了 `X-API-Key: tt_...`(优先)或 `Authorization: Bearer tt_...`,两者都缺就是 401/4014。
- key 是否已被禁用(`PATCH .../enabled`)或吊销(`DELETE .../:id`);库里按 `sha256(key)` 匹配,手工改过明文会导致永远对不上。
- 是否误用了**用户 access token**(`payload.signature` 形状、不以 `tt_` 开头)或管理员会话令牌 —— 它们都不能当开放 key,同样回 401/4014。
- 若返回的是 429/4290 而不是 401,那是 `open-api` 限流桶先命中,等窗口过去再试。

401/4014 **不会**退化成 403;看到 403 说明请求没走到 `ApiKeyGuard`,先核对路径是否真是 `/open/**`。

## 2. 管理后台登录

### 所有 `/admin/auth/login` 请求都返回 401/4013

第一嫌疑:`@UseGuards(AdminAuthGuard, RolesGuard)` 被挂在了 `AdminAuthController` 类上。类级守卫对 `login` 同样生效,而登录时用户还没有凭据,于是登录请求先被自己的守卫拦掉。

处理:改成方法级装饰器 `@AdminGuarded()`,只挂在需要会话的方法上。这不是类型错误,`tsc` 查不出来(完整约束见 [82-admin-routes-data.md](82-admin-routes-data.md))。

### 登录返回 401/4011,但密码确实是对的

按顺序检查:

- 该账号是否 `disabled_at` 非空(禁用后本地登录直接失败)。
- 配了 LDAP 时,目录是否明确拒绝了这个账号。LDAP 返回 `denied`(用户不存在、目录口令错、本地已禁用)时**不会**回落到本地密码,这是刻意设计。
- LDAP 返回 `skipped`(目录不可达、没配 LDAP、目录里没这个人)时才会用本地口令;但账号 `auth_source = 'ldap'` 时也不回落(503/5031)。
- 是否触发了 `auth:admin-login` 限流(每 IP 30 次/15 分钟),此时是 429 而不是 401。

注意 401/4011 不区分「用户名不存在」和「密码错」,这是防用户名枚举的刻意行为,不要试图改成更精确的提示。

### 第二步 2FA 报 401/4011「验证已过期,请重新登录」

`temp_token` 只在进程内存里活 5 分钟,且用后即焚:

- 超过 5 分钟、或者已经用过一次,都会过期。
- 服务重启过(票据不持久化)。
- 多实例部署时,第二步落到了没有这张票的那个实例上。

处理:重新走第一步拿新票据。前端在 `AdminLogin.vue` 里遇到这种情况会清空票据并退回第一步。

### 第二步报「验证失败」而不是「动态码错误」

说明票据本身有问题,不是动态码算错:

- 请求体里的 `admin_id` 与票据绑定的 `admin_id` 不一致。
- 该账号在两步之间被禁用、删除,或 `totp_enabled` 被关掉。

### 登录成功但发布/公告/用户页面报 401

先确认**不是**守卫问题:所有管理控制器(`/app/admin/**`、`/admin/auth/**`)挂的都是同一个 `AdminAuthGuard`,它只接受 `Authorization: Bearer <会话令牌>`,登录后直接就能操作发布和公告页面。

如果确实报 401,检查:

- 会话是否已过期(24 小时)或已被 `revokeOtherSessions` 撤销(改密码会踢掉其它设备)。
- 请求头是否是 `Authorization: Bearer <token>`。**静态 `X-Admin-Token` 通道已整体移除**,带这个头一律 401/4013。

> **`AdminTokenGuard` 早已删除。** `common/guards/admin-token.guard.ts` 曾经有一个只认 `X-Admin-Token` 的守卫,但它**没有任何引用**,是纯死代码。历史文档把它写成「发布接口的守卫」是错的,照着它排查会走偏。

### 管理员登录返回 403/4031

「首次登录必须先修改初始密码」。默认管理员(以及任何由超管重置过密码的账号)带 `must_change_password` 标记,改密前除 `me` 与 `change-password` 外所有管理接口都会被拒。

先调 `POST /api/v1/admin/auth/change-password` 完成改密,或者用超管在 `PATCH /api/v1/admin/auth/users/:id` 里重置该账号的口令。

### 管理员登录返回 429/4291

该**账号**被登录失败退避锁定了:连续失败 5 次触发,首次锁 5 分钟,之后每轮翻倍、30 分钟封顶。换 IP 没有用(这正是它和 4290 的区别,4290 才是来源地址限流)。等待退避期过去,或由超管在 `PATCH /api/v1/admin/auth/users/:id` 里重置口令。注意退避状态是**进程内 Map**,重启服务即清空。

### 管理员接口返回 403/4030

这是「已认证但角色不够」,不是登录失效:

- `viewer` 读管理员列表或审计日志会 403(`PRIVILEGED_READ_ROLES` 不含观察者)。
- `viewer` 读**用户列表或听歌历史**也会 403:这两处返回 `email` 与逐首歌的播放记录,同样用 `PRIVILEGED_READ_ROLES`。前端已把「用户与统计」页签对观察者隐藏,所以正常操作下不会碰到;如果碰到了,说明是直接调接口。
- `viewer` 对**业务管理接口的任何写操作**都会 403:发布、补丁、放量、抬高下限、改远端配置、Windows 发布、公告增删改、禁用或删除用户、导入或删除图片 Key。观察者只能读发布、补丁、Windows 发布、公告、图片 Key 这五类运营数据。
- `admin` 做后台自身的写操作(创建/编辑/删除管理员、改 IP 白名单)会 403,这些需要 `super_admin`。
- 「当前 IP 不在白名单中」也是 403/4030,来自 `assertIpAllowed`。

角色矩阵的唯一出处是 `admin-roles.ts`,排查时先看那里,不要在控制器里找手写的角色数组(矩阵细节见 [81-admin-roles-audit.md](81-admin-roles-audit.md))。

**不要**把它改成 401:前端收到 401 会清本地会话并跳登录页,等于因为权限不足被登出。

### 写操作成功了但审计日志里查不到

按顺序检查:

- 控制器是否真的注入了 `AdminAuditService` 并 `await this.audit.record(...)`。**漏写不会报错**,接口照常返回 2xx,只是 `admin_audit_log` 里没有记录。
- `record()` 是否放在 `await` 业务动作**之后**。放在之前的话,操作抛异常时也会留一条「做了」的假记录。
- `record()` 的调用是否真的被 `await` 了。没 await 的话请求返回后异步写入可能被进程回收掉。
- 该模块是否 `imports: [AdminAuthModule]`(`AdminAuditService` 由它导出)。

`admin_id` 为 `null` 是正常的:那表示该管理员后来被删除了(外键 `ON DELETE SET NULL`),历史审计必须保留为「无归属」。这是当前唯一会产生无归属记录的原因。

### 白名单里明明有我的 IP 却还是 403

按顺序检查:

- `TRUST_PROXY` 是否开启。关闭时服务端用 `socket.remoteAddress`,**忽略** `X-Forwarded-For`;写在白名单里的必须是服务端实际看到的地址。
- 地址格式。白名单只做精确字符串匹配,不支持 CIDR;`::ffff:192.168.1.1` 和 `192.168.1.1` 不相等,写哪个取决于服务端实际看到哪个。
- 反向代理是否覆写而不是追加 `X-Forwarded-For`(追加时取第一个值,可能是客户端伪造的)。

### 删除管理员报外键错误(23503)

`admin_audit_log.admin_id` 和 `admin_users.created_by` 必须可空且带 `ON DELETE SET NULL`。早期版本的库把 `admin_id` 建成了 `NOT NULL` + 无 `ON DELETE`,而 `CREATE TABLE IF NOT EXISTS` 不会修正已存在的表。迁移里有可重复执行的 `ALTER` 补齐(见 [43-database-tables-admin.md](43-database-tables-admin.md)),重启服务让迁移跑一次即可。

### 忘记默认管理员密码

删除该行后重启服务,`AdminBootstrapService` 会重新创建 `admin`,口令取 `ADMIN_INITIAL_PASSWORD`(未设置则随机生成并在日志里打印一次),并重新带上强制改密标记:

```sql
DELETE FROM admin_users WHERE username = 'admin';
```

只在开发/验证库这么做。正式库应改密码而不是删账号 —— 删账号会连带删掉他的会话,审计里的 `admin_id` 会变成 `null`。
