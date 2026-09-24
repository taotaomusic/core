# 桃桃音乐后端服务

> 后端架构、接口、数据库与运维文档统一整理在 [wiki/](wiki/README.md)。先看 [CodeGraph 代码索引](wiki/00-code-index.md) 了解当前路由和模块快照。

NestJS + TypeScript 实现的音乐接口适配服务。音乐媒体、图片和歌词只做实时转发；分享试听和 Android/Windows 发布文件是受控的本地缓存/对象例外。PostgreSQL 保存用户账号、刷新令牌哈希、收藏、歌单、播放统计、热更新发布记录、第三方 API Key、图片任务和 IM 凭据元数据，不保存聊天正文或上游限时播放直链。

## 启动

先准备数据库（只需一次）：

```powershell
psql -U postgres -c "CREATE DATABASE music"
```

建表由服务启动时自动完成（幂等 DDL + 顾问锁），不需要手工执行迁移。然后：

```powershell
npm install
npm run dev
```

生产构建与部署：

```powershell
npm run build
```

产物是 `dist/` 目录树（不是单文件）。`npm run build` 会先用 `tsc` 编译后端，再用 Terser
压缩 `dist/` 内的服务端 JavaScript 并移除注释，最后由 `vite build` 把管理后台产到
`dist/public/`。源码始终保持正常缩进和注释，压缩只发生在构建产物中。**部署步骤**：

1. 上传 `dist/` 整个目录和 `package-lock.json` 到服务器（`dist/public/` 是管理后台，访问路径是 `/admin/`；不带上去后台会 404，接口不受影响）
2. 在 `dist/` 同级执行 `npm install --omit=dev`（`dist/package.json` 只列出生产依赖）
3. `node dist/main.js` 启动，或在 `dist/` 内 `npm start`

**PostgreSQL 必须先于本服务启动。** 数据库换成独立进程后多了一种失败模式：机器重启时若 Node 先起来，连接会失败。启动时有 10 次 × 1 秒的重试兜底，超过就退出交给进程管理器；用 systemd 的话建议加 `After=postgresql.service`。

> 迁移说明：早先用 esbuild 打成单文件 `dist/app.js`。NestJS 的构造器注入依赖
> `emitDecoratorMetadata` 生成的 `design:paramtypes` 元数据，而 esbuild 没有类型检查器、
> 无法生成这份元数据，因此改用 `tsc` 输出目录树。

Terser 是 `tsc` 之后的纯产物后处理，不替代 TypeScript 编译，并保留类名和函数名，避免影响
NestJS 依赖注入及生产日志定位。可以单独执行 `npm run minify:server`，但正常发布只需执行
`npm run build`。

开发模式用 `ts-node`（`npm run dev`，Node 的 `--watch` 负责重启）。

**不能用 tsx / esbuild 跑开发**：它们不支持 `emitDecoratorMetadata`，NestJS 的构造器注入拿不到 `design:paramtypes`，启动时会报 `Cannot read properties of undefined`。同理，构建也必须用 `tsc` 而不是 esbuild。

## 模块结构

```
src/
  main.ts                 启动引导：全局前缀、守卫/拦截器/过滤器、按路由挂载 body parser
  app.module.ts           根模块

  common/                 跨模块的基础设施
    api.exception.ts        带业务码的异常
    filters/                统一错误信封
    interceptors/           统一成功信封、安全响应头
    decorators/             @Public @RawResponse @RateLimit @CurrentUser
    guards/                 管理令牌校验
    rate-limit/             按用途划分的进程内滑动窗口限流桶
    semaphore.ts            并发上限

  config/                 环境变量读取与校验
  database/               PostgreSQL 连接池与建表迁移
  auth/                   注册、登录、令牌轮换、访问令牌守卫
  mail/                   验证码邮件和模板
  announcement/           公告读取、置顶和后台管理
  favorites/              收藏
  playlists/              云端歌单与歌曲顺序
  playback/               最近播放、播放会话与听歌统计
  shares/                 分享短链、公开元数据与 60 秒低码率试听
  upstream/               第三方接口适配（成功码、字段名、音质降级都收敛在此）
  music/                  搜索、播放转发、歌词
  open-api/               开放搜歌：API Key 鉴权（ApiKeyGuard）、第三方搜歌/歌词/直链端点与后台 key 管理
  image-generation/       gpt-image-2 图片生成任务适配
  release/                热更新：客户端引导、安装包分发、发布管理
  desktop-release/        Windows 模块清单、内容寻址对象和差分发布
  im/                     悟空 IM 会话、同步、撤回和已读代理
  user-admin/              后台用户、禁用和播放统计
  health/                 健康检查

  frontend/               管理后台（Vue 3 + Element Plus，浏览器入口）
    index.html              vite 入口，tsconfig 刻意 exclude 掉整个目录
    src/api.ts              信封拆解、sha256、上传原始字节
    src/App.vue             令牌输入与管理标签页
    src/components/         发布、补丁、公告、用户统计、AI 密钥与系统设置
```

根目录的 `webApp/` 是 Kotlin/Wasm 分享播放器，复用 `player-ui` 的歌曲信息、进度和播放控制。
执行 `npm run build:web-player` 会构建它并复制到 `dist/share-player/`；完整的 `npm run build`
会同时生成 NestJS、管理后台和分享播放器三类产物。

### 管理后台

浏览器打开 `http://localhost:4500/admin/`。首次进入会跳转到登录页。服务启动时会自动创建默认超级管理员账号 `admin`：

- 设置了 `ADMIN_INITIAL_PASSWORD`（至少 12 字符）时，初始口令取该值，日志只提示「口令取自环境变量」，不打印明文。
- 未设置时启动阶段随机生成一个 32 字节口令，并在日志里以 `WARN` 打印**一次**。

两种情况下该账号都带「首次登录必须改密」标记：改密前除 `/admin/auth/me` 与
`/admin/auth/change-password` 外，所有管理接口一律 403/4031。前端会直接进入全屏改密页，
没有跳过入口。

