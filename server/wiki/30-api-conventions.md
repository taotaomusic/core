# 接口总纲:信封、业务码、鉴权与限流

[返回文档中心](README.md)

最后更新:2026-09-27

改任何接口前先读本文;各业务域的接口细节在 31–37 各篇。**不能破坏的客户端契约**的完整清单另见项目根目录 [RELEASE.md](../../RELEASE.md)。

## 1. 基础约定

- 普通 API 前缀:`/api/v1`。
- 健康检查:`/health`,不带前缀。
- 普通 JSON 请求体上限:16KB;桌面发布清单 `POST /desktop/admin/releases` 单独上限 1MB。
- 普通接口默认需要 `Authorization: Bearer <accessToken>`。
- 管理接口(`/admin/auth/**`、`/app/admin/**`、`/desktop/admin/**`)统一用登录后签发的管理员会话:`Authorization: Bearer <token>`。静态 `X-Admin-Token` 通道已整体移除。
- 客户端判断成功的唯一依据是响应体 `code === 0`。

## 2. 响应信封

普通成功:

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

普通失败:

```json
{
  "code": 4042,
  "message": "图片生成任务不存在"
}
```

顶层 `message` 必须是非空字符串。NestJS 校验异常可能产生字符串数组,全局过滤器会用中文分号压平,不能移除这层处理:

```json
{
  "code": 4005,
  "message": "model must be equal to gpt-image-2；images must be a URL address"
}
```

业务主动校验可以返回更精确业务码:

```json
{
  "code": 4007,
  "message": "图片生成提示词不能为空"
}
```

Controller 不应返回 HTTP 200 加错误业务码;失败应同时使用正确 HTTP 状态和业务码。

## 3. 不套信封的接口

| 路径 | 格式 | 不能改变的原因 |
| --- | --- | --- |
| `GET /api/v1/search` | `application/x-ndjson` | 客户端逐行读取 `type` |
| `GET /api/v1/open/search/stream` | `application/x-ndjson` | 开放侧与内部 `/search` 同格式,第三方逐行读 `type` |
| `GET /api/v1/songs/{id}/play` | 音频流 | 支持 Range 和播放器直读 |
| `GET /api/v1/songs/{id}/lyrics` | 默认 `text/plain` | 旧客户端直接展示响应体 |
| `GET /api/v1/app/apk/{versionCode}` | APK 字节 | 下载器要求 Range/ETag |
| `GET /api/v1/app/patch/{targetVersionCode}/{patchVersion}` | 补丁字节 | 补丁应用器要求 Range/ETag |
| `GET /api/v1/public/shares/{token}/preview` | MP3 音频流 | 公开试听需要 Range,不能套 JSON |
| `GET /api/v1/desktop/artifacts/{sha256}` | 桌面模块字节 | 内容寻址下载和 Range |
| `GET /api/v1/desktop/patches/{sha256}` | 桌面差分字节 | 内容寻址差分下载和 Range |

`lyrics?format=json` 仍由 Controller 自行返回带信封的 JSON。

## 4. 业务码

| HTTP | 业务码示例 | 含义 |
| --- | --- | --- |
| 400 | 4000、4001、4002、4003、4004、4005、4006、4007、4008、4009 | 输入、来源、文件校验、邮箱或歌单集合不合法 |
| 401 | 4010、4011、4012、4013、4014 | 用户、刷新令牌、管理令牌、管理员会话无效,或开放 API Key 无效、已禁用或已吊销 |
| 403 | 4030 | 管理员角色不足,或当前 IP 不在白名单中 |
| 404 | 4040、4041、4042、4043、4044、4045 | 路由、版本、文件、公告、用户、歌单或分享不存在 |
| 409 | 4090、4091、4092、4093、4094、4095、4096 | 唯一约束、发布守卫、邮箱状态或播放因果冲突 |
| 429 | 4290、4291 | 本地限流或图片上游限流(4291 也是账号退避锁定,见 [80-admin-auth-login.md](80-admin-auth-login.md)) |
| 502 | 5020、5021 | 音乐、图片、IM 或未知上游失败 |
| 503 | 5031、5032 | IM 未启用/传输加密未启用,或发布读写失败 |

