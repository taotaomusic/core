# 后端安全性与文档时效性审查报告

- **审查对象**：`server/src`（NestJS 11 + PostgreSQL 接口服务）、`server/wiki`（13 篇文档）
- **审查日期**：2026-09-17
- **代码基线**：`a713d11`（`feat(server): 管理端业务接口补角色校验与审计留痕`），工作区干净
- **方法**：全量路由与守卫穷举、认证链路逐行阅读、动态 SQL 与外部进程调用点抽查、文档断言与代码逐条比对

---

## 修复状态（2026-09-17 当日闭环）

本报告的问题已在同日全部处理完毕，**下方正文保留审查当时的原始描述**，用于追溯「当时为什么算问题」。
实际处置结果如下（`S12` 因 registry 不支持 audit 无法验证，按原样挂起）：

| 编号 | 处置 | 落地位置 |
| --- | --- | --- |
| S1 | 删除 `NODE_ENV` 门控，`AUTH_SECRET` 无条件要求 ≥32 位；删除源码内硬编码回退（改为 `?? ""`，由校验拦住） | `config/env.validation.ts`、`config/app-config.service.ts` |
| S2 | 默认管理员改为「配置 `ADMIN_INITIAL_PASSWORD`（≥12 位）或启动时随机生成并打印一次」，两种路径都置 `must_change_password = 1`；新增 `@AllowPendingPasswordChange()` 白名单 + 403/4031 全链路（含后台强制改密页） | `admin-bootstrap.service.ts`、`admin-auth.guard.ts`、`ForcePasswordChange.vue` |
| S3 | **整体移除**静态通道：`ADMIN_TOKEN` 环境变量、`AppConfigService.adminToken`、守卫内的 `X-Admin-Token` 分支、前端 `authHeaders` 的短串判定、桌面发布任务改 Bearer 会话 | `admin-auth.guard.ts`、`api.ts`、`desktopApp/build.gradle.kts` |
| S4 | 4 个控制器共 21 个方法改为方法级 `@AdminGuarded()`，删除全部类级 `@UseGuards`；新增逐条无凭据探测断言（39 条） | 新增 `admin-guarded.decorator.ts` |
| S5 | Express 中间件下发 CSP（挂在 `expressStatic` 之前）；内联主题脚本外置为 `public/theme-bootstrap.js` | `main.ts`、`frontend/index.html` |
| S6 | 新增账号维度指数退避（5 次阈值，5→10→20→30 分钟封顶）+ 独立错误码 4291；管理端登录地址限流放宽到 30 次/15 分钟 | `admin-auth.service.ts`、`api.exception.ts`、`rate-limit.service.ts` |
| S7 | LDAP 增加 `unavailable` 态，不再静默回落；新增 `auth_source` 列区分本地/LDAP 账号 | `ldap.service.ts`、`migrations.ts` |
| S8 | 改用文件魔数嗅探（PNG/JPEG/GIF/WebP，刻意排除 SVG）替代 `file.mimetype`；回写头像 URL 做 host 白名单校验 | 新增 `common/image-signature.ts`、`auth.controller.ts` |
| S9 | CORS 改为白名单回显 + `Vary: Origin`，删除通配 `*`（音视频流与搜索转发保持路由级 `*`） | `security-headers.interceptor.ts` |
| S10 | 保持进程内实现，但已在文档与代码注释中明确其边界（不跨实例、重启即清空） | `rate-limit.service.ts`、`wiki/11-admin-auth.md` |
| S11 | 移除 `speakeasy`，改为 RFC 6238/4226/4648 自实现 + 36 项 RFC 向量验证 | 新增 `admin-auth/totp.ts`、`tools/verify-totp.ts` |
| S12 | **未处理**：外部 registry 不支持 audit，无法验证依赖 CVE 状态 | — |

文档侧 D1/D3/D4/D5/D7 已修正，并按「全部同步」口径把 `server/wiki` 13 篇、`server/README.md`、
根 `AGENTS.md`/`README.md`/`RELEASE.md`/`HOT_UPDATE.md` 中所有关于静态令牌通道、默认口令、
TOTP 依赖、构建链步骤的过时描述一并更新。

