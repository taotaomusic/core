# 后端代码索引(CodeGraph 基准)

[返回文档中心](README.md)

最后更新:2026-09-30(路由/表清单核对日:2026-09-30)

本页是后端源码、接口和数据库文档的导航基准。路由清单中的路径是 Controller 相对路径,除 `/health` 外实际都要加全局前缀 `/api/v1`;文件/节点/路由的具体数量以 `codegraph status` 与 `codegraph query` 实测为准,不要引用本文历史快照的数字。

## 1. 如何刷新索引

CodeGraph 数据库是本机生成物,不应提交 `codegraph.db`。源码变化后在项目根目录执行:

```powershell
codegraph index server
codegraph status server --json

# 查看所有路由(输出 120+ 条,数量变化意味着需要同步本页)
codegraph query --path server --kind route --limit 200 --json ""
```

索引状态为 `pendingChanges.added/modified/removed = 0` 且 `reindexRecommended = false` 时,下面的清单才可作为当前实现的快照。新增 Controller、迁移表或环境变量时,应先刷新索引,再更新本页和对应专题。

## 2. 模块地图

| 目录/文件 | 责任边界 | 主要依赖或持久化 |
| --- | --- | --- |
| `src/main.ts` | Nest 启动、传输加密中间件挂载(先于 body parser)、按路由解析请求体、静态管理后台与分享页、全局前缀 | Express |
| `src/app.module.ts` | 组装全部业务模块和全局 Provider | Guard、Interceptor、Filter |
| `src/config/` | 环境变量加载、校验、类型化配置 | `@nestjs/config` |
| `src/common/` | 业务异常、鉴权、限流、信封、响应头、通用工具 | 进程内状态 |
| `src/database/` | PostgreSQL 连接池、INT8 解析、启动迁移、事务辅助 | PostgreSQL |
| `src/crypto/`(`CryptoModule`) | 传输层加密:`crypto.controller.ts` 的 `POST /crypto/handshake` 握手、`crypto.middleware.ts` 的 AEAD 逐块解密(挂载在 body parser 之前)、`crypto-transport.service.ts`(原生引擎加载、PSK 登记、会话清理)、`native-loader.ts` | `crypto/dist` 原生产物、`CRYPTO_PSK_ID/HEX` |
| `src/auth/` | 注册、邮箱验证码、登录、令牌轮换、资料和头像 | `users`、`refresh_tokens`、SMTP、`user_avatars`(经 `FilesModule` 的 `AvatarStoreService`) |
| `src/mail/` | 验证码邮件发送与模板 | SMTP |
| `src/announcement/` | 公告读取、后台上下线和置顶 | `app_announcement` |
| `src/favorites/` | 收藏软删除、恢复和批量查询 | `favorites` |
| `src/files/`(`FilesModule`) | 用户文件公开下载:头像存取与匿名只读端点 | `user_avatars`、`AvatarStoreService` |
| `src/playback/` | 播放会话幂等、最近播放、听歌统计和清空代际 | 4 张 playback 表 |
| `src/playlists/` | 云端歌单、快照、排序和完整替换 | `playlists`、`playlist_songs` |
| `src/music/` | 搜索、歌曲信息、直链、音频代理、歌词 | 上游 Client、`favorites` |
| `src/open-api/`(`OpenApiModule`) | 开放搜歌 API Key 鉴权与第三方搜歌端点:`open-api.module.ts`、`open-api-key.repository.ts`、`open-api-key.service.ts`(`OpenApiKeyService`)、`open-api-key.guard.ts`(`ApiKeyGuard`)、`open-api.controller.ts`(`OpenApiController`)、`open-api-key-admin.controller.ts`(管理端 key CRUD) | `open_api_key` 表(只存 `sha256(key)`),imports `MusicModule` + `UpstreamModule` + `AdminAuthModule` |
| `src/upstream/` | 多音源协议适配和错误收敛:腾讯/网易/酷我客户端、波点(Bodian)客户端与 KPK 签名复刻、`music-source-account.repository.ts` 音源账号凭据、`music-source.registry.ts` 注册表分派、`music-source-admin.controller.ts` 音源账号后台管理 | 外部音乐 API、`music_source_account` |
| `src/shares/` | 歌曲短链、公开元数据、试听转发 | `song_share`、`StreamService` |
| `src/image-generation/` | 图片任务、Key 池、额度预扣和状态轮询 | `api_key`、`image_generation_task`、ApiSweet |
| `src/release/` | Android APK/补丁、灰度、最低版本、远程配置、发版 webhook 与桌面更新代理 | `app_release`、`app_patch`、`app_channel`、`app_config` |
| `src/im/` | 悟空 IM 会话凭据和同步代理 | `im_device_session`、悟空 IM HTTP API |
| `src/user-admin/` | 后台用户列表、播放统计、禁用和删除 | `users`、refresh/playback 外键级联 |
| `src/admin-auth/` | 管理后台账号、数据库会话、TOTP 2FA、角色守卫、IP 白名单和审计 | `admin_users`、`admin_sessions`、`admin_audit_log` |
| `src/ldap/` | LDAP/SSO 目录对接:Bind、Search、过滤器编解码、角色映射 | 企业目录、`admin_users` 同步 |
| `src/tools/` | 独立维护工具(随 tsc 编进 dist,服务器上 `node dist/tools/…` 执行):`backfill-playlist-covers.ts` 按音源回填 `playlist_songs.cover_url`,幂等、只补空值 | `playlist_songs`、`MusicSourceRegistry`、上游 API |
| `src/frontend/` | Vue 管理后台;发布时产到 `dist/public` | Vite、Element Plus |