几个易混语义:

- **4011 双语境复用**:普通用户登录失败,以及管理员登录 / 2FA 校验失败。两者不会混淆,因为路径不同;但排查时要先确认是哪一个入口。
- **4014 只用于 `/open/**`**:开放 API Key 缺失、无效、已禁用或已吊销时返回 401/4014,不能改成 403。用户访问令牌与管理员会话都不能当作开放 key 使用。
- **4030 是「已认证但权限不够」**:它和管理令牌缺失时的 401/4013 语义不同——**没有凭据是 401,凭据有效但角色不够才是 403**。不要为了省事把权限不足改成 401——前端收到 401 会清本地会话并跳登录页,把一次「换个超管账号再来」变成「整个后台被登出」。
- **传输加密复用部分业务码**:`503/5031`(未启用,客户端回退明文)、`400/4013`(握手被拒绝)、`400/4006`(加密头/解密失败)、`409/4091`(加密会话失效)。详见 [37-api-crypto.md](37-api-crypto.md)。
- 歌单接口用 4045 表示歌单不存在或不属于当前账号,4004 表示歌曲身份、来源或排序集合不合法;排序集合与服务端当前内容不一致时不会静默合并,客户端应重新拉取详情(见 [33-api-playlists.md](33-api-playlists.md))。

新增业务码前先搜索现有使用点,不能复用语义不同的旧码。

## 5. 鉴权矩阵

| 路由范围 | 桃桃访问令牌 | 管理令牌 | 备注 |
| --- | --- | --- | --- |
| `/auth/register`、`/login`、`/refresh`、`/logout` | 不要求 | 不要求 | 显式公开 |
| `/auth/me` | 必须 | 不使用 | 标准用户接口 |
| `/favorites/**` | 必须 | 不使用 | 标准用户接口 |
| `/search`、`/songs/**` | 必须 | 不使用 | 音乐业务接口 |
| `/draw/**` | 必须 | 不使用 | 图片任务属于当前登录用户调用会话,但任务表当前不存 user_id |
| `/app/bootstrap`、`/app/apk/**`、`/app/patch/**` | 不要求 | 不要求 | Android 热更新通道必须公开 |
| `/app/admin/**` | 不使用 | 管理员会话 | `AdminAuthGuard` + `RolesGuard`;读 `READ_ROLES`,写 `WRITE_ROLES`(观察者 403/4030),写操作另写审计。`/app/admin/users/**` 的**读**用 `PRIVILEGED_READ_ROLES`(返回 email 与听歌历史)。`/app/admin/open-api-keys` 同走管理员会话:读 `READ_ROLES`,写 `WRITE_ROLES`。`/app/admin/music-sources` 的清单读也是 `PRIVILEGED_READ_ROLES`(见 [53-feature-music-sources.md](53-feature-music-sources.md)) |
| `/open/**` | 不要求(用 `X-API-Key` 或 `Authorization: Bearer tt_...`) | 不使用 | 开放搜歌,`ApiKeyGuard`,鉴权失败 401/4014;与用户访问令牌、管理员会话三套凭据互相独立。详见 [36-api-open.md](36-api-open.md) |
| `/admin/auth/login`、`/totp-verify`、`/logout` | 不要求 | 不要求 | 显式公开;2FA 第二步额外要求 `temp_token` |
| `/admin/auth/**`(其余) | 不使用 | 管理员会话 | 由 AdminAuthGuard + RolesGuard 校验;角色不足为 403/4030 |
| `/desktop/bootstrap`、`/desktop/artifacts/**`、`/desktop/patches/**` | 不要求 | 不要求 | 桌面更新通道必须公开 |
| `/desktop/admin/**` | 不使用 | 管理员会话 | 与 Android 共用守卫与角色常量,但版本表分开 |
| `/announcements`、`/public/shares/**` | 不要求 | 不使用 | 公开公告、分享元数据和试听 |
| `/shares/songs` | 必须 | 不使用 | 创建短链 |
| `/playback/**`、`/playlists/**` | 必须 | 不使用 | 用户云端数据 |
| `/im/**` | 必须 | 不使用 | IM 会话和同步代理;未启用时返回 503/5031 |
| `/crypto/handshake` | 不要求 | 不要求 | 显式公开(握手时客户端尚无会话);未启用固定 503/5031 |