**验收证据**：`tools/verify-contract.mjs` → 通过 213 项，失败 0 项；`npm run verify:totp` → 36 项通过，0 项失败；
`tsc -p tsconfig.json --noEmit` 零错误；运行时路由数 104，与 `wiki/00-code-index.md` 记录一致。

> 残留检索说明：全仓库 `X-Admin-Token` / `ADMIN_TOKEN` 的剩余命中**均为有意保留**，
> 语义统一是「该通道已移除」，分布在 `AGENTS.md`、`RELEASE.md`、`server/README.md`、
> `wiki/00-code-index.md`、`03-api-contracts.md`、`04-database.md`、`07-troubleshooting.md`、
> `10-desktop-release.md`、`11-admin-auth.md` 与 `verify-contract.mjs` 的反向断言中。
> 本报告正文内的旧引用属于审查快照，不视为残留。

---

## 一、结论摘要

后端整体安全基线**高于同类项目平均水平**。SQL 全部参数化、LDAP 过滤器已转义、口令用 scrypt 且做了时序均衡、2FA 票据设计正确、角色矩阵有唯一定义处。**未发现 SQL 注入、LDAP 注入、命令注入或路径穿越漏洞**，101 条路由中**没有一条处于完全无保护状态**。

但存在 **2 个严重问题**，其共同特征是「安全校验依赖了一个部署流程从未设置的开关」，以及「默认凭据开箱即用」。这两条叠加后，全新部署的实例在公网可达时存在被直接接管 admin 后台的路径。

| 编号 | 严重度 | 问题 | 位置 |
| --- | --- | --- | --- |
| S1 | **严重** | `AUTH_SECRET` 缺省回退到源码内硬编码常量，且强制校验被 `NODE_ENV=production` 门控，而部署流程从不设置该变量 | `config/app-config.service.ts:131`、`config/env.validation.ts:9` |
| S2 | **严重** | 默认超级管理员 `admin/admin123` 自动创建，无强制改密机制 | `admin-auth/admin-bootstrap.service.ts:5-6` |
| S3 | 高 | `X-Admin-Token` 兼容通道是绕过 2FA、IP 白名单、会话撤销与审计归属的静态万能钥匙 | `admin-auth/admin-auth.guard.ts:35-44` |
| S4 | 中 | 4 个管理端控制器使用类级 `@UseGuards`，违反项目自身明文规范 | 4 个 admin controller |
| S5 | 中 | 管理端会话令牌存 `localStorage`，且全站无 CSP | `frontend/src/App.vue:115`、`security-headers.interceptor.ts` |
| S6 | 中 | 管理端登录无账号维度失败锁定，仅按 IP 限流 | `common/rate-limit/rate-limit.service.ts:26-28` |
| S7 | 中 | LDAP 不可达时静默回落本地口令；TLS 证书校验可被关闭 | `ldap/ldap.service.ts:59-84,130` |
| S8 | 低 | 头像上传仅信任客户端声明的 MIME，回写 URL 未校验来源 | `auth/auth.controller.ts:151-157,176-180` |
| S9 | 低 | 全站响应带 `access-control-allow-origin: *` | `common/interceptors/security-headers.interceptor.ts:16` |
| S10 | 低 | 限流状态在进程内，重启即清空、不跨实例 | `common/rate-limit/rate-limit.service.ts:11` |
| S11 | 低 | 2FA 依赖已停止维护的 `speakeasy@^2` | `package.json` |
| S12 | 低 | 依赖 CVE 状态未能验证（registry 不支持 audit） | 审查局限，见第七节 |

文档侧：13 篇中 **11 篇存在与代码不符或明显滞后的内容**，其中 2 条会直接误导部署验证与客户端对接。

---

## 二、严重问题

### S1【严重】`AUTH_SECRET` 回退到硬编码常量，且强制校验的开关从未被打开

**证据**

```ts
// server/src/config/app-config.service.ts:131
this.authSecret = String(config.get("AUTH_SECRET") ?? "taotao-development-secret-change-me");
```