管理后台支持企业级认证体系：
- **多管理员账号**：独立于普通 users 表，支持 super_admin / admin / viewer 三种角色
- **双因素认证 (2FA)**：基于 TOTP 的动态码验证，可选启用（自实现，不依赖已停维护的 speakeasy）
- **操作审计日志**：全量记录管理员操作，支持按操作类型筛选
- **IP 白名单**：可选的网络层访问控制
- **登录失败退避**：同一账号连续失败 5 次即锁定，5 → 10 → 20 → 30 分钟逐轮翻倍
- **LDAP/SSO 集成**：可选的企业目录对接，支持组到角色的映射

后台入口固定在 `/admin/`，服务根路径留给未来网页版；Android 发布、补丁、公告、用户统计、AI 密钥和系统设置通过 `/api/v1/app/admin/*`，Windows 桌面发布通过独立的 `/api/v1/desktop/admin/*`，管理员管理通过 `/api/v1/admin/auth/*`。

管理接口**只认一种凭据**：`Authorization: Bearer <token>`（通过 `/api/v1/admin/auth/login` 获取，
24 小时有效）。历史上的静态 `X-Admin-Token` / `ADMIN_TOKEN` 通道已整体移除 —— 它同时绕过 2FA、
IP 白名单、会话撤销与审计归属。脚本化调用请从后台登录后取出会话令牌（浏览器
`localStorage.taotao_admin_token`），或用环境变量 `ADMIN_SESSION_TOKEN`。

`/app/admin/*` 与 `/desktop/admin/*` 的**写操作**要求 `admin` 及以上角色，观察者（`viewer`）
只能读、写会拿到 403/4030；所有写操作都会记一条审计（`admin_audit_log`）。
**用户资料与听歌历史是例外**：`/app/admin/users` 与 `/app/admin/users/:id/playback` 返回 `email`
和逐首歌的播放记录，读也要求 `admin` 及以上，观察者被拒（前端「用户与统计」页签对观察者隐藏）。
角色分配见 [wiki/11-admin-auth.md](wiki/11-admin-auth.md)。

角色与端点权限的对应关系、以及几条不能破的硬约束（类级守卫会连登录一起挡掉、
2FA 第二步必须带 `temp_token`、用到管理守卫或审计服务的模块必须自己导入 `AdminAuthModule`、
兼容身份的 `id = 0` 不能直接落库、管理接口漏标 `@RequireRole` 等于放行）逐条列在
[../AGENTS.md](../AGENTS.md) 的「管理员认证体系」一节。

后两条是启动级缺陷：漏了 `AdminAuthModule` 会抛 `UnknownDependenciesException`，兼容身份直接
落库会撞外键，两者都不报类型错误，`tsc` 查不出来，只有真把服务跑起来才会暴露。

管理后台使用 Vite 生产压缩；Element Plus 通过 `unplugin-vue-components` 与
`unplugin-auto-import` 按实际使用的组件、服务和样式自动导入，入口禁止重新使用
`app.use(ElementPlus)` 或导入 `element-plus/dist/index.css`，否则会退化成整包构建。五个管理
标签页使用异步组件，未打开的模块不进入首屏主包。用户统计读取服务端已同步的
`user_song_stats`，可查看歌曲数、有效播放、完整播放、累计听歌时长和最近播放明细。

```powershell
npm run build:frontend    # 产出 dist/public/
npm run dev:frontend      # 独立开发服务器（5173），API 代理到本机后端
```

**静态资源靠 express 中间件在路由之前拦截,不是 NestJS 控制器。** 这一点是踩出来的:

`main.ts` 里 `setGlobalPrefix("api/v1", { exclude: [...] })` 的 `exclude` **只能列具体路径,绝不能写通配符**。曾经为了让一个前端控制器的 `@Get("*")` 落在根路径而写成 `exclude: ["health", "/*"]` —— 结果它把**所有**路由都从前缀里豁免掉了,`/api/v1/search`、`/api/v1/favorites`、`/api/v1/auth/me` 全部 404,装机客户端会瞬间全线失联。

现在的做法没有这个风险:`express.static` 只响应磁盘上真实存在的文件,`/api/v1/...` 匹配不到任何文件就直接落到下一个中间件,所以不会遮住接口,也完全不用碰全局前缀。

另外 `vue` / `element-plus` 放在 `devDependencies` 是刻意的 —— 后台编译成自包含静态文件,运行时不需要它们。

### 鉴权是显式白名单

全局 `AccessTokenGuard` 默认要求所有路由携带访问令牌，公开路由必须用 `@Public()` 标注。

这替掉了早先「靠门禁那行代码的位置来保证鉴权」的隐式约定 —— 那种写法下，新增路由放错位置就是安全漏洞，且看代码不容易发现。现在漏标 `@Public()` 只会让接口意外要求登录（能被立刻发现），而不是意外裸奔。

### 数据层的四条硬规矩

从 SQLite 迁过来时踩到的坑，改 SQL 前先看这几条 —— 违反它们**不会报错**，只会让功能静默失效。

**① 别名必须加双引号。** PostgreSQL 把不加引号的标识符折叠成小写，`AS songId` 得到的字段是 `songid`。客户端读不到 `songId` 后所有歌都显示未收藏，且没有任何报错。写 `AS "songId"`。

**② 存 `Date.now()` 的列一律 `bigint`，其它整数一律 `integer`。** 毫秒时间戳约 1.7e12，超出 int4 的 21 亿上限；反过来 pg 默认把 int8 解析成**字符串**，所以 `database.service.ts` 里注册了 `INT8 → Number` 的解析器。这个解析器成立的前提是 bigint 列只存时间戳 —— 今后别把真正的 64 位 ID 放进 bigint 列。

**③ `app_release.enabled` 是 `smallint` 0/1，不是 `boolean`。** 两个调用点写法不一致：`release.service.ts` 是 `enabled === 1`（严格等于数字），`release.controller.ts` 是 `!enabled`（真值判断）。改成 boolean 会让前者恒假，抬高最低可用版本的守卫就永远返回 409。

**④ `bucketOf` 必须保持同步。** 它在 `Array.prototype.find` 的回调里被调用，一旦变成 async，回调返回的 Promise 恒为真值，`find` 会命中第一个候选版本 —— 灰度静默失效成全量下发。