当前图片任务表没有 `user_id` 字段,因此知道合法 taskId 的任意已登录用户都能查询该任务。若产品需要任务归属隔离,必须先给任务表增加 `user_id` 外键,并在创建和查询路径同时校验,不能只在客户端隐藏 taskId。

## 6. 重要响应头

| 响应头 | 出现场景 | 用途 |
| --- | --- | --- |
| `X-Latest-Version-Code` | 所有可正常写头的响应 | 会话中途发现全量新版本;只有 `rollout_percent = 100` 且启用的最高版本进入该头 |
| `X-Accel-Buffering: no` | 搜索 NDJSON | 禁止 nginx 缓冲 |
| `Content-Range` | 音频和 APK Range | 断点续传范围 |
| `Accept-Ranges: bytes` | 支持 Range 的资源 | 告知客户端下载能力 |
| `ETag` | APK 下载 | 使用 APK sha256 标识内容 |
| `ETag` | Android 补丁、桌面模块/差分 | 使用对象 sha256 标识内容 |
| `Cache-Control: public, ... immutable` | 内容寻址桌面对象 | sha256 地址内容不可变,可长缓存 |
| `Content-Type` | 所有响应 | 区分 JSON、NDJSON、文本和二进制 |

新增拦截器或自行 `writeHead` 时,要确认不会覆盖已经由全局拦截器设置的响应头(响应阶段顺序见 [12-request-pipeline.md](12-request-pipeline.md))。

## 7. 限流桶总表

| 桶 | 限制(15 分钟) |
| --- | --- |
| `auth:*` | 每 IP 10 次 |
| `auth:admin-login` | 每 IP 30 次(管理后台登录;主防线是账号退避,见 [80-admin-auth-login.md](80-admin-auth-login.md)) |
| `auth:admin-totp` | 每 IP 10 次(2FA 第二步与开关,独立桶) |
| `auth:admin-password` | 每 IP 10 次(管理后台改密码,独立桶) |
| `email-verification` | 每 IP 5 次 |
| `app` | 每 IP 900 次 + 每设备/来源 60 次 |
| `admin` | 每 IP 60 次 |
| `image` | 每用户 10 次 + 每 IP 60 次 |
| `image-status` | 每用户 300 次 + 每 IP 1,800 次 |
| `im-session` | 每用户 30 次 + 每 IP 180 次 |
| `im-sync` | 每用户 300 次 + 每 IP 1,800 次 |
| `open-api` | 每 key 120 次 + 每来源地址 600 次 |
| `music-source-sms` | 每 IP 5 次 + 每手机号 3 次(音源短信登录) |

限流是进程内状态,重启清空且多实例不共享。客户端收到 429/4290 时应退避,不能把它当成登录失效。注意 429/4291 是**账号退避锁定**(管理登录),与 4290 的区别见 [80-admin-auth-login.md](80-admin-auth-login.md)。

## 8. 兼容性变更分类

### 通常安全

- 在 `data` 中增加客户端会忽略的可选字段。
- 增加新的独立路由。
- 提升内部日志和超时处理,不改变响应。

### 需要客户端同步

- 字段重命名或类型变化。
- 把可选字段变成必需字段。
- 修改分页、默认音质或轮询节奏。

### 禁止直接修改

- 把 401 改成 403。
- 给 `/search`、歌词文本或二进制接口套信封。
- 改变令牌格式。
- 改变 `accessToken/refreshToken/user` 的平铺结构。
- 让 `/app/bootstrap` 要求登录。

## 9. 修改接口前核对

- 旧客户端是否依赖字段名、字段类型或裸响应?
- 错误是否可能错误触发令牌刷新?
- 绝对 URL 是否为 HTTPS 且免鉴权?
- 是否需要 `@RawResponse()`?
- 是否需要独立限流桶?(开放搜歌用了独立的 `open-api` 桶,见 [36-api-open.md](36-api-open.md))
- 是否为新增行为补充契约验证?(见 [22-contract-verification.md](22-contract-verification.md))
- 是否更新本组专题文档和后端 README?