各模块的边界规则与依赖注入约束见 [11-architecture-modules.md](11-architecture-modules.md);专题细节:图片 [50-feature-image-generation.md](50-feature-image-generation.md)、IM [51-feature-wukongim.md](51-feature-wukongim.md)、音源账号 [53-feature-music-sources.md](53-feature-music-sources.md)、传输加密 [37-api-crypto.md](37-api-crypto.md)。

全局 Provider 的实际职责如下:

| Provider | 当前行为 |
| --- | --- |
| `AccessTokenGuard` | 默认保护所有路由;`@Public()` 只允许「无令牌/无效令牌继续」,不绕过有效令牌解析;受保护路由失败固定为 401/4010 |
| `RateLimitGuard` | 读取 `@RateLimit()`,在 Controller 前执行用途限流;未登录的受保护限流路由返回 401 |
| `EnvelopeInterceptor` | 普通返回包装为 `{code:0,message:"success",data}`;`@RawResponse()` 和 `undefined` 不包装 |
| `LatestVersionHeaderInterceptor` | 依据已全量发布版本写 `X-Latest-Version-Code`/`X-Latest-Patch-Version`;进程内同步缓存,冷启动异步填充 |
| `SecurityHeadersInterceptor` | CORS 白名单回显 + `Vary: Origin`(无条件下发 `*` 已移除;音视频流与搜索在各自路由里另行设置 `*`)、`nosniff`、`DENY`、`no-referrer` 和默认 `no-store` |
| `AllExceptionsFilter` | 将异常统一成业务信封;未知异常为 HTTP 502/5020;已发送响应的流只结束连接 |

全局 `APP_GUARD` 只有 `AccessTokenGuard` 和 `RateLimitGuard` 两个。

`AdminAuthGuard` 和 `RolesGuard` **不是全局守卫**,它们由 `AdminAuthModule` 提供。所有管理控制器(`/admin/auth/**`、`/app/admin/**`)都挂 `AdminAuthGuard`,它只接受会话 `Authorization: Bearer`(静态 `X-Admin-Token` 通道已移除);`RolesGuard` 再按 `@RequireRole` 判角色,写操作还要注入 `AdminAuditService` 写审计。引用它们的模块必须自己 `imports: [AdminAuthModule]`,否则启动时 `UnknownDependenciesException`。详见 [82-admin-routes-data.md](82-admin-routes-data.md)。