普通 JSON 请求体限制为 16KB；桌面发布清单 `POST /api/v1/desktop/admin/releases` 单独允许 1MB，
以容纳多模块清单。桌面模块文件上传仍使用原始字节流，不应发送 JSON 或 multipart。

另外两处竞态是这次迁移顺带修掉的，别改回两步写法：刷新令牌的 `consume` 是**单条** `UPDATE … RETURNING`（拆成 SELECT + UPDATE 会让并发刷新双双成功，一次性令牌就不再一次性）；注册的唯一约束冲突在 `users.create` 里翻译成 409/4090（查重和插入之间夹着约 100ms 的 scrypt，连接池下挡不住并发，不翻译会漏成 502）。

## 配置

服务启动时读取同目录的 `.env`（已存在的环境变量优先），可参考 `.env.example`。`.env` 保存密钥，不要提交到版本库。

| 变量 | 说明 |
| --- | --- |
| `PORT` | 监听端口，默认 `4500` |
| `DATABASE_URL` | PostgreSQL 连接串，如 `postgres://postgres:密码@localhost:5432/music`。**没有默认值**，缺失或不是 `postgres://` 开头会启动即失败 |
| `AUTH_SECRET` | 访问令牌签名密钥，生产环境必须为至少 32 位随机值（启动时校验） |
| `ADMIN_INITIAL_PASSWORD` | 默认超级管理员的初始口令。留空则启动时随机生成并只打印一次；设置时至少 12 字符。两种情况下该账号首次登录都必须改密 |
| `APK_DIR` | APK 存放目录，默认 `./data/apk` |
| `DESKTOP_RELEASE_DIR` | Windows 模块和差分对象目录，默认 `./data/desktop` |
| `COURGETTE_PATH` | 可选的 PE 差分工具；未配置时使用 bsdiff-wasm |
| `DEFAULT_CHANNEL` | 默认渠道，默认 `release` |
| `PUBLIC_BASE_URL` | 对外基地址，用于拼装 APK 下载地址 |
| `SEARCH_CONCURRENCY` | 历史兼容配置，当前搜索不解析播放地址，不读取该字段 |
| `ENV_FILE` | 指定 `.env` 的其它路径 |
| `APISWEET_BASE_URL` | 图片生成服务地址，默认 `https://apisweet.com` |
| `LSKY_UPLOAD_URL` / `LSKY_API_KEY` | 头像图床地址和服务端 Key |
| `LSKY_PUBLIC_HOSTS` | 可选：允许写入头像地址的图床主机白名单（逗号分隔）。留空只要求 https 且格式合法 |
| `CORS_ALLOWED_ORIGINS` | 可选：允许跨域读取接口响应的来源白名单（逗号分隔）。**留空即不下发任何 CORS 头**；音视频流与搜索接口有各自的通配 `*`，不受影响 |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | 注册验证码邮件 SMTP 配置；五项均需设置 |
| `IM_ENABLED` / `IM_INTERNAL_API_BASE_URL` / `IM_EXTERNAL_GATEWAY_URL` / `IM_API_TOKEN` / `IM_SESSION_LIFETIME_SECONDS` | 悟空 IM 开关、内网 HTTP API、TCP Gateway、服务端 Token 和会话周期 |
| `TOTP_ISSUER` | 2FA TOTP 发行者名称，默认 `桃桃音乐管理后台` |
| `LDAP_URL` / `LDAP_BIND_DN` / `LDAP_BIND_PASSWORD` | LDAP/SSO 连接配置（可选，不配置则使用本地管理员账号） |
| `LDAP_USER_SEARCH_BASE` / `LDAP_USER_SEARCH_FILTER` | LDAP 用户搜索配置，过滤器支持 `{{username}}` 占位符 |
| `LDAP_GROUP_SEARCH_BASE` / `LDAP_GROUP_SEARCH_FILTER` | LDAP 组搜索配置，过滤器支持 `{{userDn}}` 占位符 |
| `LDAP_ROLE_MAPPING` | LDAP 组到管理员角色的映射（JSON 对象：组 DN → 角色）；写坏了只退化成空映射并告警，不会拦住服务启动 |
| `LDAP_TLS_REJECT_UNAUTHORIZED` | LDAPS 是否校验证书，默认 `true`。内网自签证书才显式设 `false` |
| `LDAP_TIMEOUT_MS` | LDAP 连接与单次操作的超时，默认 `10000` |
| `TRUST_PROXY` | 是否采信 `X-Forwarded-For` 作为客户端地址，默认**关闭**。只有确实部署在可信反向代理之后才设 `1` |

> **LDAP 登录的回落规则**：目录明确拒绝（口令错、已被禁用）时不回落，直接 401；
> 目录已用用户 DN 绑定成功但后续同步失败时返回 502，同样不回落；未配置、目录不可达、
> 或目录里查不到该用户时回落到本地密码校验 —— 这条路径是配置写错或目录挂掉时的
> break-glass 通道，默认超管 `admin` 因此始终可用。**例外**：账号的 `auth_source = 'ldap'`
> （已被目录接管）时，即使目录不可达也不回落本地口令，否则「目录里已停用」在抖动时会失效。

> **IP 白名单与 `TRUST_PROXY`**：白名单按 TCP 对端地址判断。`X-Forwarded-For` 是
> 客户端可随手伪造的头，未开启 `TRUST_PROXY` 时一律忽略 —— 否则任何人加一个请求头
> 就能伪装成白名单里的地址绕过访问控制。

## 响应约定

成功统一为 `{ "code": 0, "message": "success", "data": ... }`，失败为 `{ "code": <业务码>, "message": "<中文文案>" }`。客户端判断成功的唯一依据是 `code == 0`。

以下端点自己写响应体，**不套信封**（代码里用 `@RawResponse()` 标注）：