```ts
// server/src/config/env.validation.ts:9
if (config.NODE_ENV === "production" && secret.length < 32) {
  throw new Error("生产环境 AUTH_SECRET 至少需要 32 个字符");
}
```

该密钥是访问令牌的 HMAC 签名密钥：

```ts
// server/src/auth/auth.service.ts:99-101
private sign(payload: string): string {
  return createHmac("sha256", this.config.authSecret).update(payload).digest("base64url");
}
```

**关键放大因素**：全仓 grep 确认，**没有任何部署文档或脚本设置 `NODE_ENV=production`**。`wiki/06-release-deployment.md:229-249` 给出的 systemd 示例只有 `EnvironmentFile=/opt/taotao/server/.env`，没有 `Environment=` 或 `EnvironmentFile` 内的 `NODE_ENV`；`.env.example` 中也没有这一项。

**影响**

只要部署时未显式设置 `NODE_ENV=production`，缺失或过短的 `AUTH_SECRET` **不会导致启动失败**，而是静默使用一个公开写在源码里的固定字符串。攻击者据此可离线伪造任意 `sub` 的访问令牌：

```
payload = base64url({"sub":<任意用户id>,"iat":...,"exp":...,"typ":"access"})
token   = payload + "." + base64url(HMAC-SHA256(payload, "taotao-development-secret-change-me"))
```

`AccessTokenGuard` 只校验签名与 `exp`（`auth/guards/access-token.guard.ts:32`），因此该令牌可通行全部 44 条受全局令牌保护的接口——包括歌单、收藏、播放历史，以及 IM Token 签发（`POST /im/session`，可换取可连接悟空 IM 的凭据）。

**建议**

1. 把校验从 `NODE_ENV` 解耦：改为「`NODE_ENV !== "test"` 即要求 ≥32 字符」，或直接无条件要求。
2. 删除硬编码回退，`AUTH_SECRET` 缺失即启动失败。
3. `wiki/06-release-deployment.md` 的生产配置检查表补充 `NODE_ENV=production`，systemd 示例补 `Environment=NODE_ENV=production`。
4. 轮换已在用的生产密钥，并撤销现存会话（旧令牌在密钥变更后自动失效）。

---

### S2【严重】默认超级管理员 `admin / admin123` 自动创建，且无强制改密

**证据**

```ts
// server/src/admin-auth/admin-bootstrap.service.ts:5-6
const DEFAULT_ADMIN_USERNAME = "admin";
const DEFAULT_ADMIN_PASSWORD = "admin123";

// :30-36
if (await this.adminUsers.findAnyByUsername(DEFAULT_ADMIN_USERNAME)) return;
const { hash, salt } = this.adminAuth.hashPassword(DEFAULT_ADMIN_PASSWORD);
await this.adminUsers.create(DEFAULT_ADMIN_USERNAME, hash, salt, "超级管理员", "super_admin", null, null);
this.logger.log(`已创建默认管理员账号：${DEFAULT_ADMIN_USERNAME} / ${DEFAULT_ADMIN_PASSWORD}（请尽快修改密码）`);
```

`wiki/06-release-deployment.md:66-70` 自己承认了这一事实：

> **首次上线后必须立刻改掉默认管理员密码。** …… 代码里没有强制首次改密的机制。

**影响**

新部署实例在首次启动后即存在一个口令公开已知的 `super_admin` 账号。`POST /api/v1/admin/auth/login` 是公开路由，仅受每 IP 10 次/15 分钟限流（`rate-limit.service.ts:27`）——对已知口令不构成任何阻碍。攻击者登录后可创建管理员、删除用户、下发客户端版本与热修复补丁（可影响所有装机客户端）。

该风险与 S1 独立，即使 S1 修复，S2 仍然成立。

**建议**（任一即可，建议前两条同时做）

