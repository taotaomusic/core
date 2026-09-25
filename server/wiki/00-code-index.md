# 后端代码索引（CodeGraph 基准）

[返回文档中心](README.md)

本页是后端源码、接口和数据库文档的导航基准。内容在 2026-09-17 按 `server/.codegraph` 索引和源码逐项核对，索引快照包含 **131 个文件、2,653 个节点、6,455 条边、104 条路由**。路由清单中的路径是 Controller 相对路径，除 `/health` 外实际都要加全局前缀 `/api/v1`。

## 1. 如何刷新索引

CodeGraph 数据库是本机生成物，不应提交 `codegraph.db`。源码变化后在项目根目录执行：

```powershell
codegraph index server
codegraph status server --json

# 查看所有路由（输出 104 条左右，数量变化意味着需要同步本页）
codegraph query --path server --kind route --limit 200 --json ""
```

索引状态为 `pendingChanges.added/modified/removed = 0` 且 `reindexRecommended = false` 时，下面的清单才可作为当前实现的快照。新增 Controller、迁移表或环境变量时，应先刷新索引，再更新本页和对应专题。

## 2. 模块地图

| 目录/文件 | 责任边界 | 主要依赖或持久化 |
| --- | --- | --- |
| `src/main.ts` | Nest 启动、按路由解析请求体、静态管理后台与分享页、全局前缀 | Express |
| `src/app.module.ts` | 组装全部业务模块和全局 Provider | Guard、Interceptor、Filter |
| `src/config/` | 环境变量加载、校验、类型化配置 | `@nestjs/config` |
| `src/common/` | 业务异常、鉴权、限流、信封、响应头、通用工具 | 进程内状态 |
| `src/database/` | PostgreSQL 连接池、INT8 解析、启动迁移、事务辅助 | PostgreSQL |
| `src/auth/` | 注册、邮箱验证码、登录、令牌轮换、资料和头像 | `users`、`refresh_tokens`、Lsky、SMTP |
| `src/mail/` | 验证码邮件发送与模板 | SMTP |
| `src/announcement/` | 公告读取、后台上下线和置顶 | `app_announcement` |
| `src/favorites/` | 收藏软删除、恢复和批量查询 | `favorites` |
| `src/playback/` | 播放会话幂等、最近播放、听歌统计和清空代际 | 4 张 playback 表 |
| `src/playlists/` | 云端歌单、快照、排序和完整替换 | `playlists`、`playlist_songs` |
| `src/music/` | 搜索、歌曲信息、直链、音频代理、歌词 | 上游 Client、`favorites` |
| `src/open-api/`（`OpenApiModule`） | 开放搜歌 API Key 鉴权与第三方搜歌端点：`open-api.module.ts`、`open-api-key.repository.ts`、`open-api-key.service.ts`（`OpenApiKeyService`）、`open-api-key.guard.ts`（`ApiKeyGuard`）、`open-api.controller.ts`（`OpenApiController`）、`open-api-key-admin.controller.ts`（管理端 key CRUD） | `open_api_key` 表（只存 `sha256(key)`），imports `MusicModule` + `UpstreamModule` + `AdminAuthModule` |
| `src/upstream/` | 腾讯/网易协议适配和错误收敛 | 外部音乐 API |
| `src/shares/` | 歌曲短链、公开元数据、试听转发 | `song_share`、`StreamService` |
| `src/image-generation/` | 图片任务、Key 池、额度预扣和状态轮询 | `api_key`、`image_generation_task`、ApiSweet |
| `src/release/` | Android APK/补丁、灰度、最低版本和远程配置 | `app_release`、`app_patch`、`app_channel`、`app_config` |
| `src/desktop-release/` | Windows 模块化发布、内容寻址文件和二进制差分 | `desktop_*`、Courgette/bsdiff-wasm |
| `src/im/` | 悟空 IM 会话凭据和同步代理 | `im_device_session`、悟空 IM HTTP API |
| `src/user-admin/` | 后台用户列表、播放统计、禁用和删除 | `users`、refresh/playback 外键级联 |
| `src/admin-auth/` | 管理后台账号、数据库会话、TOTP 2FA、角色守卫、IP 白名单和审计 | `admin_users`、`admin_sessions`、`admin_audit_log` |
| `src/ldap/` | LDAP/SSO 目录对接：Bind、Search、过滤器编解码、角色映射 | 企业目录、`admin_users` 同步 |
| `src/frontend/` | Vue 管理后台；发布时产到 `dist/public` | Vite、Element Plus |

