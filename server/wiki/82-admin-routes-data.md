# 管理后台:路由表、限流与启动期硬约束

[返回文档中心](README.md)

最后更新:2026-09-27

登录链路与 2FA 见 [80-admin-auth-login.md](80-admin-auth-login.md);角色矩阵与审计见 [81-admin-roles-audit.md](81-admin-roles-audit.md);前端实现见 [83-admin-frontend.md](83-admin-frontend.md)。

## 1. 认证域路由表

所有路径都要加 `/api/v1` 前缀。`@AdminGuarded()` = `AdminAuthGuard` + `RolesGuard`。

| 方法 | 路径 | 鉴权 | 最低角色 |
| --- | --- | --- | --- |
| `POST` | `/admin/auth/login` | 公开 | — |
| `POST` | `/admin/auth/totp-verify` | 公开(需 `temp_token`) | — |
| `POST` | `/admin/auth/logout` | 公开(无凭据时静默成功) | — |
| `GET` | `/admin/auth/me` | `@AdminGuarded()` | 任意已认证角色 |
| `POST` | `/admin/auth/change-password` | `@AdminGuarded()` | 任意已认证角色 |
| `POST` | `/admin/auth/totp-enable` | `@AdminGuarded()` | 任意已认证角色 |
| `POST` | `/admin/auth/totp-confirm` | `@AdminGuarded()` | 任意已认证角色 |
| `POST` | `/admin/auth/totp-disable` | `@AdminGuarded()` | 任意已认证角色 |
| `GET` | `/admin/auth/users` | `@AdminGuarded()` | `super_admin` / `admin` |
| `POST` | `/admin/auth/users` | `@AdminGuarded()` | `super_admin` |
| `PATCH` | `/admin/auth/users/:id` | `@AdminGuarded()` | `super_admin` |
| `DELETE` | `/admin/auth/users/:id` | `@AdminGuarded()` | `super_admin` |
| `GET` | `/admin/auth/audit-log` | `@AdminGuarded()` | `super_admin` / `admin` |
| `GET` | `/admin/auth/ip-whitelist/:adminId` | `@AdminGuarded()` | `super_admin` |
| `POST` | `/admin/auth/ip-whitelist/:adminId` | `@AdminGuarded()` | `super_admin` |

`logout` 虽然不带守卫,但会尝试解析 `Authorization: Bearer` 并撤销对应会话;没有凭据时直接返回 204,不做任何事 —— 这样退出接口本身不会把用户卡在登录页。

## 2. 业务管理接口的角色标注

`/app/admin/**` 不在认证控制器里,但用的是同一套守卫与角色常量:

| 域 | 路径前缀 | 读 | 写 |
| --- | --- | --- | --- |
| Android 发布 | `/app/admin`(releases、rollout、min-version、config、patches、patch-rollout) | `READ_ROLES` | `WRITE_ROLES` |
| 公告 | `/app/admin/announcements` | `READ_ROLES` | `WRITE_ROLES` |
| 图片 Key | `/app/admin/image-keys` | `READ_ROLES` | `WRITE_ROLES` |
| 开放 API Key | `/app/admin/open-api-keys` | `READ_ROLES` | `WRITE_ROLES` |
| 音源账号 | `/app/admin/music-sources*`(清单、可用项、增删改、启停、探测、短信、登录) | 清单 `PRIVILEGED_READ_ROLES`(凭据属个人信息),其余 `READ_ROLES` | `WRITE_ROLES` |
| 用户 | `/app/admin/users` | `PRIVILEGED_READ_ROLES` | `WRITE_ROLES` |

**守卫一律逐个方法挂,任何控制器都不例外。** 早期文档把守卫的挂法分成两类(「全是管理接口的控制器挂类上、含公开路由的逐个方法挂」),这个区分已经废弃:类级守卫在「将来给这个控制器加一个公开路由」时会静默出错,而维护者不会记得去改类级装饰器。统一用 `@AdminGuarded()` 之后,「这个路由受不受保护」在方法上一眼可见。

`AnnouncementController` 同时有公开的 `GET /announcements`(客户端首页要用),它是最早被迫逐个方法挂的那个;现在其余四个控制器也统一成同样的写法。

## 3. 限流分桶

认证域三个独立桶:

| 桶 | 挂载点 | 额度 |
| --- | --- | --- |
| `auth:admin-login` | `POST /admin/auth/login` | **每 IP 30 次/15 分钟** |
| `auth:admin-totp` | `POST /admin/auth/totp-verify`、`totp-enable`、`totp-confirm`、`totp-disable` | 每 IP 10 次/15 分钟 |
| `auth:admin-password` | `POST /admin/auth/change-password` | 每 IP 10 次/15 分钟 |

第一步和第二步分桶,避免密码尝试次数挤占动态码尝试次数;改密码单独一桶,是因为它需要提交当前密码,同样属于可爆破的凭据校验入口。

`auth:admin-login` 比另外两个宽(30 而非 10),是因为**登录的主防线已经换成账号维度的退避**(连续失败 5 次即锁,见 [80-admin-auth-login.md](80-admin-auth-login.md))。IP 桶剩下的职责只是给「同一个出口地址轮着猜很多不同账号」设上界,而不是限制单个管理员的登录次数 —— 办公室、机房普遍共用一个 NAT 出口,按 10 次收紧会让第二个人登录就被拦下,运维会误判成密码错误。实现见 `RateLimitService.allowAdminLoginAttempt()`。

全局限流桶总表见 [30-api-conventions.md](30-api-conventions.md)。