1. 初始口令从环境变量读取（如 `ADMIN_INITIAL_PASSWORD`），未配置时生成 32 字节随机口令并**仅在日志输出一次**。
2. 在 `admin_users` 增加 `must_change_password` 标记，改密前除 `change-password` 与 `logout` 外拒绝所有管理接口。
3. 至少在服务启动日志中升级为 WARN 级别并明确提示公网暴露风险。

---

## 三、高与中危问题

### S3【高】`X-Admin-Token` 兼容通道绕过全部管理员管控

**证据**

```ts
// server/src/admin-auth/admin-auth.guard.ts:35-44
if (legacyToken && this.auth.verifyLegacyToken(legacyToken)) {
  const legacyAdmin: AdminActor = {
    id: 0, username: "legacy_admin", role: "super_admin", display_name: "传统管理员",
  };
  request.adminUser = legacyAdmin;
  return true;
}
```

**影响**：只要 `.env` 中 `ADMIN_TOKEN` 非空，任何持有该串的人即获得固定 `super_admin` 身份，且同时绕过以下四项管控：

| 管控 | 为何被绕过 |
| --- | --- |
| 2FA | TOTP 只在本地口令路径触发（`admin-auth.controller.ts:100` `if (!viaLdap)`），兼容通道完全不经过 |
| IP 白名单 | `assertIpAllowed()` 仅在 `login` / `totp-verify` 内调用，不在守卫链上 |
| 会话撤销 | 它不是会话，`admin_sessions` 中无记录，改密、登出、禁用账号均无法使其失效 |
| 审计归属 | `id = 0` 在 `admin_users` 无对应行，留痕降级为「无归属」（`audit-log.repository.ts:14-21`） |

限流为 60 次/15 分钟/IP（`rate-limit.service.ts:47-49`），足以完成任何管理操作。

**关联文档风险**：`wiki/06-release-deployment.md:44` 把 `ADMIN_TOKEN` 的运维要求写成「**非空**且妥善保管」。这与最小权限原则相冲突——它把一把静态超管万能钥匙变成了部署清单上的推荐配置。

**建议**

1. 将 `ADMIN_TOKEN` 的推荐值改为**留空**（`.env.example` 已是留空，文档应与之统一）。
2. 若必须保留兼容通道：在守卫中识别兼容身份并**只放行读操作**，写操作一律要求真实会话；或限制来源为回环地址。
3. 文档补充该通道的完整风险说明（绕过 2FA 与白名单）。

### S4【中】4 个管理端控制器使用类级 `@UseGuards`

**证据**（均为 `@Public()` + 类级 `@UseGuards(AdminAuthGuard, RolesGuard)`）

| 控制器 | 位置 |
| --- | --- |
| `DesktopReleaseAdminController` | `desktop-release/desktop-release-admin.controller.ts:24` |
| `ImageKeyAdminController` | `image-generation/image-key-admin.controller.ts:24` |
| `UserAdminController` | `user-admin/user-admin.controller.ts:31` |
| `ReleaseAdminController` | `release/release-admin.controller.ts:39` |

**现状**：这 4 个控制器内均无 `login` 路由，因此**未触发实际后果**。对照组 `AdminAuthController` 已按规范改为方法级 `@AdminGuarded()`，`AnnouncementController` 亦有注释说明刻意只在方法级挂守卫。

**风险**：项目规范（`AGENTS.md`「管理员认证的几条硬约束」）明确禁止该写法，理由是类级守卫会让同控制器内的公开路由先被自己的守卫 401。这是一枚**静默的定时炸弹**——未来在这 4 个控制器中新增任何公开路由都会立刻失败，且不会被类型检查或静态审计发现。

**建议**：统一改为方法级 `@AdminGuarded()`。属机械改动，风险低。

### S5【中】管理端会话令牌存 `localStorage`，全站无 CSP

**证据**

```ts
// server/src/frontend/src/App.vue:115,139,168
const STORAGE_KEY = "taotao_admin_token";
const stored = localStorage.getItem(STORAGE_KEY);
localStorage.setItem(STORAGE_KEY, data.token);
```

响应头仅有 `nosniff` / `x-frame-options` / `referrer-policy`，**无 `content-security-policy`**（`security-headers.interceptor.ts:16-20`）。