全局 Provider 的实际职责如下：

| Provider | 当前行为 |
| --- | --- |
| `AccessTokenGuard` | 默认保护所有路由；`@Public()` 只允许“无令牌/无效令牌继续”，不绕过有效令牌解析；受保护路由失败固定为 401/4010 |
| `RateLimitGuard` | 读取 `@RateLimit()`，在 Controller 前执行用途限流；未登录的受保护限流路由返回 401 |
| `EnvelopeInterceptor` | 普通返回包装为 `{code:0,message:"success",data}`；`@RawResponse()` 和 `undefined` 不包装 |
| `LatestVersionHeaderInterceptor` | 依据已全量发布版本写 `X-Latest-Version-Code`/`X-Latest-Patch-Version`；进程内同步缓存，冷启动异步填充 |
| `SecurityHeadersInterceptor` | 写 CORS `*`、`nosniff`、`DENY`、`no-referrer` 和默认 `no-store` |
| `AllExceptionsFilter` | 将异常统一成业务信封；未知异常为 HTTP 502/5020；已发送响应的流只结束连接 |

全局 `APP_GUARD` 只有 `AccessTokenGuard` 和 `RateLimitGuard` 两个。

`AdminAuthGuard` 和 `RolesGuard` **不是全局守卫**，它们由 `AdminAuthModule` 提供。所有管理控制器
（`/admin/auth/**`、`/app/admin/**`、`/desktop/admin/**`）都挂 `AdminAuthGuard`，它只接受会话
`Authorization: Bearer`（静态 `X-Admin-Token` 通道已移除）；`RolesGuard` 再按 `@RequireRole` 判角色，
写操作还要注入 `AdminAuditService` 写审计。引用它们的模块必须自己
`imports: [AdminAuthModule]`，否则启动时 `UnknownDependenciesException`。
详见 [11-admin-auth.md](11-admin-auth.md)。

`ApiKeyGuard`（`src/open-api/open-api-key.guard.ts`）也不是全局守卫，它只挂在 `OpenApiModule`
的 `/open/**` 路由上：读取 `X-API-Key`（优先）或 `Authorization: Bearer tt_...`，校验开放
API Key，失败一律 401/4014（**不能 403**）。用户访问令牌（`payload.signature` 形状、不以
`tt_` 开头）与管理员会话都不能当开放 key。开放侧不经过 `AccessTokenGuard` 的用户令牌语义，
鉴权矩阵见 [03-api-contracts.md](03-api-contracts.md) §12、§17。

> `common/guards/admin-token.guard.ts` 里的 `AdminTokenGuard` **已删除**。它曾是死代码（全项目零引用），
> 不要因为历史文档或文件名像“管理后台守卫”就以为它还在生效。判断某个守卫是否生效，
> `grep` 它在 `@UseGuards` 里的实际引用。

## 3. 启动与请求链路

```text
读取 .env/系统环境变量
  → validateEnvironment
  → NestFactory.create({ bodyParser: false })
  → DatabaseService 等待 PostgreSQL（最多 10 次，每次 1 秒）
  → pg_advisory_xact_lock(913720001) + 幂等迁移
  → 挂载按路由分流的 JSON parser（普通 16KB、桌面清单 1MB、原始上传跳过）
  → /api/v1 全局前缀（/health 例外）
  → 全局 ValidationPipe(transform=true)
  → onApplicationBootstrap：AdminBootstrapService 创建默认管理员 admin（随机或 ADMIN_INITIAL_PASSWORD 口令，强制首登改密）
  → app.listen(PORT)
```

`main.ts` 的顶层代码在两个生命周期钩子**之前**执行，`onModuleInit` 早于
`onApplicationBootstrap`。因此默认管理员必须在 `onApplicationBootstrap` 创建，写进 `main.ts`
会先撞“关系 admin_users 不存在”。

当前原始请求体例外路径：

```text
POST /api/v1/app/admin/releases
POST /api/v1/app/admin/patches
POST /api/v1/desktop/admin/artifacts
```

`main.ts` 还保留了 `/api/v1/desktop/admin/jars` 和 `/api/v1/desktop/admin/patches` 的原始体路径白名单，但当前 Controller 没有对应路由；不要把这两个路径当作可调用接口。大文件上传必须走实际的 `artifacts` 路由。

静态资源：