- `GET /api/v1/search` —— NDJSON 流
- `GET /api/v1/open/search/stream` —— 开放侧 NDJSON 流，格式同内部 `/search`
- `GET /api/v1/songs/{id}/play` —— 音频流
- `GET /api/v1/songs/{id}/lyrics` —— 默认纯文本（带 `format=json` 时才是信封）
- `GET /api/v1/app/apk/{versionCode}` —— 二进制
- `GET /api/v1/app/patch/{targetVersionCode}/{patchVersion}` —— Android 补丁二进制
- `GET /api/v1/public/shares/{token}/preview` —— 上游完整音频的转发流，支持 Range（60 秒上限由分享页自己守）
- `GET /api/v1/desktop/artifacts/{sha256}`、`GET /api/v1/desktop/patches/{sha256}` —— 桌面对象/差分二进制，支持 Range

## 接口

### 公告

- `GET /api/v1/announcements`：公开读取最新 20 条已发布公告，置顶公告始终排在最前。
- 管理端使用 `GET/POST /api/v1/app/admin/announcements` 读取、发布公告；`POST /api/v1/app/admin/announcements/{id}` 编辑，`POST /api/v1/app/admin/announcements/{id}/enabled` 上下线，`POST /api/v1/app/admin/announcements/{id}/pinned` 置顶/取消置顶，`DELETE /api/v1/app/admin/announcements/{id}` 删除。均需管理员会话。

### 管理端用户（需要管理员会话）

- `GET /api/v1/app/admin/users`：按用户名、昵称或邮箱搜索用户，并返回听歌汇总。
- `GET /api/v1/app/admin/users/{id}/playback`：读取该用户的统计与最近播放明细。
- `POST /api/v1/app/admin/users/{id}/disabled`，JSON：`{"disabled":true|false}`：禁用或恢复账号。禁用会撤销所有刷新令牌，所有后续业务请求都会被拒绝。
- `DELETE /api/v1/app/admin/users/{id}`：永久删除账号及其令牌、收藏、播放会话与统计，无法恢复。

### 认证

- `POST /api/v1/auth/email-verification`，JSON：`{"email":"name@qq.com"}`，发送六位注册验证码，返回 **204**
- `POST /api/v1/auth/register`，JSON：`{"username":"用户名","password":"至少6位密码","email":"name@qq.com","verificationCode":"六位验证码"}`，成功返回 **201**
- `POST /api/v1/auth/email/bind-verification`、`POST /api/v1/auth/email/bind`：已登录的老账号补绑邮箱
- `POST /api/v1/auth/email/change-verification`、`POST /api/v1/auth/email/change`：已绑定账号换绑新邮箱
- `POST /api/v1/auth/login`，JSON：`{"username":"用户名","password":"密码"}`
- `POST /api/v1/auth/refresh`，JSON：`{"refreshToken":"刷新令牌"}`
- `POST /api/v1/auth/logout`，JSON：`{"refreshToken":"刷新令牌"}`，返回 **204** 空体
- `GET /api/v1/auth/me`，需要访问令牌
- `GET /api/v1/auth/profile`：读取当前账号的昵称、头像和邮箱；`PATCH /api/v1/auth/profile`：更新 `nickname` 与 `avatarUrl`（头像可传 `null` 清除）

密码使用随机盐和高成本 scrypt 哈希，不保存明文。新注册账号必须完成 QQ 邮箱（`qq.com` 或 `foxmail.com`）验证码校验，验证码仅保存于服务进程内存、10 分钟过期、校验成功即删除；服务重启后未使用验证码也会失效。发码按来源地址限流，单邮箱 60 秒内不能重复发码。访问令牌有效期 15 分钟，刷新令牌 30 天；刷新令牌只保存 SHA-256 哈希，**刷新时轮换**，注销后立即失效。登录和注册按来源地址限流。

访问令牌格式为 `base64url(payload).HMAC-SHA256(payload, AUTH_SECRET)`，刻意没有换成 `@nestjs/jwt` —— 换格式会让所有已安装客户端手里的令牌立刻失效。

### 收藏（需要访问令牌）

- `POST /api/v1/favorites/tencent/105648974`
- `DELETE /api/v1/favorites/tencent/105648974`
- `GET /api/v1/favorites`

`createdAt` 与 `firstFavoritedAt` 都表示第一次收藏时间，取消后重新收藏也不会改变；
`favoritedAt` 表示当前这一轮收藏开始的时间。取消收藏采用软删除，旧客户端的列表和搜索结果
仍只会看到当前有效收藏，不会感知到软删除记录。

### 云端歌单（需要访问令牌）

歌单数据按账号保存在 PostgreSQL，歌单 ID 只在当前账号下有效。歌曲以
`source + songId` 作为稳定键，同时保存标题、歌手、专辑、封面、时长和链接的快照，
换设备时客户端可以先展示云端内容，再按歌曲来源补全最新信息。普通成功响应仍使用
`{ code: 0, message: "success", data: ... }` 信封。

当前歌单来源只接受 `tencent` 和 `netease`，必须与音乐路由支持的来源一致。
`songId` 一律按字符串传输；腾讯搜索返回 `id=0` 时，调用方必须同时提供非空 `mid`，
服务端会用 `mid` 作为稳定键，不会把所有这类歌曲折叠成同一个数字 ID。

- `GET /api/v1/playlists`：读取当前账号的歌单摘要（按最近更新时间倒序）
- `POST /api/v1/playlists`，JSON `{"name":"通勤","description":"","coverUrl":null}`：创建歌单，HTTP 201
- `GET /api/v1/playlists/{playlistId}`：读取歌单详情及按 `position` 排序的 `songs`
- `PATCH /api/v1/playlists/{playlistId}`（也接受 `PUT`），JSON 可更新 `name`、`description`、`coverUrl`
- `DELETE /api/v1/playlists/{playlistId}`：删除歌单及其中歌曲，HTTP 204
- `POST /api/v1/playlists/{playlistId}/songs`：添加歌曲；重复键只更新快照，不生成重复项
- `DELETE /api/v1/playlists/{playlistId}/songs/{source}/{songId}`：移除歌曲并自动压紧顺序
- `PATCH /api/v1/playlists/{playlistId}/songs/order`（也接受 `PUT`），JSON
  `{"songs":[{"source":"tencent","songId":"105648974"},...]}`：重排歌曲（`songIds`、`order` 也是兼容字段）