**影响**：会话有效期 24 小时（`admin-auth.service.ts:9`）。任一 XSS 即可完整窃取 `super_admin` 会话。Vue 的默认转义降低了发生概率，但缺少 CSP 意味着没有第二道防线，且 `localStorage` 令牌无法被 `httpOnly` 保护。

**建议**：为 `/admin` 路径下发 CSP（至少 `default-src 'self'; object-src 'none'; base-uri 'none'`）。是否改为 `httpOnly` Cookie 需权衡 CSRF 防护成本。

### S6【中】管理端登录无账号维度锁定

**证据**

```ts
// server/src/common/rate-limit/rate-limit.service.ts:26-28
allowAuthAttempt(scope: string, address: string): boolean {
  return this.allow(`auth:${scope}:${address}`, 10, 15 * 60_000);
}
```

计数键只含来源地址，无账号维度、无失败计数、无锁定。

**影响**：攻击者用代理池轮换 IP 即可对 `admin` 这一已知账号名做无限口令猜测。对 LDAP 路径同样有效，且可能触发企业目录侧的账号锁定策略，演变为对目录的拒绝服务。

**建议**：增加按账号维度的失败计数与指数退避（如连续 5 次失败后对该账号全局退避 5 分钟），并将计数与 IP 计数取交集。

### S7【中】LDAP 不可达时静默回落本地口令

**证据**

```ts
// server/src/ldap/ldap.service.ts:104-105（异常路径）
this.logger.warn(`LDAP 认证异常，本次登录回落本地校验：${(error as Error).message}`);
return { outcome: "skipped", message: "目录不可用" };
```

```ts
// :130
rejectUnauthorized: this.config.ldapTlsRejectUnauthorized,
```

`.env.example` 允许 `LDAP_TLS_REJECT_UNAUTHORIZED=false`。

**影响**

1. 回落通道是**有意的 break-glass 设计**（`admin-auth.controller.ts:50-52` 有说明），但它意味着「目录判定某账号已停用/离职」这一事实在目录不可达时不生效——只要该账号在本地残留过口令哈希，仍可登录。注意 LDAP 同步创建的账号存的是随机占位哈希（`ldap.service.ts:210-211`），这部分已被正确处理；风险集中在「先有本地口令、后被 LDAP 接管」的账号。
2. 若运维为内网自签证书关闭 TLS 校验，同网段攻击者可中间人窃取**服务账号凭据与所有用户口令**——这是比单账号失陷严重得多的影响。

**建议**：目录不可达时，对「已由 LDAP 接管」的账号拒绝回落；把 `LDAP_TLS_REJECT_UNAUTHORIZED=false` 收敛到显式开发开关之下，生产环境强制为 `true`。

---

## 四、低危问题

| 编号 | 问题 | 证据 | 建议 |
| --- | --- | --- | --- |
| S8 | 头像上传仅校验客户端声明的 `file.mimetype`（可伪造），第三方图床返回的 `url` 未经来源校验即写入 `avatar_url` | `auth/auth.controller.ts:154,157,176-180` | 校验魔数字节；限定返回 URL 的 host 必须等于配置的图床域名 |
| S9 | 全站响应带 `access-control-allow-origin: *`。管理后台与 API 本就同源，通配符无收益 | `security-headers.interceptor.ts:16` | 收窄为 Origin 白名单，或仅对公开只读接口保留 `*` |
| S10 | 限流状态在进程内 Map 中，重启即清空，多实例部署时实际阈值放大 N 倍 | `rate-limit.service.ts:11` | 多实例场景迁至 Redis；单实例场景在文档中明确前提 |
| S11 | `speakeasy@^2.0.0` 已长期停止维护，而它承担 TOTP 校验这一安全关键路径 | `package.json`、`admin-auth.service.ts:3` | 迁移至 `otplib`，或用 `node:crypto` 自行实现 RFC 6238（约 30 行） |
| S12 | 依赖 CVE 状态未能验证 | `npm audit` 在当前 registry 返回 `404 NOT_IMPLEMENTED` | 在可访问官方 registry 的环境重跑，或接入 SCA 工具 |