- 管理后台：`/admin/`，构建目录 `dist/public`。
- 分享播放器：`/share/*` 资源和 `/s/{token}` 页面，构建目录 `dist/share-player`。
  入口 JS 与应用 wasm 都是**固定名**，两者必须严格同批（JS 胶水要提供 wasm 的全部 `js_code`
  导入），否则浏览器抛 `LinkError ... requires a callable`。所以服务端把它们挂成
  **版本化路径** `/share/v/<内容指纹>/…`，并改写 `index.html` 的 `<base>` 指向它，
  让所有相对引用自动跟版本走（见 `src/common/share-player-assets.ts`）。
  未版本化的 `/share/*` 保留，用于兼容历史页面与绝对路径引用。
- 资源不存在时后端仍可启动，只记录警告。

普通请求在 Guard → RateLimit → Controller → Service/Repository/上游 → Interceptor 的链路中运行。普通 JSON 请求体上限为 16KB；`POST /api/v1/desktop/admin/releases` 的桌面清单单独允许 1MB，原始 artifact 上传仍跳过 JSON 解析。任何跨网络调用都不得持有 PostgreSQL 连接；需要多条 SQL 原子化时使用 `DatabaseService.transaction`。

## 4. 完整路由索引

以下路径均省略 `/api/v1` 前缀；`/health` 是唯一例外。总数由 CodeGraph 当前索引统计为 104，
加上开放搜歌 API 新增的 8 条（4 条 `/open/**` + 4 条 `/app/admin/open-api-keys`）后应为 112，
下次刷新索引时以实际查询结果为准。

### 公开公告（1）

- `GET /announcements`：读取最新可见公告，置顶优先，最多 20 条。

### Android/后台聚合接口（30）

- `GET /app/bootstrap`：公开更新检查、补丁和远程配置。
- `GET /app/apk/:versionCode`、`HEAD /app/apk/:versionCode`：APK Range 下载/探测。
- `GET /app/patch/:targetVersionCode/:patchVersion`：Android 补丁下载。
- `GET /app/admin/releases`、`POST /app/admin/releases`：发布列表和原始 APK 登记。
- `POST /app/admin/rollout`：APK 灰度比例。
- `POST /app/admin/min-version`：Android 最低支持版本。
- `GET /app/admin/patches`、`POST /app/admin/patches`、`POST /app/admin/patch-rollout`：补丁登记和灰度。
- `GET /app/admin/config`、`POST /app/admin/config`：远程配置读写。
- `GET /app/admin/image-keys`、`POST /app/admin/image-keys`、`DELETE /app/admin/image-keys/:id`：图片 Key 池后台维护。
- `GET /app/admin/announcements`、`POST /app/admin/announcements`、`POST /app/admin/announcements/:id`、`POST /app/admin/announcements/:id/enabled`、`POST /app/admin/announcements/:id/pinned`、`DELETE /app/admin/announcements/:id`：公告管理。
- `GET /app/admin/users`、`GET /app/admin/users/:id/playback`、`POST /app/admin/users/:id/disabled`、`DELETE /app/admin/users/:id`：用户与播放统计管理。
- `GET /app/admin/open-api-keys`、`POST /app/admin/open-api-keys`、`PATCH /app/admin/open-api-keys/:id`、`DELETE /app/admin/open-api-keys/:id`：开放 API Key 列表、创建（明文只返回一次）、启停和吊销；走管理员会话，读 `READ_ROLES`，写 `WRITE_ROLES`。

### 认证与资料（13）

- `POST /auth/email-verification`、`POST /auth/register`：邮箱验证码和注册。
- `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`：会话生命周期。
- `GET /auth/me`、`GET /auth/profile`、`PATCH /auth/profile`：当前用户与资料。
- `POST /auth/avatar`：5 MiB 以内图片上传到 Lsky。
- `POST /auth/email/bind-verification`、`POST /auth/email/bind`：首次绑定邮箱。
- `POST /auth/email/change-verification`、`POST /auth/email/change`：换绑邮箱。

### 管理后台认证（15）

除 `login`、`totp-verify`、`logout` 外都挂 `@AdminGuarded()`（= `AdminAuthGuard` + `RolesGuard`）。
会话统一用 `Authorization: Bearer`。细节见 [11-admin-auth.md](11-admin-auth.md)。

- `POST /admin/auth/login`、`POST /admin/auth/totp-verify`、`POST /admin/auth/logout`：公开；登录时开了
  TOTP 的账号只拿到 `temp_token`，第二步必须出示它。