`ApiKeyGuard`(`src/open-api/open-api-key.guard.ts`)也不是全局守卫,它只挂在 `OpenApiModule` 的 `/open/**` 路由上:读取 `X-API-Key`(优先)或 `Authorization: Bearer tt_...`,校验开放 API Key,失败一律 401/4014(**不能 403**)。用户访问令牌(`payload.signature` 形状、不以 `tt_` 开头)与管理员会话都不能当开放 key。开放侧细节见 [36-api-open.md](36-api-open.md)。

> `common/guards/admin-token.guard.ts` 里的 `AdminTokenGuard` **已删除**。它曾是死代码(全项目零引用),不要因为历史文档或文件名像「管理后台守卫」就以为它还在生效。判断某个守卫是否生效,`grep` 它在 `@UseGuards` 里的实际引用。

## 3. 启动与请求链路

```text
读取 .env/系统环境变量
  → validateEnvironment
  → NestFactory.create({ bodyParser: false })
  → 挂载传输加密中间件(带 X-Taotao-Crypto 头的请求先做 AEAD 解密;必须先于 body parser,
    否则拿到的是密文;无加密头请求完全透明。排除 /api/v1 之外的静态路径、RAW_BODY_PATHS
    与 */play 音频流;强制加密白名单内的接口拒绝明文降级,见下)
  → 挂载按路由分流的 JSON parser(普通 16KB、原始上传跳过)
  → 挂载 /admin 与 /share 静态资源(CSP 中间件在 expressStatic 之前)
  → /api/v1 全局前缀(/health 例外)
  → 全局 ValidationPipe(transform=true)
  → app.listen(PORT)
      ├─ app.init()
      │    ├─ onModuleInit:DatabaseService 等待 PostgreSQL(最多 10 次,每次 1 秒)
      │    │    → pg_advisory_xact_lock(913720001) + 幂等迁移
      │    └─ onApplicationBootstrap:AdminBootstrapService 创建默认管理员 admin
      │         (随机或 ADMIN_INITIAL_PASSWORD 口令,强制首登改密);
      │         CryptoTransportService 加载原生产物、登记 PSK、启动会话清理
      └─ 端口开始接受连接
```

完整时序与三个启动时间点的区别见 [13-startup-lifecycle.md](13-startup-lifecycle.md);守卫/拦截器的执行顺序见 [12-request-pipeline.md](12-request-pipeline.md)。

加密中间件带一份**强制加密白名单**:链路已启用时,命中白名单且未带加密头的明文请求被拒绝(HTTP 426/4007,提示升级客户端),防止降级攻击。当前白名单只有 `GET /api/v1/favorites`。`CRYPTO_REQUEST_LOG`(默认开,设 `off` 关闭)会为每个 `/api/v1` 请求打一行「明文/加密」日志。

当前原始请求体例外路径:

```text
POST /api/v1/app/admin/releases
POST /api/v1/app/admin/patches
```

静态资源:

- 管理后台:`/admin/`,构建目录 `dist/public`;CSP 见 [83-admin-frontend.md](83-admin-frontend.md)。
- 分享播放器:`/share/*` 资源和 `/s/{token}` 页面,构建目录 `dist/share-player`。入口 JS 与应用 wasm 都是**固定名**,两者必须严格同批(JS 胶水要提供 wasm 的全部 `js_code` 导入),否则浏览器抛 `LinkError ... requires a callable`。所以服务端把它们挂成**版本化路径** `/share/v/<内容指纹>/…`,并改写 `index.html` 的 `<base>` 指向它,让所有相对引用自动跟版本走(见 `src/common/share-player-assets.ts`)。未版本化的 `/share/*` 保留,用于兼容历史页面与绝对路径引用。
- 资源不存在时后端仍可启动,只记录警告。

普通 JSON 请求体上限为 16KB;原始 artifact 上传跳过 JSON 解析。任何跨网络调用都不得持有 PostgreSQL 连接;需要多条 SQL 原子化时使用 `DatabaseService.transaction`。

## 4. 完整路由索引