---

## 五、已确认的良好实践

以下均为逐项核验通过，建议在后续重构中保持：

1. **SQL 全参数化**。抽查 `audit-log` / `admin-users` / `user-admin` / `favorites` / `playlists` / `image-task` 等全部含动态拼接的仓储，拼接片段仅来自硬编码列常量（`COLUMNS` / `TASK_COLUMNS`）与固定条件字符串，**无用户输入进入 SQL 文本**。`audit-log.repository.ts:33-44` 与 `user-admin.repository.ts:47-56` 的占位符编号递增写法正确。
2. **LDAP 过滤器按 RFC 4515 转义**。用户名与用户 DN 均经 `escapeFilterValue()`（`ldap.service.ts:233-236`）处理后注入 `{{username}}` / `{{userDn}}`，未发现 LDAP 注入。
3. **路径穿越防护到位**。`desktop-artifact.service.ts:162-165` 用 `resolve` + `relative` 判定并拒绝 `..`；下载文件名经 `basename()` 清洗（`:126`）。
4. **口令存储规范**。scrypt（`N=65536, r=8, p=1`）+ 16 字节随机盐；用户名不存在时执行等价 scrypt 抹平时序差（`admin-auth.service.ts:21,57-60`），且登录失败统一返回 4011 不区分账号是否存在。
5. **恒定时间比较**。口令校验、访问令牌签名、兼容令牌比较均使用 `timingSafeEqual`，并先比较长度。
6. **2FA 票据设计正确**。32 字节随机、5 分钟有效期、用后即焚、绑定 `admin_id`，第二步复查 `disabled_at` / `totp_enabled` / `totp_secret`（`admin-auth.controller.ts:139-157`）——有效挡住了「知道 admin_id 就跳过密码猜动态码」的路径。
7. **会话管理规范**。令牌 48 字节随机，库中仅存 SHA-256 哈希，24 小时过期，改密后撤销其它会话。
8. **角色矩阵有唯一定义处**。`admin-roles.ts` 集中定义三档角色与三个分组；抽查 5 个业务管理控制器的全部写路由，均正确标注 `WRITE_ROLES`，`viewer` 被一致排除。
9. **无「裸奔」路由**。101 条路由全部至少受全局令牌守卫或显式 `@Public()` 覆盖；未发现应公开却漏标的路由。
10. **命令执行面已收窄到零**。2026-09-20 起分享试听改为转发上游音频（`song-share.service.ts` 不再 spawn），
    服务端**已无任何外部进程调用**；bsdiff 通过 wasm 虚拟文件系统调用，无 shell 参与。
    （本轮之前唯一的调用是 ffmpeg 裁剪试听，当时已使用参数数组而非 shell 字符串。）
11. **敏感文件未入库**。`taotao-release.jks` 与 `local.properties` 均在 `.gitignore` 中且 `git ls-files` 确认未被跟踪；仓库仅含 `server/.env.example`（无真实凭据）。
12. **若干生产校验设计良好**：`EMAIL_VERIFICATION_TEST_CODE` 仅在 `NODE_ENV=test` 生效（防止测试后门带入生产）；`DATABASE_URL` 无默认值；`TRUST_PROXY` 默认关闭（防止伪造 `X-Forwarded-For` 绕过 IP 白名单）；已 `disable("x-powered-by")`。

---

## 六、文档时效性问题

### 高（会直接误导操作）

**D1 `wiki/README.md:119` 健康检查预期响应错误（两处错）**

```
# 文档写：
# 预期响应：{"status":"ok"}
```

实际返回（`health.controller.ts:23` 返回 `{ status: "up" }`，且 `/health` 同样被全局信封拦截器包装）：

```json
{"code":0,"message":"success","data":{"status":"up"}}
```

值与结构**均不符**。照此编写探针或冒烟脚本会一直判定服务未就绪。旁证：`wiki/06-release-deployment.md:92-96` 写的是「HTTP 200 / 响应 `code` 为 0」，与代码一致，即两份文档自相矛盾。