- `PUT /api/v1/playlists/{playlistId}/songs`，JSON `{"songs":[...]}`：完整替换歌曲集合，供首次同步或导入使用

每次资料、歌曲或顺序发生变化都会递增歌单 `revision`。排序接口要求提交的歌曲集合与
服务端当前集合完全一致；不一致返回 400/4004，客户端应先重新读取详情再重试，避免旧设备
覆盖另一台设备刚添加的歌曲。单个歌单最多保存 5,000 首歌曲。

### 歌曲分享

- `POST /api/v1/shares/songs`：需要访问令牌；JSON 传 `source`、`remoteId`/`mid`、可选 `type`，
  返回 `{token,url}`。同一账号重复分享同一来源和歌曲身份会复用原短链。
- `GET /api/v1/public/shares/{token}`：公开读取网页展示元数据、试听地址与最新稳定 APK 下载地址。
- `GET /api/v1/public/shares/{token}/preview`：公开试听流。服务端按**标准音质**解析上游地址后
  直接转发（支持 Range），**不裁剪也不落盘** —— 音乐不进服务器磁盘，返回的就是完整音频；
  「最多 60 秒」由分享页 `webApp` 自己守（`previewDurationSeconds` 只是告知）。
- `GET /s/{token}`：Kotlin/Wasm 分享页，循环播放这一首试听，不包含播放列表和下载歌曲能力。

生产环境应显式配置 `PUBLIC_BASE_URL`，否则短链会按请求的 Host 和代理协议头推导。

### 最近播放与听歌统计（需要访问令牌）

- `POST /api/v1/playback/sessions`：幂等上报一个播放会话的累计快照；新客户端额外传
  `historyRevision`（创建会话时从状态接口读到的版本）
- `GET /api/v1/playback/recent?limit=500`：读取最近播放，默认及最多 500 首
- `GET /api/v1/playback/recent/state`：读取 `{revision, clearedAt, clearedBefore, marker}`；`revision`
  是清空的权威代际，`clearedAt` 是服务端接收清空请求的时间，`clearedBefore` 仅为旧客户端兼容
- `GET /api/v1/playback/stats`：读取账号累计歌曲数、播放次数和听歌时间
- `DELETE /api/v1/playback/recent?marker=<uuid>`：服务端递增 `revision` 并返回同一状态对象；
  新客户端须持久化 UUID 形式的 `marker` 并在网络重试时原样带回，同 marker 始终返回第一次
  清空的结果，不会重复推进版本；清空最近播放的可见列表，累计统计仍保留

会话以客户端生成的 `sessionId` 幂等。重复上报只计算 `listenedMs` 相比旧快照的增长量；
单次听满 `min(30 秒, 歌曲时长 50%)` 计一次播放，听满 3 秒才进入最近播放。云端只保存
`source + songId` 和统计字段，不保存标题、歌手、专辑、封面或音频内容；客户端按 ID 复用
本地缓存，缺失时调用歌曲信息接口补全。

最近播放不使用 NDJSON 流：云端只返回 ID 与统计字段，500 条仍是较小的单次数据库结果；
普通 JSON 信封可以继续复用现有的鉴权续期与错误处理逻辑。

清空不以客户端时间戳作为因果判断。客户端须把 `/recent/state` 的 `revision` 与会话快照一起
持久化；服务端首次收到会话时固化该版本，只有当前版本的会话能进入最近播放。另一台设备清空后
补传的旧离线会话仍会累计听歌统计，但不会复活最近列表；同步到新版本后创建的下一会话会立即可见。
这是离线清空与离线播放没有共同因果顺序时的保守策略，避免设备墙钟错误导致新旧记录相互覆盖。
每次清空还需要客户端生成并持久化 UUID `marker`；服务端保留每个 marker 的结果，故即使清空
响应丢失、期间另一设备又清空，原 marker 的重试仍返回它最初的 `revision`，不会产生额外清空。

### 搜索、播放与歌词（需要访问令牌）

搜索默认并发聚合 QQ 音乐与网易云音乐；可传 `source=tencent` 或 `source=netease` 只查询一个
音源。所有搜索结果均携带 `source`，同一首歌的后续播放、歌词、详情、收藏和播放记录必须使用该
来源，不能仅按数字 ID 匹配，否则两个平台恰好相同的 ID 会串歌。`audioUrl`、`lyricUrl` 已经带上
对应来源，旧客户端可以直接播放。其它音乐接口默认仍是 QQ 音乐，传 `source=netease` 切换网易云。

`GET /api/v1/search?keyword=歌曲名&page=1&num=60&quality=10&source=netease`

`num` 范围 1–60（也接受 `limit`），默认 60，`quality` 范围 0–18。返回 NDJSON 流，每行一个 `{"type":"song","data":{...}}`，末行为 `{"type":"end","meta":{...}}`。

**搜索只返回元信息，不解析播放地址。** 早先每首歌都要额外向上游要一次播放链接、还要探测首字节，20 首约 1.8 秒，60 首最坏能打 240 次上游请求；而客户端拿到后又会把这个地址丢掉，改用自己拼的播放地址 —— 那些请求换来的只是「把拿不到地址的歌过滤掉」。现在改成客户端点播时再走 `/songs/{id}/link` 单独解析，搜索只剩一次上游请求，**60 首实测 130–900ms**（取决于上游缓存）。

代价是付费/下架的歌现在会出现在结果里。搜索结果带 `vip` 字段（上游本来就返回 `pay`，之前没读），界面据此加标记；真正拿不到地址的歌在点播时才提示。`meta.dropped` 因此恒为 0，保留只为兼容装机的旧客户端。

每行还带上：

- `favorited` —— 当前用户是否已收藏，由一次批量查询填入（`song_id = ANY($3::text[])`，走 `UNIQUE (user_id, source, song_id)` 索引）。这替掉了客户端原来「为每首歌拉一次完整收藏列表再线性查找」的做法。批量查询刻意放在 `writeHead` **之前**：响应头一旦发出，异常就只能截断连接。
- `mid` / `type` —— 解析播放地址要用。`songID` 为 0 的歌只能靠 `mid` 解析，拆成独立接口后不下发它们就永远拿不到地址。

