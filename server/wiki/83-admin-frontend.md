# 管理后台:Vue 前端与安全响应头

[返回文档中心](README.md)

最后更新:2026-09-30

后端认证链路见 80–82 各篇;本文讲 `src/frontend/` 的 Vue 管理后台、CSP 安全响应头,以及契约脚本对管理后台的断言。

## 1. 构建与部署

- Vue 管理后台在 `src/frontend/`,Vite 构建产物输出到 `dist/public`,由 Nest 挂在 `/admin/`。
- **漏跑 `npm run build:frontend` 会导致 `/admin` 404**(构建 6 步见 [60-deploy-backend.md](60-deploy-backend.md))。
- 独立开发:`npm run dev:frontend` 起 Vite 5173,`/api` 代理到后端(见 [20-development-setup.md](20-development-setup.md));生产不经过 Vite 代理。

## 2. 关键组件

| 组件 | 职责 |
| --- | --- |
| `AdminLogin.vue` | 两步登录。第一步失败清空凭据;第二步 `temp_token` 失效时清空票据并退回第一步,而不是停在动态码输入框反复失败 |
| `ForcePasswordChange.vue` | `must_change_password` 为真时的全屏强制改密页,在 `App.vue` 里优先于后台主体渲染,没有跳过入口 |
| `AdminUserManager.vue` / `AuditLogViewer.vue` | 调用 `/admin/auth/**` 下的路径(管理员管理 / 审计中文化展示) |
| `IpWhitelistManager.vue` | 白名单编辑。**当前没有任何页面引用它,是孤儿组件**;白名单实际通过 `GET/POST /admin/auth/ip-whitelist/:adminId` 接口维护 |
| `ReleaseManager.vue` / `PatchManager.vue` / `AnnouncementManager.vue` / `UserManager.vue` / `MusicSourceManager.vue` / `OpenApiKeyManager.vue` / `SupporterKeyManager.vue` | 业务管理页,各自调用对应 `/app/admin/**` 路径 |
| `SystemSettings.vue` | 「系统设置」页:查看当前强制更新下限与可作下限的版本,抬高/取消下限(顺序约束同 `min-version` 接口,409/4091 兜底) |

`App.vue` 的页签按角色收敛:观察者看不到「用户与统计」「审计日志」;「音源账号」仅 `PRIVILEGED_READ_ROLES` 可见;「管理员」仅 `super_admin` 可见。改后端路由时必须同步对应组件,否则页面表现为 404/4040。改 `PRIVILEGED_READ_ROLES` 接口时要同步 `App.vue` 的页签可见性(观察者不该点进只会报错的页签,见 [81-admin-roles-audit.md](81-admin-roles-audit.md))。

## 3. 凭据存取

- 会话令牌存 `localStorage.taotao_admin_token`,24 小时过期。
- `api.ts` 的 `authHeaders()` **恒定返回 `Authorization: Bearer`**,不再按令牌长度猜头部(那个判断随 `X-Admin-Token` 兼容通道一起删掉了)。
- `adminChangePassword()` 是给强制改密页用的。
- XSS 是 localStorage 方案的主要威胁面,防线是 CSP(下一节);是否迁移 `httpOnly` Cookie 需权衡 CSRF 防护成本。

## 4. CSP 与安全响应头

`/admin` 下的所有响应(含 `express.static` 直接吐出的 JS/CSS)都带 CSP,由 `main.ts` 的 Express 中间件下发,**必须挂在 `expressStatic` 之前**:

```text
default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self';
object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

- **用中间件而不是 Nest 拦截器**:静态资源在路由之前就被 `express.static` 吐出去了,拦截器根本没机会运行,而 CSP 必须跟着 `index.html` 一起下发。
- `style-src` 是本策略唯一放宽的一项(Element Plus 以行内样式注入主题变量),`script-src` 保持严格 —— 因此**主题预置脚本已外置**成 `src/frontend/public/theme-bootstrap.js`。它必须是同步阻塞脚本(不加 `defer`/`async`),否则会晚于首屏渲染,暗色模式闪烁会回来。
- **只作用于 `/admin`**:分享页是 Kotlin/Wasm,编译 WebAssembly 需要 `wasm-unsafe-eval`,套用这份策略会直接把播放器打瘫。
- 响应头的响应阶段与拦截器顺序见 [12-request-pipeline.md](12-request-pipeline.md)。

## 5. 契约断言

管理后台认证与安全头的契约断言在 `tools/verify-contract.mjs`,相关段落:

1. **「管理端会话准备:强制改密与 Bearer 会话」**(必须在所有管理端断言之前跑):用 `ADMIN_INITIAL_PASSWORD` 首次登录 → 断言 `must_change_password === true` → 断言改密前访问其它管理接口是 403/4031 → 改密 204 → 新口令重登成功且不再要求改密 → 初始口令失效 4011。
2. **「管理路由逐条无凭据探测」**:枚举脚本内 `guardedAdminRoutes` 清单的全部受保护管理路由(随音源账号、创建用户等新接口增长,当前约 44 条,以脚本清单为准),逐条断言无凭据时返回 **401/4013**;再断言 4 条公开路由不会被管理员守卫拦下。期望 4013 而不是 4010 是有意的:4013 说明 `AdminAuthGuard` 确实跑了,若有人把 `@Public()` 摘掉,全局访问令牌守卫会抢先返回 4010,断言同样会失败。
3. **「管理后台认证:账号 / 角色 / 2FA / 审计」**:匿名 4013、密码错 4011、超管登录、`/me`、管理员列表、审计日志、缺/伪造 `temp_token` 均 4011、**已移除的 `X-Admin-Token` 通道不再被接受**、伪造 `X-Forwarded-For` 被白名单拒、创建管理员 201、viewer 读列表 403/4030、viewer 提权 403/4030、`PATCH` 局部更新、**账号退避 429/4291 且不影响其它账号**、不能禁用最后一个超管 400/4000、删除管理员 204。
4. **「业务管理接口:角色校验与审计」**:viewer 对发布放量、创建用户、禁用用户、发布公告、导入图片 Key 五个写接口一律 403/4030;发布、公告、图片 Key、音源可用清单四个读接口对 viewer 返回 200(不能顺手把只读账号锁死);**用户列表与听歌历史对 viewer 返回 403/4030,对 `admin` 返回 200/404**;`admin` 角色能发布公告;该公告在 `admin_audit_log` 里查得到且 `admin_id` 记的是发布者本人。另有「音源账号(后台)与酷我音源接入」「单曲倒带日记」等新段落覆盖音源账号的角色边界(viewer 读清单 403/4030)与日记接口契约。

另有「安全响应头与跨域」一节断言 CSP 与 CORS,以及「头像上传:按文件头判定格式」一节断言上传的魔数校验。

运行方式和数据库准备见 [22-contract-verification.md](22-contract-verification.md)。契约脚本是有状态的,必须「重置验证库 → 重启服务 → 单次运行」,并且启动时必须显式给 `ADMIN_INITIAL_PASSWORD`(否则拿不到管理会话,后面所有管理端断言会连锁失败)。

新增管理路由时,记得把它收进脚本的 `guardedAdminRoutes` 清单,让无凭据探测自动覆盖。