- `GET /admin/auth/me`、`POST /admin/auth/change-password`：当前管理员信息与改密。
- `POST /admin/auth/totp-enable`、`POST /admin/auth/totp-confirm`、`POST /admin/auth/totp-disable`：2FA 生命周期。
- `GET /admin/auth/users`、`POST /admin/auth/users`、`PATCH /admin/auth/users/:id`、`DELETE /admin/auth/users/:id`：
  管理员增删改查；读取需 `admin` 以上，写入需 `super_admin`。
- `GET /admin/auth/audit-log`：操作审计查询。
- `GET /admin/auth/ip-whitelist/:adminId`、`POST /admin/auth/ip-whitelist/:adminId`：IP 白名单读写，仅 `super_admin`。

### Windows 桌面发布（10）

- `GET /desktop/bootstrap`：按渠道、架构和当前版本返回模块清单及可用差分。
- `GET/HEAD /desktop/artifacts/:sha256`：内容寻址模块文件。
- `GET/HEAD /desktop/patches/:sha256`：内容寻址差分文件。
- `GET /desktop/admin/releases`：后台查看桌面版本。
- `POST /desktop/admin/artifacts`：上传单个原始模块（最多 500 MiB）。
- `POST /desktop/admin/releases`：提交版本清单并生成差分。
- `POST /desktop/admin/rollout`：桌面版本灰度/启用。
- `POST /desktop/admin/min-version`：桌面最低支持版本。

### 音乐与图片（8）

- `GET /search`：裸 NDJSON 搜索。
- `GET /songs/:id/link`、`GET /songs/:id/info`、`GET /songs/batch-info`：直链、音质和批量信息。
- `GET /songs/:id/play`：Range 音频代理。
- `GET /songs/:id/lyrics`：纯文本或 `format=json` 歌词。
- `POST /draw/completions`、`GET /draw/result/:taskId`：图片任务创建/轮询。

### 开放搜歌（4）

第三方用 API Key 调用（`X-API-Key` 或 `Bearer tt_...`），`ApiKeyGuard` 鉴权，失败 401/4014。

- `GET /open/search`：统一信封，`data = { songs, meta }`。
- `GET /open/search/stream`：裸 NDJSON（`@RawResponse`），格式同内部 `/search`。
- `GET /open/songs/:id/lyrics`：默认 `text/plain`，`format=json` 走信封。
- `GET /open/songs/:id/link`：信封，`data = { songId, url, quality, requestedQuality, kbps, fallback }`。

### 收藏、歌单、播放（20）

- 收藏：`GET /favorites`、`POST /favorites/:source/:songId`、`DELETE /favorites/:source/:songId`。
- 歌单：`GET /playlists`、`POST /playlists`、`GET /playlists/:playlistId`、`GET /playlists/:playlistId/songs`、`PATCH/PUT /playlists/:playlistId`、`DELETE /playlists/:playlistId`、`POST /playlists/:playlistId/songs`、`DELETE /playlists/:playlistId/songs/:source/:songId`、`PATCH/PUT /playlists/:playlistId/songs/order`、`PUT /playlists/:playlistId/songs`。
- 播放：`POST /playback/sessions`、`GET /playback/recent`、`GET /playback/recent/state`、`GET /playback/stats`、`DELETE /playback/recent`。

### 分享与 IM（10）

- 分享：`POST /shares/songs`、`GET /public/shares/:token`、`GET /public/shares/:token/preview`。
- IM 会话/同步：`POST /im/session`、`DELETE /im/session`、`POST /im/sync/conversations`、`POST /im/sync/channel-messages`、`POST /im/messages/revoke`、`GET /im/contacts`、`POST /im/conversations/read`。

### 系统（1）

- `GET /health`：数据库可用时返回健康状态，不套 `/api/v1`。

## 5. 鉴权、公开路由与限流