响应带 `X-Accel-Buffering: no`：nginx 默认 `proxy_buffering on` 会把整个响应缓完再转发，逐行下发就白做了。代理配置不在版本库里，只能由服务端主动声明。

`GET /api/v1/songs/{id}/link?quality=10&mid=&type=&source=netease` 解析播放地址，返回**上游直链**：

```json
{ "code": 0, "data": { "songId": 97773, "url": "https://ws.stream.qqmusic.qq.com/...",
  "quality": 10, "requestedQuality": 12, "kbps": "1644kbps", "fallback": true } }
```

客户端直接拉 QQ 的 CDN，音频字节不再经过本服务。`quality` 是**实际拿到的**档位 —— 上游不会自动降级，阶梯是我们自己走的，`fallback` 为真表示发生了降级，客户端据此提示「这首只有 320kbps」。拿不到地址走 502，**绝不能 401**。

`GET /api/v1/songs/{id}/info?mid=&source=netease` 给出歌曲信息与**真实存在**的音质档位（含各档字节数），已过滤掉 `size` 为 0 的档位。客户端的音质选择器用它只列出能选的档，并在下载前提示体积。网易云上游没有音质列表接口，此时仅返回实际请求到的最高可用档位。

`GET /api/v1/songs/{id}/play?quality=10&source=netease` 仍然保留：装机的旧客户端在用，也是新客户端解析失败时的兜底。支持 Range 断点续传（透传给上游并回写 206 与 `Content-Range`）；无 Range 时返回 200 全量。上游非 2xx 一律归成 502，**不透传上游的状态码** —— 上游的 401 会被客户端当成自己的令牌失效。

`GET /api/v1/songs/{id}/lyrics?source=netease` 默认返回纯 LRC 文本；带 `format=json` 时返回 `{lrc, yrc, trans}`，其中 `yrc` 是逐字时间轴，格式为 `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`。

### 开放搜歌 API（需要 API Key）

第三方通过 API Key 调用的开放接口，前缀 `/api/v1`。它与用户访问令牌、管理员会话是三套互相独立的凭据。

- `GET /api/v1/open/search?keyword&page&num&limit&quality&source`：统一信封，`data = { songs, meta }`。
- `GET /api/v1/open/search/stream?...`：裸 NDJSON（`@RawResponse`），格式同内部 `/search`。
- `GET /api/v1/open/songs/{id}/lyrics?format&mid&source`：默认纯文本，`format=json` 走信封。
- `GET /api/v1/open/songs/{id}/link?quality&mid&type&source`：信封，`data = { songId, url, quality, requestedQuality, kbps, fallback }`。

鉴权用 `X-API-Key: tt_...`（优先）或 `Authorization: Bearer tt_...`；缺失、无效、禁用或吊销一律 **401/4014**（新码，绝不能 403）。用户 access token（不以 `tt_` 开头）不能当开放 key。开放侧 Song 的 `favorited` 恒为 `false`，**不下发 `audioUrl`**（外部改用 `/open/songs/{id}/link` 换直链），`lyricUrl` 指向 `/api/v1/open/songs/...`；开放 JSON 端点下发 `access-control-allow-origin: *`（不带 credentials）。限流用独立的 `open-api` 桶：每 key 120 次 + 每来源地址 600 次 / 15 分钟，超限 429/4290。

管理端（需管理员会话）：`GET/POST /api/v1/app/admin/open-api-keys`、`PATCH/DELETE /api/v1/app/admin/open-api-keys/{id}`；读用 `READ_ROLES`，写用 `WRITE_ROLES`，创建返回的明文 key 只出现一次（列表只有 `keyPrefix`），写操作记 `open_api_key.*` 审计。详见 [wiki/03-api-contracts.md](wiki/03-api-contracts.md) §17。

### 图片生成（需要访问令牌）

`POST /api/v1/draw/completions` 创建 `gpt-image-2` 图片生成任务。服务端从数据库
`api_key` 表读取 `GPTIMAGE2` 渠道的 Key，客户端不能直接接触第三方密钥。

Key 不存环境变量。同一渠道可放多条 Key，创建任务时优先使用剩余额度最高的一条：

```sql
INSERT INTO api_key (channel, key, quota)
VALUES ('GPTIMAGE2', '替换为真实Key', 100)
ON CONFLICT (channel, key) DO UPDATE SET quota = excluded.quota;
```

`quota` 是本地可创建任务次数，每次成功创建任务扣减 1；上游拒绝或连接失败时自动归还。
状态轮询不消耗额度。任务会记录提示词、消耗额度、渠道、状态、完成图片和创建时使用的
`api_key_id`，所以同渠道多 Key 或后续新增 Key 都不会让轮询查错。

```json
{
  "model": "gpt-image-2",
  "prompt": "一只可爱的猫咪在草地上玩耍",
  "aspectRatio": "1:1",
  "imageSize": "1K",
  "quality": "high",
  "images": ["https://example.com/image-1.png"]
}
```

`images` 最多 8 张且只接受 HTTP/HTTPS URL。成功响应沿用统一信封：

```json
{
  "code": 0,
  "message": "success",
  "data": { "taskId": "xxxxx", "status": "IN_PROGRESS" }
}
```

该接口按登录用户 10 次、来源地址 60 次/15 分钟限流。第三方 401/402 会转换为
502，不能原样透传成 401，否则客户端会把服务端密钥或余额问题误判为用户登录失效。

创建成功后，客户端每隔约 3 秒调用 `GET /api/v1/draw/result/{taskId}`。状态为
`IN_PROGRESS` 时继续轮询，`COMPLETED` 或 `FAILED` 时停止：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": "task_xxxxx",
    "state": "COMPLETED",
    "progress": 100,
    "createdAt": 1773894600,
    "completedAt": 1773894780,
    "result": { "imageUrl": "https://example.com/generated-image.png" },
    "error": null
  }
}
```

状态查询独立限流：按用户 300 次、来源地址 1800 次/15 分钟。第三方返回 404 时映射为
本服务的 404/4042；第三方 401 仍转换为 502，不能触发客户端令牌续期。

### 上游接口与音质降级

搜索与播放链接用 v3（`https://api.vkeys.cn/music/tencent`，路径里不带版本号前缀），歌词用 v2（`https://api.vkeys.cn/v2/music/tencent`）—— 只有 v2 的歌词接口同时给出逐字时间轴与翻译。