以下路径均省略 `/api/v1` 前缀;`/health` 是唯一例外。总数以 CodeGraph 实测为准(2026-10-01 手工逐 Controller 核对 120 条),不要引用历史快照数字。各域接口的字段与语义细节见 30–37 各篇。

### 公开公告(1)

- `GET /announcements`:读取最新可见公告,置顶优先,最多 20 条。

### Android/后台聚合接口(40)

- `GET /app/bootstrap`:公开更新检查、补丁和远程配置(契约见 [61-release-android.md](61-release-android.md))。
- `GET /app/apk/:versionCode`、`HEAD /app/apk/:versionCode`:APK Range 下载/探测。
- `GET /app/patch/:targetVersionCode/:patchVersion`:Android 补丁下载。
- `GET /app/admin/releases`、`POST /app/admin/releases`:发布列表和原始 APK 登记。GET 不带分页参数返回全量数组(旧形状,宿主版本下拉依赖);带 `page`/`pageSize`(`pageSize` 上限 100)返回 `{ items, total, rolledOut }` 信封。
- `POST /app/admin/rollout`:APK 灰度比例。
- `POST /app/admin/release-edit`:编辑发布记录的更新说明与安装包外链(传了才更新,外链必须 http(s),空串回落本机端点)。
- `POST /app/admin/min-version`:Android 最低支持版本。
- `GET /app/admin/patches`、`POST /app/admin/patches`、`POST /app/admin/patch-rollout`:补丁登记和灰度。
- `GET /app/admin/config`、`POST /app/admin/config`:远程配置读写。
- `GET /app/admin/image-keys`、`POST /app/admin/image-keys`、`DELETE /app/admin/image-keys/:id`:图片 Key 池后台维护(删除成功 204,被任务引用 404/4042)。
- `GET /app/admin/announcements`、`POST /app/admin/announcements`、`POST /app/admin/announcements/:id`、`POST /app/admin/announcements/:id/enabled`、`POST /app/admin/announcements/:id/pinned`、`DELETE /app/admin/announcements/:id`:公告管理。
- `POST /app/admin/users`、`GET /app/admin/users`、`GET /app/admin/users/:id/playback`、`POST /app/admin/users/:id/disabled`、`DELETE /app/admin/users/:id`:用户管理(后台创建免邮箱验证码,审计 `user.create`)与播放统计。
- `GET /app/admin/open-api-keys`、`POST /app/admin/open-api-keys`、`PATCH /app/admin/open-api-keys/:id`、`DELETE /app/admin/open-api-keys/:id`:开放 API Key 列表、创建(明文只返回一次)、启停和吊销;走管理员会话,读 `READ_ROLES`,写 `WRITE_ROLES`(见 [36-api-open.md](36-api-open.md))。
- `GET /app/admin/music-sources`、`GET /app/admin/music-sources/available`、`POST /app/admin/music-sources`、`PATCH /app/admin/music-sources/:id`、`PUT /app/admin/music-sources/:id/enabled`、`POST /app/admin/music-sources/:id/probe`、`DELETE /app/admin/music-sources/:id`、`POST /app/admin/music-sources/sms`、`POST /app/admin/music-sources/login`:音源账号(酷我/波点)后台管理,细节见 [53-feature-music-sources.md](53-feature-music-sources.md)。

### 发版分发(2)

- `POST /app/github-webhook`:GitHub 发版 webhook(`X-Hub-Signature-256` 验签),收到 Release 事件后拉 `metadata.json` 自动登记安卓版本(机制见 [62-ci-cloud-build.md](62-ci-cloud-build.md))。
- `GET /desktop/updater/latest.json`:Tauri 桌面更新清单代理,只改写未签名 url,签名校验不受影响(见 [91-client-desktop.md](91-client-desktop.md))。

### 认证与资料(13)

- `POST /auth/email-verification`、`POST /auth/register`:邮箱验证码和注册。
- `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout`:会话生命周期。
- `GET /auth/me`、`GET /auth/profile`、`PATCH /auth/profile`:当前用户与资料。
- `POST /auth/avatar`:5 MiB 以内图片按文件头嗅探后存入 `user_avatars`。
- `POST /auth/email/bind-verification`、`POST /auth/email/bind`:首次绑定邮箱。
- `POST /auth/email/change-verification`、`POST /auth/email/change`:换绑邮箱。