**D2 `wiki/09-wukongim.md:113` `GET /im/contacts` 声称返回头像**

文档写「从业务库读取昵称、**头像**和 IM UID」。实际只返回两个字段：

```ts
// server/src/auth/users.repository.ts:13
export type ImContact = { uid: string; nickname: string };
// :139
`SELECT im_uid AS uid, COALESCE(NULLIF(BTRIM(nickname), ''), username) AS nickname ...`
```

`grep -rn "avatar|头像" server/src/im/` 零命中。客户端按文档读取头像字段会得到 `undefined`。同一批文档的 `wiki/03-api-contracts.md:435` 表述为「返回除自己的用户资料」，未提头像——**两份文档口径冲突**。

### 中

**D3 `wiki/03-api-contracts.md:402` 播放历史默认条数写错**

文档写「`GET /playback/recent?limit=` 默认 50，最大 500」。实际默认值就是 **500**：

```ts
// server/src/playback/playback.controller.ts:16,42-43
const RECENT_LIMIT = 500;
const parsed = Number(limitParam ?? RECENT_LIMIT);
const limit = Number.isInteger(parsed) ? Math.min(RECENT_LIMIT, Math.max(1, parsed)) : RECENT_LIMIT;
```

依赖「不传 limit 只返回 50 条」做内存或性能假设的调用方会拿到 10 倍数据。旁证：`server/README.md:304` 写「默认及最多 500 首」，与代码一致。

**D4 `wiki/01-architecture.md:88` 目录树列出已删除的 `src/common/guards/`**

该目录及其唯一内容 `admin-token.guard.ts` 已随 `e0c28e0` 删除。`ls server/src/common/` 确认只剩 `decorators/`、`filters/`、`interceptors/`、`rate-limit/`。

更值得注意的是**同批文档自相矛盾**：`wiki/00-code-index.md:69`、`wiki/07-troubleshooting.md:175`、`wiki/11-admin-auth.md:32` 都明确写「`AdminTokenGuard` 已删除」，只有架构篇的目录树仍保留它。新开发者按目录树排查鉴权会扑空。

**D5 `wiki/06-release-deployment.md:44` 把 `ADMIN_TOKEN` 要求写成「非空」**

原文：「`ADMIN_TOKEN` | 非空且妥善保管」。这与安全实践冲突——该值是绕过 2FA、IP 白名单与会话撤销的静态 `super_admin` 凭据（详见 S3）。`.env.example` 的默认值本就是空，文档应与之统一为「保持为空，仅存量运维脚本依赖时临时启用」。

**D6 `wiki/00-code-index.md:5,19` 自称「当前实现的快照」，但底层索引已陈旧**

文档给出判定条件：「索引状态为 `pendingChanges = 0` 且 `reindexRecommended = false` 时，下面的清单才可作为当前实现的快照」。

实测 `server/.codegraph/codegraph.db`：files=131、nodes=2653、edges=6455、route 节点=104——四个数字**确实与文档一致**，但逐文件比对 `content_hash` 与磁盘 sha256 后：**26 个文件内容已变更、1 个文件已从磁盘删除、2 个磁盘文件不在索引中**。索引 `indexed_at` 为 18:18，早于 `a713d11`（19:49）约 1.5 小时。

因此「131 个文件」这一数字本身已不准确：它计入了已删除的 `admin-token.guard.ts`，漏计了新建的 `admin-roles.ts` 与 `admin-audit.service.ts`。104 条路由与 24 张表这两组数字恰好仍然成立，**反而掩盖了快照陈旧的事实**。

### 低

**D7 构建脚本步骤枚举不完整**（`wiki/02-development.md:119`、`wiki/06-release-deployment.md:15-21`）

文档列 3 步（清理 / `tsc` / 生成生产 `package.json`）。`package.json` 的 `build` 实际为 6 步，漏掉 `minify:server`（Terser 压缩）、`build:frontend`（管理后台 vite 构建到 `dist/public`）、`build:web-player`。其中管理后台构建缺失会直接导致 `dist/public` 不存在，而 `main.ts:76-89` 要在 `/admin` 提供该目录。照 `npm run build` 执行不受影响，手工复刻步骤的人会踩坑。