实测发现文档与实际不符，实现以实际响应为准：

- v3 搜索的封面字段是 `cover`，文档写的 `albumImage` 不存在
- v3 播放链接实际只返回 `songID` / `songMID` / `kbps` / `link` / `url`，文档里的歌名、歌手、封面、时长、音质全都没有，因此元信息全部取自搜索结果
- 成功码不统一：v3 用 `0`，v2 歌词用 `200`，两个都认
- v3 播放链接**不会自动降级**，付费歌曲请求 `quality=14` 可能返回 `code=110000`，因此实现了音质阶梯 `[14,11,10,8,4,0]`，最多试 4 档
- 上游会返回 `code=0` 但 `kbps=0kbps` 的死链，所以首字节探测放在阶梯循环**内部**：某一档给出死链时继续往下试，否则会白白放弃后面本来可用的低音质档位
- **`/song/info` 的 `qualityInfo[].type` 与 `/song/link` 的 `quality` 参数一一对应**（已用同一首歌逐档核对，返回的文件名完全一致），且 `size == 0` 与「这一档拿不到可用地址」严格对应。所以 `/link` 先问一次 `/song/info`，直接挑一个真实存在的档位 —— 最坏情况从 4 次请求降到 1 次，也不会再把请求打在必定失败的档位上。`info` 自己失败时退回音质阶梯
- 实测档位到 **18**（NAC），不是文档和旧版 README 写的 16
- 一处旧注释的误判：付费歌曲**并非**一律在 `quality=14` 失败 —— 实测付费歌的 14 档同样能拿到地址（6020kbps），报 `110000` 的真正原因是该档 `size` 为 0，与是否付费无关

### 热更新

设计说明见项目根目录 `HOT_UPDATE.md`。

客户端接口（**免鉴权**，原因是最需要强制更新的场景恰恰是「上一个版本把登录搞坏了」）：

- `GET /api/v1/app/bootstrap?versionCode=54&sdk=36&deviceId=<uuid>&channel=release`

  一次返回更新信息与远程配置。`Authorization` 可选：带了有效令牌就按用户做灰度分桶，未登录或**令牌已过期**时退回 `deviceId`。这条路由永远不会返回 401 —— 客户端刻意用未续期的令牌调用它，返回 401 会让热更新通道静默失效。

- `GET /api/v1/app/apk/{versionCode}`：下载安装包，支持 Range（206 / `Content-Range`），越界返回 416，`ETag` 为 APK 的 sha256。

发布管理（需要管理员会话 `Authorization: Bearer`）：