契约见 [31-api-auth-user.md](31-api-auth-user.md)。

### 管理后台认证(15)

除 `login`、`totp-verify`、`logout` 外都挂 `@AdminGuarded()`(= `AdminAuthGuard` + `RolesGuard`)。会话统一用 `Authorization: Bearer`。细节见 [80-admin-auth-login.md](80-admin-auth-login.md) 与 [82-admin-routes-data.md](82-admin-routes-data.md)。

- `POST /admin/auth/login`、`POST /admin/auth/totp-verify`、`POST /admin/auth/logout`:公开;登录时开了 TOTP 的账号只拿到 `temp_token`,第二步必须出示它。
- `GET /admin/auth/me`、`POST /admin/auth/change-password`:当前管理员信息与改密。
- `POST /admin/auth/totp-enable`、`POST /admin/auth/totp-confirm`、`POST /admin/auth/totp-disable`:2FA 生命周期。
- `GET /admin/auth/users`、`POST /admin/auth/users`、`PATCH /admin/auth/users/:id`、`DELETE /admin/auth/users/:id`:管理员增删改查;读取需 `admin` 以上,写入需 `super_admin`。
- `GET /admin/auth/audit-log`:操作审计查询。
- `GET /admin/auth/ip-whitelist/:adminId`、`POST /admin/auth/ip-whitelist/:adminId`:IP 白名单读写,仅 `super_admin`。

### 音乐与图片(10)

- `GET /search`:裸 NDJSON 搜索。
- `GET /search/suggestions`:搜索联想词(默认酷我源)。
- `GET /search/hot`:热搜词(默认酷我源)。
- `GET /songs/:id/link`、`GET /songs/:id/info`、`GET /songs/batch-info`:直链、音质和批量信息。
- `GET /songs/:id/play`:Range 音频代理。
- `GET /songs/:id/lyrics`:纯文本或 `format=json` 歌词。
- `POST /draw/completions`、`GET /draw/result/:taskId`:图片任务创建/轮询。

契约见 [32-api-search-music.md](32-api-search-music.md) 与 [50-feature-image-generation.md](50-feature-image-generation.md)。

### 开放搜歌(4)

第三方用 API Key 调用(`X-API-Key` 或 `Bearer tt_...`),`ApiKeyGuard` 鉴权,失败 401/4014。契约见 [36-api-open.md](36-api-open.md)。

- `GET /open/search`:统一信封,`data = { songs, meta }`。
- `GET /open/search/stream`:裸 NDJSON(`@RawResponse`),格式同内部 `/search`。
- `GET /open/songs/:id/lyrics`:默认 `text/plain`,`format=json` 走信封。
- `GET /open/songs/:id/link`:信封,`data = { songId, url, quality, requestedQuality, kbps, fallback }`。

### 收藏、歌单、播放(21)

- 收藏:`GET /favorites`、`POST /favorites/:source/:songId`、`DELETE /favorites/:source/:songId`。
- 歌单:`GET /playlists`、`POST /playlists`、`GET /playlists/:playlistId`、`GET /playlists/:playlistId/songs`、`PATCH/PUT /playlists/:playlistId`、`DELETE /playlists/:playlistId`、`POST /playlists/:playlistId/songs`、`DELETE /playlists/:playlistId/songs/:source/:songId`、`PATCH/PUT /playlists/:playlistId/songs/order`、`PUT /playlists/:playlistId/songs`。
- 播放:`POST /playback/sessions`、`GET /playback/recent`、`GET /playback/recent/state`、`GET /playback/stats`、`GET /playback/diary`(单曲倒带日记:按天峰值、年度/半年会话数、最近会话明细)、`DELETE /playback/recent`。

契约见 [33-api-playlists.md](33-api-playlists.md) 与 [34-api-playback.md](34-api-playback.md)。

### 分享与 IM(10)