**D8 `wiki/04-database.md:70-76`** — `favorites` 小节漏列 `mutation_id text`（`database/migrations.ts:80`）。

**D9 `wiki/11-admin-auth.md:236`** — `GET /admin/auth/audit-log` 只列 `adminId` / `action` / `limit`，代码还支持 `offset`（`admin-auth.controller.ts:392,401`）。

**D10 `wiki/05-image-generation.md:62`** — 未写 `DELETE /app/admin/image-keys/{id}` 返回 **204**（`image-key-admin.controller.ts:61`）。

**D11 `wiki/00-code-index.md:266`** — LDAP 行列了 4 个变量却写「三者缺一」。实际必填为 3 个，不含 `LDAP_BIND_PASSWORD`（`config/app-config.service.ts:202-204`）。仅措辞歧义。

**D12 `wiki/09-wukongim.md:5`** — 版本写 `v2.2.5-20260422`，源码只写 `v2.2.5`，日期后缀无代码依据。

### 流程性问题

**D13 文档缺少时效性元信息**。13 篇中仅 `00-code-index.md` 含日期，其余 12 篇既无「最后更新」也无版本号。缺少日期标记使得任何一方都无法自查「久未更新」到底是「不需要改」还是「已经过期」。

**D14 部署流程未定义 `NODE_ENV`**（与 S1 联动）。全部文档与示例配置中，`NODE_ENV` 仅出现在 `=test` 语境，**从未出现 `production`**。这使得 `env.validation.ts` 中唯一的生产级校验形同虚设。

---

## 七、建议的处置顺序

**立即（本周内）**

1. **S1**：解耦 `AUTH_SECRET` 校验与 `NODE_ENV`，删除硬编码回退；部署文档补 `NODE_ENV=production`。变更后轮换生产密钥。
2. **S2**：初始管理员口令改为环境变量或随机生成；加 `must_change_password` 强制改密。
3. **D1**：修正 `wiki/README.md:119` 的健康检查预期响应——这是运维冒烟测试的第一道关卡。

**短期（两周内）**

4. **S3 + D5**：把 `ADMIN_TOKEN` 的文档要求改为「保持为空」；若保留兼容通道，限制为只读或回环地址。
5. **D2 / D3**：修正 IM 联系人与播放历史的接口文档，避免客户端按错误契约开发。
6. **S4**：4 个控制器改为方法级 `@AdminGuarded()`（机械改动）。
7. **S6**：管理端登录增加账号维度退避。

**中期（一个月内）**

8. **S5**：为 `/admin` 下发 CSP。
9. **D4 / D6 / D7**：修正架构篇目录树；刷新 CodeGraph 索引并复核 `00-code-index.md`；补全构建步骤。
10. **S11 / S12**：迁移 2FA 实现至维护中的库；在可访问官方 registry 的环境补跑依赖审计。
11. **D13**：为 13 篇文档统一增加「最后更新」与「适用代码版本」头字段，并纳入提交检查。

---

## 八、审查局限

1. **依赖 CVE 未验证**。`npm audit` 在当前 registry（npmmirror）返回 `404 NOT_IMPLEMENTED`，本次未能取得依赖漏洞清单。S11 基于库的维护状态判断，而非具体 CVE。
2. **未做动态验证**。本报告全部结论来自静态代码阅读与文档比对，未启动服务、未做渗透测试、未验证运行时行为。
3. **未覆盖客户端与构建链路**。范围限定为 `server/src` 与 `server/wiki`；Android/桌面/Web 客户端与 Gradle 构建链未纳入。
4. **前端审计为浅层**。管理后台前端仅检查了令牌存储方式与接口调用，未做完整 XSS/依赖审计。
5. **`X-Admin-Token` 的实际存量使用情况未能确认**。该通道是否为现役运维脚本所依赖，需由维护者判断后再决定收敛力度。