| 用途 | 鉴权 | 限制 |
| --- | --- | --- |
| 注册/登录 | `@Public()` | 每 IP 每用途 10 次/15 分钟 |
| 邮箱发码 | `@Public()` | 每 IP 5 次/15 分钟；同邮箱 60 秒冷却；验证码最多 5 次错误 |
| Android/桌面 bootstrap、APK/模块下载、公告和公开分享 | 公开；无效 Authorization 也不能变 401 | App 桶：每 IP 900 次 + 每设备/来源 60 次/15 分钟 |
| 普通音乐、收藏、歌单、播放、资料、分享创建 | 桃桃访问令牌 | 默认无独立桶；由全局门禁保护 |
| 图片创建 | 桃桃访问令牌 | 每用户 10 次 + 每 IP 60 次/15 分钟 |
| 图片轮询 | 桃桃访问令牌 | 每用户 300 次 + 每 IP 1800 次/15 分钟 |
| IM 会话 | 桃桃访问令牌 | 每用户 30 次 + 每 IP 180 次/15 分钟 |
| IM 同步/撤回/已读 | 桃桃访问令牌 | 每用户 300 次 + 每 IP 1800 次/15 分钟 |
| Android/桌面后台、公告、图片 Key（读） | `AdminAuthGuard` + `RolesGuard`，`READ_ROLES` | 每 IP 60 次/15 分钟；两条凭据都不可用时 401/4013；角色不足 403/4030 |
| 用户资料与听歌历史（读） | 同上，`PRIVILEGED_READ_ROLES` | 同上；观察者看不到个人数据 |
| 同上（写：发版、放量、改配置、公告、用户、密钥） | 同上，`WRITE_ROLES` | 同上；观察者被拒，写操作另写 `admin_audit_log` |
| 管理后台登录、2FA、改密码 | `@Public()` 或已认证 | 每 IP 10 次/15 分钟；`admin-login`/`admin-totp`/`admin-password` 三个独立桶 |
| 管理后台其它接口 | 管理员会话 `Authorization: Bearer` | 默认无独立桶；再按角色判 403/4030 |
| 开放搜歌 `/open/**` | 不要求桃桃访问令牌；用 `X-API-Key` 或 `Bearer tt_...`（`ApiKeyGuard`） | `open-api` 桶：每 key 120 次 + 每来源地址 600 次/15 分钟；失败 401/4014，超限 429/4290 |
| 开放 API Key 管理 `/app/admin/open-api-keys` | 管理员会话；读 `READ_ROLES`，写 `WRITE_ROLES` | 同管理端 `admin` 桶（每 IP 60 次/15 分钟）；写操作记 `open_api_key.*` 审计 |

限流器是进程内滑动窗口，重启清空，多实例不共享。不能把它当成跨实例的安全配额。

## 6. 数据模型速览

迁移当前创建 25 张表：

```text
users                         refresh_tokens
favorites                     playlists                 playlist_songs
song_share                    playback_sessions         user_song_stats
playback_history_state        playback_history_clear_operation
app_release                   app_channel               app_config
app_announcement              api_key                    image_generation_task
im_device_session             app_patch                  desktop_release
desktop_jar                   desktop_patch
admin_users                   admin_sessions            admin_audit_log
open_api_key
```

主要外键和删除语义：

- 用户删除会级联刷新令牌、收藏、歌单、分享、播放和 IM 设备凭据。
- `image_generation_task.api_key_id` 使用 `ON DELETE RESTRICT`，仍有任务引用时不能删除 Key。
- `desktop_jar`/`desktop_patch` 随 `desktop_release` 级联删除；磁盘内容寻址文件不会自动回收。
- 播放清空只推进 `playback_history_state.revision`，不删除累计统计；每个 marker 另存于 `playback_history_clear_operation` 保证重试幂等。
- `admin_sessions.admin_id` 随 `admin_users` 级联删除；`admin_audit_log.admin_id` 与 `admin_users.created_by` 是 `ON DELETE SET NULL`，管理员被删后审计仍保留、只是变成无归属。
- `open_api_key` 只存 `sha256(key)`，明文 key 只在创建响应里返回一次；列表接口只回传 `keyPrefix`。

所有 `Date.now()` 语义的字段使用 `bigint`，版本/大小/百分比使用 `integer`，历史布尔值使用 `smallint` 0/1。SQL 返回 TypeScript camelCase 时必须给别名加双引号。

## 7. 配置索引