- 分享:`POST /shares/songs`、`GET /public/shares/:token`、`GET /public/shares/:token/preview`。契约见 [35-api-shares.md](35-api-shares.md)。
- IM 会话/同步:`POST /im/session`、`DELETE /im/session`、`POST /im/sync/conversations`、`POST /im/sync/channel-messages`、`POST /im/messages/revoke`、`GET /im/contacts`、`POST /im/conversations/read`。契约见 [51-feature-wukongim.md](51-feature-wukongim.md)。

### 传输加密(2)

- `GET /crypto/psk`:把当前 PSK 下发给**已登录**客户端(不加 `@Public()`,受全局访问令牌守卫保护);客户端据此完成握手,密钥不再内嵌于 App。未启用加密(产物缺失或协议版本过低)固定 503/5031。
- `POST /crypto/handshake`:`@Public()` 握手,交换加密会话。未启用(产物缺失或协议版本过低)固定 503/5031;被拒绝(device_id 不匹配、报文非法)400/4013。协议见 [37-api-crypto.md](37-api-crypto.md)。

### 用户文件(1)

- `GET /files/avatars/:token`:`@Public()` 匿名头像下载(渲染方不携带访问令牌);token 是每次上传随机生成的 128 位十六进制,不可枚举,头像更新后旧地址 404/4040,响应带 `immutable` 缓存头。

### 系统(1)

- `GET /health`:数据库可用时返回健康状态,不套 `/api/v1`;响应形如 `{"code":0,"message":"success","data":{"status":"up"}}`。

## 5. 鉴权、公开路由与限流

| 用途 | 鉴权 | 限制 |
| --- | --- | --- |
| 注册/登录 | `@Public()` | 每 IP 每用途 10 次/15 分钟 |
| 邮箱发码 | `@Public()` | 每 IP 5 次/15 分钟;同邮箱 60 秒冷却;验证码最多 5 次错误 |
| Android bootstrap、APK 下载、公告和公开分享 | 公开;无效 Authorization 也不能变 401 | App 桶:每 IP 900 次 + 每设备/来源 60 次/15 分钟 |
| 普通音乐、收藏、歌单、播放、资料、分享创建 | 桃桃访问令牌 | 默认无独立桶;由全局门禁保护 |
| 图片创建 | 桃桃访问令牌 | 每用户 10 次 + 每 IP 60 次/15 分钟 |
| 图片轮询 | 桃桃访问令牌 | 每用户 300 次 + 每 IP 1800 次/15 分钟 |
| IM 会话 | 桃桃访问令牌 | 每用户 30 次 + 每 IP 180 次/15 分钟 |
| IM 同步/撤回/已读 | 桃桃访问令牌 | 每用户 300 次 + 每 IP 1800 次/15 分钟 |
| Android 后台、公告、图片 Key(读) | `AdminAuthGuard` + `RolesGuard`,`READ_ROLES` | 每 IP 60 次/15 分钟;未携带 Bearer 或会话无效 401/4013;角色不足 403/4030 |
| 用户资料与听歌历史(读) | 同上,`PRIVILEGED_READ_ROLES` | 同上;观察者看不到个人数据 |
| 同上(写:发版、放量、改配置、公告、用户、密钥) | 同上,`WRITE_ROLES` | 同上;观察者被拒,写操作另写 `admin_audit_log` |
| 管理后台登录、2FA、改密码 | `@Public()` 或已认证 | `admin-login`/`admin-totp`/`admin-password` 三个独立桶(见 [82-admin-routes-data.md](82-admin-routes-data.md)) |
| 管理后台其它接口 | 管理员会话 `Authorization: Bearer` | 默认无独立桶;再按角色判 403/4030 |
| 开放搜歌 `/open/**` | 不要求桃桃访问令牌;用 `X-API-Key` 或 `Bearer tt_...`(`ApiKeyGuard`) | `open-api` 桶:每 key 120 次 + 每来源地址 600 次/15 分钟;失败 401/4014,超限 429/4290 |
| 音源短信登录 `/app/admin/music-sources/sms` | 管理员会话,`WRITE_ROLES` | `music-source-sms` 桶:每 IP 5 次 + 每手机号 3 次/15 分钟 |
| 传输加密握手 `/crypto/handshake` | `@Public()` | 无独立限流;未启用固定 503/5031 |