## 4. 数据模型

三张表,字段定义见 [43-database-tables-admin.md](43-database-tables-admin.md):

- `admin_users`:账号、scrypt 口令、角色、TOTP 密钥、IP 白名单、登录痕迹、禁用时间,以及 `must_change_password`(首次登录必须改密)与 `auth_source`(`local` / `ldap`)。
- `admin_sessions`:会话 token 的 SHA-256 哈希、过期时间、撤销时间、登录 IP 和 UA。
- `admin_audit_log`:操作审计,`admin_id` 可空且 `ON DELETE SET NULL`。

`publicAdmin()` 把管理员行收敛成 `{ id, username, display_name, role, must_change_password }` 再返回,不要把 `AdminUserRecord` 整条丢出去 —— 那会带上 `password_hash` 与 `totp_secret`。

## 5. 启动期四个硬约束

这四条都不是类型错误,`tsc` 和静态审计都发现不了,只有真正把服务跑起来才会暴露。前三条曾经同时存在,导致整个后台无法登录;第四条是后来补的强制改密链路。

### 5.1 不能把 `@UseGuards` 挂在控制器类上

```typescript
// 错误:类级守卫对 login 同样生效
@UseGuards(AdminAuthGuard, RolesGuard)
@Controller("admin/auth")
export class AdminAuthController {}

// 正确:共享的方法级装饰器(src/admin-auth/admin-guarded.decorator.ts)
@AdminGuarded()
@Post("login")
```

登录时用户手里还没有凭据,类级守卫会让**所有**登录请求先被自己的守卫 401 掉,表现为 `/admin/auth/login` 恒返回 401/4013,谁也进不去。

新增受保护方法时用 `@AdminGuarded()`,不要图省事把它挪到类上。装饰器本身也**只能**挂在方法上 —— `admin-guarded.decorator.ts` 的注释里写明了理由,改动它之前先读一遍。

### 5.2 用到 `AdminAuthGuard` 的模块必须导入 `AdminAuthModule`

Nest 在**声明 Controller 的模块**里解析守卫的依赖。某个模块用了 `AdminAuthGuard` 却没导入 `AdminAuthModule`,启动时会抛:

```text
UnknownDependenciesException: Nest can't resolve dependencies of the AdminAuthGuard (?)
```

这是启动致命错误,进程直接起不来。新增模块引用管理守卫时,记得把 `AdminAuthModule` 加进 `imports`。`ImageGenerationModule` 就踩过这个坑。

同理,业务控制器要写审计就得注入 `AdminAuditService`,而它也在 `AdminAuthModule` 里,所以 `AdminAuthModule` 的 `exports` 必须包含 `AdminAuthService`、`AdminUsersRepository`、`AdminAuditService` 三个。`RolesGuard` 只依赖 Nest 核心的 `Reflector`(全局可用),不需要导出。

### 5.3 默认管理员必须建在 `onApplicationBootstrap`

`DatabaseService` 的迁移跑在 `onModuleInit`,而 `main.ts` 里的代码在两个钩子**之前**执行。把默认管理员创建写在 `main.ts`,会先撞「关系 admin_users 不存在」。

`AdminBootstrapService` 实现 `OnApplicationBootstrap`,在迁移完成后才创建 `admin`(`super_admin`)。找不到该用户名才创建,已存在则跳过,所以重启幂等(启动时序见 [13-startup-lifecycle.md](13-startup-lifecycle.md))。

### 5.4 默认管理员不再有硬编码口令,首次登录强制改密

`admin/admin123` 是公开的默认凭据 —— 任何一次「部署完忘了改密码」都等于把后台挂在公网上。初始口令的两个来源与日志行为见 [80-admin-auth-login.md](80-admin-auth-login.md) §6。

无论哪种来源,创建的账号都带 `must_change_password = 1`。`AdminAuthGuard` 据此拦截:该标志为 1 时,除标了 `@AllowPendingPasswordChange()` 的 `me` 与 `change-password` 外,**一律 403/4031**「首次登录必须先修改初始密码」。

几个必须保持的点:

- **拦截放在守卫里,不放在控制器辅助函数里。** 业务控制器只通过 `audit.record(request, ...)` 使用 `request.adminUser`,不调用任何控制器内的取身份方法 —— 只有放守卫里才能覆盖全部当前与未来的管理路由。
- **改密的放行必须显式标注。** `@AllowPendingPasswordChange()` 是白名单,默认拒绝。新增「待改密状态下也要能用」的接口时才会去标它。
- **`updatePassword()` 顺带清标志,且在一条 SQL 里完成。** 分两步写会出现「密码已改但标志还在」的中间态,用户改完密码仍然被 4031 挡在门外,只能靠重启或手工改库救。
- **前端 `ForcePasswordChange.vue` 是独立全屏组件**,在 `App.vue` 里优先于后台主体渲染。登录响应里的 `must_change_password` 为 `true` 时直接进入它,不给出任何绕过入口(见 [83-admin-frontend.md](83-admin-frontend.md))。

## 6. 已知限制(单实例假设)

- `temp_token` 存进程内存,多实例部署时第二步可能落到没有票据的那个实例上。
- 登录失败退避也是**进程内 Map**:多实例部署时攻击者可以在实例之间分摊失败次数。与限流、2FA 票据同属「单实例假设」,见 [12-request-pipeline.md](12-request-pipeline.md)。
- IP 白名单只支持精确匹配,不支持 CIDR,也不归一化 IPv4-mapped 地址(见 [81-admin-roles-audit.md](81-admin-roles-audit.md))。