| 变量 | 默认值/必需 | 用途 |
| --- | --- | --- |
| `PORT` | `4500` | HTTP 监听端口 |
| `ENV_FILE` | `.env` | 指定其它 dotenv 文件路径；系统环境变量优先于文件 |
| `DATABASE_URL` | 必需 | PostgreSQL 连接串 |
| `AUTH_SECRET` | **必需，至少 32 字符** | 访问令牌 HMAC；没有开发兜底，缺失或过短直接启动失败 |
| `ADMIN_INITIAL_PASSWORD` | 空 | 默认超管初始口令；留空则随机生成并只打印一次。至少 12 字符 |
| `CORS_ALLOWED_ORIGINS` | 空 | 允许跨域的来源白名单；留空不下发任何 CORS 头 |
| `APK_DIR` | `./data/apk` | Android APK/补丁文件 |
| `DESKTOP_RELEASE_DIR` | `./data/desktop` | 桌面内容寻址文件和差分 |
| `COURGETTE_PATH` | 空 | PE 文件 Courgette 差分，可选 |
| `DEFAULT_CHANNEL` | `release` | 默认发布渠道 |
| `PUBLIC_BASE_URL` | 空（按请求推导） | 公开下载/分享地址 |
| `SEARCH_CONCURRENCY` | `8` | 配置字段仍保留；当前搜索实现不读取它，不要误以为能改变请求并发 |
| `APISWEET_BASE_URL` | `https://apisweet.com` | 图片上游 |
| `LSKY_UPLOAD_URL` / `LSKY_API_KEY` | URL 有默认，Key 空 | 头像上传 |
| `SMTP_HOST` | 空 | SMTP 主机 |
| `SMTP_PORT` | `587` | SMTP 端口 |
| `SMTP_USER` | 空 | SMTP 用户 |
| `SMTP_PASSWORD` | 空 | SMTP 密码；不得提交 |
| `SMTP_FROM` | 空 | 发件人地址 |
| `IM_ENABLED` | `false` | 是否启用 IM |
| `IM_INTERNAL_API_BASE_URL` | `http://127.0.0.1:5001` | 悟空 IM 产品 HTTP API |
| `IM_EXTERNAL_GATEWAY_URL` | `tcp://im.xydaigua.cn:5100` | 下发给 Android 的 TCP Gateway |
| `IM_API_TOKEN` | 空 | 悟空 IM 服务端 API Token |
| `IM_SESSION_LIFETIME_SECONDS` | `900`，启用时 60–86400 | IM 会话凭据有效期 |
| `EMAIL_VERIFICATION_TEST_CODE` | 空，仅 `NODE_ENV=test` | 契约测试固定验证码 |
| `TOTP_ISSUER` | `桃桃音乐管理后台` | 管理员 2FA 在验证器里显示的名称 |
| `LDAP_URL` / `LDAP_BIND_DN` / `LDAP_BIND_PASSWORD` / `LDAP_USER_SEARCH_BASE` | 空 | LDAP 对接的最小必填组，三者缺一即视为未配置 |
| `LDAP_USER_SEARCH_FILTER` | `(uid={{username}})` | 用户搜索过滤器，`{{username}}` 会被转义后替换 |
| `LDAP_GROUP_SEARCH_BASE` / `LDAP_GROUP_SEARCH_FILTER` | 空 | 组搜索；过滤器可用 `{{userDn}}` |
| `LDAP_ROLE_MAPPING` | 空 | JSON 对象，LDAP 组 DN → 角色；解析失败退化为空映射并告警 |
| `LDAP_TLS_REJECT_UNAUTHORIZED` | `true` | 只有显式 `false`/`0`/`no`/`off` 才关闭 LDAPS 证书校验 |
| `LDAP_TIMEOUT_MS` | `10000` | 单次 LDAP 操作超时；连接和搜索都受它约束 |
| `TRUST_PROXY` | 关闭 | 只有 `1`/`true` 才采信 `X-Forwarded-For`；否则用 `socket.remoteAddress` |

`BSDIFF_BIN` 在 `AppConfigService` 中仍有兼容字段，但当前差分实现直接使用 `bsdiff-wasm`；不要把它写成部署必需项。生产配置不要提交 SMTP 密码、Lsky Key、IM Token 或数据库凭据。

## 8. 构建、验证和文档同步

```powershell
cd server
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
npx tsc -p tsconfig.json --noEmit
git diff --check
```

数据层改动的最小闭环是：独立 `music_verify`/`music_test` 数据库 → `tools/reset-db.mjs` → 重启服务执行迁移 → `verify-contract.mjs` 全绿。更新路由或配置时同时修改：

1. 本页索引；
2. `01-architecture.md`（模块/链路）；
3. `03-api-contracts.md`（客户端可见契约）；
4. `04-database.md`（表、索引、事务）；
5. `02-development.md` 与 `.env.example`（配置/命令）；
6. `06-release-deployment.md`、`07-troubleshooting.md`（运维路径）；
7. 改动 `admin-auth/` 或 `ldap/` 时同步 `11-admin-auth.md`（会话、2FA、角色、白名单、审计）。

本页是索引，不替代专题中的参数细节；当本页与源码冲突时，以源码和 CodeGraph 最新索引为准，并在同一提交中修正文档。