全量限流桶表见 [30-api-conventions.md](30-api-conventions.md)。限流器是进程内滑动窗口,重启清空,多实例不共享;不能把它当成跨实例的安全配额。

## 6. 数据模型速览

迁移当前创建 24 张表:

```text
users                         refresh_tokens
favorites                     playlists                 playlist_songs
song_share                    playback_sessions         user_song_stats
playback_history_state        playback_history_clear_operation
app_release                   app_channel               app_config
app_announcement              api_key                    image_generation_task
im_device_session             app_patch
admin_users                   admin_sessions            admin_audit_log
open_api_key                  music_source_account      user_avatars
```

各表的字段语义:用户与播放域见 [41-database-tables-core.md](41-database-tables-core.md),发布域见 [42-database-tables-release.md](42-database-tables-release.md),管理域见 [43-database-tables-admin.md](43-database-tables-admin.md)。迁移机制与表变更流程见 [40-database-overview.md](40-database-overview.md)。

主要外键和删除语义:

- 用户删除会级联刷新令牌、收藏、歌单、分享、播放、头像二进制和 IM 设备凭据。
- `image_generation_task.api_key_id` 使用 `ON DELETE RESTRICT`,仍有任务引用时不能删除 Key。
- 播放清空只推进 `playback_history_state.revision`,不删除累计统计;每个 marker 另存于 `playback_history_clear_operation` 保证重试幂等。
- `admin_sessions.admin_id` 随 `admin_users` 级联删除;`admin_audit_log.admin_id` 与 `admin_users.created_by` 是 `ON DELETE SET NULL`,管理员被删后审计仍保留、只是变成无归属。
- `open_api_key` 只存 `sha256(key)`,明文 key 只在创建响应里返回一次;列表接口只回传 `keyPrefix`。
- `music_source_account` 刻意**没有外键**:凭据生命周期完全由应用层管理(音源下线不牵连用户数据)。`(source, uid)` 有部分唯一索引(`WHERE uid <> ''`),token/uid 只写不读,对外只出掩码手机号。

所有 `Date.now()` 语义的字段使用 `bigint`,版本/大小/百分比使用 `integer`,历史布尔值使用 `smallint` 0/1;SQL 返回 TypeScript camelCase 时必须给别名加双引号(类型规则见 [40-database-overview.md](40-database-overview.md))。

## 7. 配置索引

全部环境变量的唯一完整出处是 **[21-configuration.md](21-configuration.md)**(含默认值、校验行为与坑),本页不再复制配置表。一条容易误判的兼容项在这里留个路标:

- `SEARCH_CONCURRENCY`:类型化配置仍保留,但当前搜索实现不读取它,不要误以为能改变请求并发。

## 8. 构建、验证和文档同步

```powershell
cd server
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720
npx tsc -p tsconfig.json --noEmit
git diff --check
```

数据层改动的最小闭环是:独立 `music_verify`/`music_test` 数据库 → `tools/reset-db.mjs` → 重启服务执行迁移 → `verify-contract.mjs` 全绿(流程见 [22-contract-verification.md](22-contract-verification.md))。更新路由或配置时同时修改:

1. 本页索引;
2. `10–13`(服务边界、模块、请求链路、启动时序,按改动内容选);
3. `30–37`(客户端可见契约,按业务域选);
4. `40–44`(表、索引、事务);
5. `21-configuration.md` 与 `.env.example`(配置);
6. `60–62`(部署、发布、CI)与 `70–74`(排障路径);
7. 改动 `admin-auth/` 或 `ldap/` 时同步 `80–83`(会话、2FA、角色、白名单、审计、前端);
8. 改动图片/IM/音源账号时同步 `50`、`51`、`53` 对应专题。

本页是索引,不替代专题中的参数细节;当本页与源码冲突时,以源码和 CodeGraph 最新索引为准,并在同一提交中修正文档。