- `GET /api/v1/app/admin/releases?channel=release`
- `POST /api/v1/app/admin/releases?versionCode=&versionName=&note=&rollout=&minSdk=&sha256=`

  请求体为 **APK 原始字节**（不是 base64、不是 multipart），服务端边写盘边算 sha256。这条路由在 `main.ts` 里被排除在 JSON body parser 之外，否则 14MB 的包会被缓进内存或直接 413。

  ```powershell
  curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/releases?versionCode=54&versionName=1.0.53&note=修复闪退&rollout=0" `
    -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
    --data-binary "@androidApp/build/outputs/apk/release/androidApp-release.apk"
  ```

  `versionCode` 和 `versionName` 必须取自 `androidApp/build/outputs/apk/release/output-metadata.json`，**不能读 `version.properties`**：`incrementVersion` 是 `assemble` 的 `finalizedBy`，构建结束时那个文件里的值已经比刚产出的 APK 大 1，登记错了客户端会陷入「提示更新 → 装完还提示」的死循环。

- `POST /api/v1/app/admin/rollout`，JSON `{"versionCode":54,"percent":10}`
- `POST /api/v1/app/admin/min-version`，JSON `{"versionCode":54}`

  内置守卫：必须已存在放量 100% 且版本号不低于目标下限的发布，否则返回 409。这是为了堵住变砖路径 —— 被判定为强制更新的客户端只会收到全量版本，若不存在这样的版本，它们会被拦在门外却拿不到升级包。

- `GET /api/v1/app/admin/config`
- `POST /api/v1/app/admin/config`，JSON `{"key":"feedback.enabled","value":"true","minVersionCode":70}`，`value` 传 `null` 表示删除

### 灰度分桶

命中条件为 `sha1(发布ID + ":" + 主体标识)` 前 4 字节取模 100 后小于 `rollout_percent`。

混入发布 ID 是为了让每个版本得到互相独立的分桶，否则永远是同一批用户当小白鼠；用哈希而非随机数是为了对同一用户稳定，否则每次检查结果都会翻转，更新提示时有时无。实测 20000 个设备号在 10% / 30% / 50% 三档下的命中率为 9.60% / 29.93% / 50.46%。

### 限流

各用途计数器互相独立 —— 更新检查和图片轮询都是周期性调用，与登录或图片创建共用会烧掉其它用途的额度。

| 用途 | 阈值 |
| --- | --- |
| 登录 / 注册 | 按来源地址 10 次 / 15 分钟 |
| 客户端引导与安装包下载 | 按设备号 60 次 + 按来源地址 900 次 / 15 分钟 |
| 发布管理 | 按来源地址 60 次 / 15 分钟 |
| 图片任务创建 | 按用户 10 次 + 按来源地址 60 次 / 15 分钟 |
| 图片任务轮询 | 按用户 300 次 + 按来源地址 1800 次 / 15 分钟 |
| 开放搜歌 `/api/v1/open/**` | 按 key 120 次 + 按来源地址 600 次 / 15 分钟 |

外层阈值放得宽，因为校园网、办公网等 NAT 环境下大量用户共用一个出口地址，按 IP 收紧会互相挤占。状态在进程内存中，重启即清空，也不跨实例共享。

## 契约验证

线上有已安装的客户端，且 `/api/v1/app/bootstrap` 本身就是推送修复的通道，改动后必须逐项核对响应形状。用**独立的验证库**（不是正式库）起一个实例：

```powershell
psql -U postgres -c "CREATE DATABASE music_verify"   # 只需一次
node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify

$env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
$env:PORT="4720"; $env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"; $env:ADMIN_INITIAL_PASSWORD="verify-initial-123456"
$env:NODE_ENV="test"; $env:EMAIL_VERIFICATION_TEST_CODE="123456"
$env:IM_ENABLED="false"; $env:CORS_ALLOWED_ORIGINS="https://verify.example"
npm run build:frontend   # /admin 下的 CSP 与主题脚本断言需要 dist/public
npm run dev
# 另一个终端
node tools/verify-contract.mjs http://127.0.0.1:4720
```

启动环境的四个必填项，缺一个就会有成片断言失败：

- `ADMIN_INITIAL_PASSWORD` 必须与脚本读到的**同一个值**：脚本要用它完成默认管理员的首次登录与
  强制改密，拿不到管理会话后面所有管理端断言会连锁失败。
- `IM_ENABLED=false` 必须显式设置（`.env` 里通常是 `true`），否则「未启用 IM 时会话入口返回
  503/5031」会变成 502/5020。
- `CORS_ALLOWED_ORIGINS` 要包含 `https://verify.example`，否则「白名单来源回显自身 Origin」
  会失败（默认不下发任何 CORS 头，失败恰好证明这一点）。
- `EMAIL_VERIFICATION_TEST_CODE` 要同时给**脚本自己**的环境，脚本会读它做断言。

另外两个容易踩的坑：**每次运行前必须重置验证库**（版本/rollout 类断言依赖空库），以及
**桌面发布目录要清空**（`data/desktop`）。内容寻址存储在命中
已有对象时会走「删除临时文件」的分支，上一轮留下的对象会让上传路径和首次运行时不同。

以脚本实际输出为准，所有契约必须全绿（当前为 **259 项**）。脚本会核对状态码、业务码、信封形状、
NDJSON 行格式、字段类型（`data.id` 必须是 number、`songId` 必须是字符串、`createdAt` /
`configVersion` / `apkSize` 必须是 number）、纯文本歌词、Range 行为，以及「无效令牌访问
bootstrap 仍返回 200」这类红线。

管理端部分另有三组断言：**强制改密与 Bearer 会话准备**（初始口令登录 → 4031 拦截 → 改密 204 →
新口令重登 → 旧口令失效）、**39 条管理路由逐条无凭据探测**（全部 401/4013，漏标
`@AdminGuarded()` 即裸奔）、**账号退避 429/4291**（与来源地址限流的 4290 区分开，否则断言会
因 IP 限流先命中而变成假阳性）。

后面三组分别覆盖：PostgreSQL 迁移的四条硬规矩（并发注册、并发刷新同一令牌、停用版本不可下载、灰度分桶稳定）；搜索拆分后的新契约（60 首的耗时、`X-Accel-Buffering`、`favorited` / `vip` / `mid` 字段、批量收藏查询、`/link` 给的是上游直链、请求不存在的档位会降级、`/info` 过滤掉不存在的档位）；以及参数健壮性（`page=abc` 不会拼出 `page=NaN`、`quality=` 空串回落到 10 而不是 0）。

`reset-db.mjs` 会 `DROP SCHEMA public CASCADE`，所以它拒绝库名里不含 `verify` / `test` 的连接串 —— SQLite 时代「删掉那个文件」就够了，现在需要一个显式且带护栏的动作。

类型检查能抓住绝大部分同步改异步的漏改（漏 `await` 会得到 `Promise<T>` 与 `T` 不匹配），但**抓不住 `.find()` / `.map()` 回调里的漏改**，改动数据层后除了 `npx tsc --noEmit` 还要人工看一遍这些回调。

### Windows 桌面更新

桌面端使用独立的 `/api/v1/desktop/*` 路由和 `DESKTOP_RELEASE_DIR` 内容寻址目录：

- `GET /api/v1/desktop/bootstrap` 公开返回渠道、架构、版本、模块清单和可用差分；无效访问令牌不能让它变成 401。
- `GET/HEAD /api/v1/desktop/artifacts/{sha256}` 与 `/desktop/patches/{sha256}` 是裸字节 Range 下载，使用 ETag 和不可变缓存。
- `POST /api/v1/desktop/admin/artifacts` 接收原始模块（最多 500 MiB），`POST /desktop/admin/releases` 接收 1–512 项清单并生成差分。
- `POST /desktop/admin/rollout` 分阶段放量，只有存在完整模块且 100% 放量的版本才能抬高 `min-version`。

完整清单校验、发布命令、差分选择和错误码见 [wiki/10-desktop-release.md](wiki/10-desktop-release.md)。桌面后台不属于
`/app/admin/*`，但用的是同一个 `AdminAuthGuard`（只认管理员会话）。

### 悟空 IM

`/api/v1/im/*` 只负责会话凭据、联系人、会话/频道同步、撤回和已读；聊天正文不进入 PostgreSQL。
`IM_ENABLED=false` 时返回 503/5031。会话 Token 只保存 SHA-256，Gateway 地址必须是 `tcp://`，悟空产品 API
默认只在 `127.0.0.1:5001` 提供。端口、防火墙、同步参数和上线验收见 [wiki/09-wukongim.md](wiki/09-wukongim.md)。

### 当前实现审计提示

- `SEARCH_CONCURRENCY` 仍是类型化配置字段，但当前搜索已经不解析播放地址，因此不会读取它。
- `BSDIFF_BIN` 仍被读取以兼容旧配置，桌面差分实际使用 `bsdiff-wasm`，外部 bsdiff 不是运行前置条件。
- `vite.config.ts` 的开发代理默认目标是 `http://localhost:4500`，可用 `VITE_API_PROXY_TARGET` 覆盖；使用其它后端端口时先设置该变量再运行 `npm run dev:frontend`。
- `main.ts` 为历史兼容保留 `/desktop/admin/jars`、`/desktop/admin/patches` 原始体白名单，但当前没有对应 Controller；可调用的上传接口只有 `/desktop/admin/artifacts`。
