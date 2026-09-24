# 接口与客户端契约

[返回文档中心](README.md)

## 1. 基础约定

- 普通 API 前缀：`/api/v1`。
- 健康检查：`/health`，不带前缀。
- 普通 JSON 请求体上限：16KB；桌面发布清单 `POST /desktop/admin/releases` 单独上限 1MB。
- 普通接口默认需要 `Authorization: Bearer <accessToken>`。
- 管理接口（`/admin/auth/**`、`/app/admin/**`、`/desktop/admin/**`）统一用登录后签发的
  管理员会话：`Authorization: Bearer <token>`。静态 `X-Admin-Token` 通道已整体移除。
- 客户端判断成功的唯一依据是响应体 `code === 0`。

## 2. 响应信封

普通成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

普通失败：

```json
{
  "code": 4042,
  "message": "图片生成任务不存在"
}
```

顶层 `message` 必须是非空字符串。NestJS 校验异常可能产生字符串数组，全局过滤器会用中文分号压平，不能移除这层处理。

## 3. 不套信封的接口

| 路径 | 格式 | 不能改变的原因 |
| --- | --- | --- |
| `GET /api/v1/search` | `application/x-ndjson` | 客户端逐行读取 `type` |
| `GET /api/v1/open/search/stream` | `application/x-ndjson` | 开放侧与内部 `/search` 同格式，第三方逐行读 `type` |
| `GET /api/v1/songs/{id}/play` | 音频流 | 支持 Range 和播放器直读 |
| `GET /api/v1/songs/{id}/lyrics` | 默认 `text/plain` | 旧客户端直接展示响应体 |
| `GET /api/v1/app/apk/{versionCode}` | APK 字节 | 下载器要求 Range/ETag |
| `GET /api/v1/app/patch/{targetVersionCode}/{patchVersion}` | 补丁字节 | 补丁应用器要求 Range/ETag |
| `GET /api/v1/public/shares/{token}/preview` | MP3 音频流 | 公开试听需要 Range，不能套 JSON |
| `GET /api/v1/desktop/artifacts/{sha256}` | 桌面模块字节 | 内容寻址下载和 Range |
| `GET /api/v1/desktop/patches/{sha256}` | 桌面差分字节 | 内容寻址差分下载和 Range |

`lyrics?format=json` 仍由 Controller 自行返回带信封的 JSON。

## 4. 业务码

| HTTP | 业务码示例 | 含义 |
| --- | --- | --- |
| 400 | 4000、4001、4002、4003、4004、4005、4006、4007、4008、4009 | 输入、来源、文件校验、邮箱或歌单集合不合法 |
| 401 | 4010、4011、4012、4013、4014 | 用户、刷新令牌、管理令牌、管理员会话无效，或开放 API Key 无效、已禁用或已吊销 |
| 403 | 4030 | 管理员角色不足，或当前 IP 不在白名单中 |
| 404 | 4040、4041、4042、4043、4044、4045 | 路由、版本、文件、公告、用户、歌单或分享不存在 |
| 409 | 4090、4091、4092、4093、4094、4095、4096 | 唯一约束、发布守卫、邮箱状态或播放因果冲突 |
| 429 | 4290、4291 | 本地或图片上游限流 |
| 502 | 5020、5021 | 音乐、图片、IM 或未知上游失败 |
| 503 | 5031、5032 | IM 未启用或发布读写失败 |

4011 在两个语境下复用：普通用户登录失败，以及管理员登录 / 2FA 校验失败。两者不会混淆，
因为路径不同；但排查时要先确认是哪一个入口。

4014 只用于 `/open/**` 开放接口：开放 API Key 缺失、无效、已禁用或已吊销时返回 401/4014，
不能改成 403。用户访问令牌与管理员会话都不能当作开放 key 使用。

`4030` 是本次新增的“已认证但权限不够”码。注意它和管理令牌缺失时的 401/4013 语义不同：
**没有凭据是 401，凭据有效但角色不够才是 403**。不要为了省事把权限不足改成 401 —— 前端收到
401 会清本地会话并跳登录页，把一次“换个超管账号再来”变成“整个后台被登出”。

新增业务码前先搜索现有使用点，不能复用语义不同的旧码。

歌单接口使用 4045 表示歌单不存在或不属于当前账号，4004 表示歌曲身份、来源或排序集合
不合法。排序集合与服务端当前内容不一致时不会静默合并，客户端应重新拉取详情。

## 5. 云端歌单

歌单接口均需要 `Authorization: Bearer <accessToken>`，成功响应遵循统一信封。歌曲身份由
`source + songId` 组成，`songId` 传字符串以兼容数字 ID 与上游 `mid`。服务端同时保存展示
快照，歌单详情返回按 `position` 升序的歌曲数组。

目前 `source` 仅允许 `tencent` 和 `netease`。当腾讯歌曲的数字 `id` 为 `0` 或缺失时，
必须传非空 `mid`；服务端以 `mid` 代替无效数字 ID 建立稳定键。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/v1/playlists` | 当前账号的歌单摘要 |
| `POST` | `/api/v1/playlists` | 创建歌单，JSON `name` 必填 |
| `GET` | `/api/v1/playlists/{id}` | 歌单详情与歌曲 |
| `PATCH`/`PUT` | `/api/v1/playlists/{id}` | 更新名称、简介或封面 |
| `DELETE` | `/api/v1/playlists/{id}` | 删除歌单，返回 204 |
| `POST` | `/api/v1/playlists/{id}/songs` | 添加/更新一首歌曲快照 |
| `DELETE` | `/api/v1/playlists/{id}/songs/{source}/{songId}` | 移除歌曲 |
| `PATCH`/`PUT` | `/api/v1/playlists/{id}/songs/order` | 用完整键列表调整顺序（`songs`、`songIds` 或 `order`） |
| `PUT` | `/api/v1/playlists/{id}/songs` | 完整替换歌曲集合 |

歌单每次发生资料、歌曲或顺序变化都会递增 `revision`。排序要求提交的集合与服务端当前
集合完全一致，否则返回 `400/4004`，客户端应重新读取详情。单歌单上限为 5,000 首。

## 6. 认证接口

### 注册

```http
POST /api/v1/auth/register
Content-Type: application/json
```

```json
{
  "username": "用户名",
  "password": "至少6位密码",
  "email": "name@example.com",
  "verificationCode": "123456"
}
```

成功 HTTP 201。新注册必须额外传 `email` 与 `verificationCode`；先调用 `POST /api/v1/auth/email-verification` 发送六位验证码。验证码仅存服务进程内存、10 分钟过期，校验成功即删除。`accessToken`、`refreshToken`、`expiresIn` 和 `user` 必须平铺在 `data` 下。

### 绑定与换绑邮箱

以下接口均需要访问令牌。老账号先调用 `POST /api/v1/auth/email/bind-verification`，再把同一个 `email` 与 `verificationCode` 提交到 `POST /api/v1/auth/email/bind`；已绑定账号则使用 `change-verification` 与 `change` 两个同形接口。两种验证码用途互不通用，防止把注册验证码用于篡改已登录账号的邮箱。

### 用户资料

`GET /api/v1/auth/profile` 返回当前账号的 `username`、`nickname`、`avatarUrl`、`email` 与 `created_at`。`PATCH /api/v1/auth/profile` 接收一个或两个字段：`nickname` 为 1–24 个非控制字符，`avatarUrl` 必须为 HTTPS 地址；传 `{"avatarUrl":null}` 可以清除头像。邮箱只在本人资料接口中返回，不对外暴露。

### 登录

```http
POST /api/v1/auth/login
```

用户名或密码错误返回 401/4011。

### 刷新

```http
POST /api/v1/auth/refresh
```

```json
{ "refreshToken": "..." }
```

刷新令牌确实无效时返回 401/4012；数据库或内部故障必须保持 5xx。客户端收到刷新接口 4xx 会清除本地会话。

## 7. 搜索契约

### 搜索联想

```http
GET /api/v1/search/suggestions?keyword=雨&limit=10&source=kuwo
Authorization: Bearer <accessToken>
```

返回普通 JSON 信封，`data` 是按官方顺序排列的联想词数组：

```json
{
  "code": 0,
  "message": "success",
  "data": ["雨爱", "雨一直下", "雨蝶"]
}
```

- `keyword` 必填，去除首尾空白后不能为空。
- `limit` 默认 10，最大 20；限制在本服务本地执行。
- `source` 当前仅支持 `kuwo`，省略时默认使用 `kuwo`。
- 官方波点 5.9.1 调用 `search/tip/v2/list`，只传业务参数
  `keyword` 与 `tipsFrom=bd`，读取 `data.resultList[].relword`。该接口没有分页参数，
  不要自行添加 `pn` / `rn`，以免联想排序或召回结果偏离官方客户端。

### 热搜

```http
GET /api/v1/search/hot?limit=20&source=kuwo
Authorization: Bearer <accessToken>
```

返回普通 JSON 信封，`data` 为官方排序的热搜对象数组：

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "keyword": "起风了",
      "type": 0,
      "icon": "",
      "sort": 1,
      "searchType": 4,
      "jumpUrl": "123456"
    }
  ]
}
```

- `limit` 默认 10，最大 20；`source` 省略时默认 `kuwo`。
- `keyword` 可直接作为普通搜索词使用。
- `searchType` / `jumpUrl` 保留官方定向跳转信息；不需要定向跳转的客户端可忽略。
- 官方波点 5.9.1 使用无业务参数的 `GET search/topic/word/list`，热搜列表位于
  `data.hotWord`。同一响应还含专题、运营入口等内容，它们不是热搜，不混入本接口。

```http
GET /api/v1/search?keyword=周杰伦&page=1&num=60&quality=10
Authorization: Bearer <accessToken>
```

每首歌一行：

```json
{"type":"song","data":{"id":97773,"mid":"...","favorited":false,"vip":false,"playable":true}}
```

末行：

```json
{"type":"end","meta":{"dropped":0,"droppedBySource":{},"quality":10}}
```

`playable` 是**这首歌能不能拿到播放地址**。`false` 时客户端必须置灰并标注，
不要发起播放（发起也只会拿到一句「没有可用播放链接」）。

- 与 `vip` **不是一回事**：`vip` 是「上游标它付费」，`playable` 是「实测取不到地址」。
- **不可播的歌照常下发**，不丢。依据是波点 App 自己也不滤：同一关键词下它综合页的
  `musicpage` 与我们的原始列表逐条一致，它把放不了的歌也列出来。
  2026-09-19 之前我们按 `listen_fragment` 滤掉，导致搜「周杰伦」上游 30 条全被滤、
  客户端拿到 0 条 —— 用户看到的是「搜不到歌」，第一反应是账号或音源坏了。
  那 30 首在酷我上都没有版权（周杰伦是 TME 独家），上游逐首回 `code 20012 歌曲已下线`。
- 只有酷我会判断；QQ 音乐与网易云恒为 `true`。

`dropped` 是**上游返回了、但没有下发**的条数。**目前恒为 0** —— 酷我也不再丢歌了。
字段与 `droppedBySource` 都**不能删**：装机的旧客户端会读 `dropped` 做算术，
拿到 `undefined` 会算出 `NaN`。保留它们只为契约兼容，不再承载信息。

红线：

- `data.id` 是 JSON number。
- 收藏接口的 `songId` 是 JSON string。
- `coverUrl` 是绝对 HTTPS。
- `lyricUrl` 是带 `/api/v1/` 的相对路径。
- `favorited`、`vip`、`playable` 是 boolean。
- 响应头包含 `X-Accel-Buffering: no`。
- 搜索只返回元信息，不逐首解析播放地址。

## 8. 播放和歌词

### 播放地址

```http
GET /api/v1/songs/97773/link?quality=10&mid=&type=
```

返回上游 HTTPS 直链和实际音质。请求档位不存在时服务端降级，并设置 `fallback: true`。

### 播放代理

`/play` 是旧客户端兼容和解析失败兜底。Range 请求应返回 206 与 `Content-Range`；上游非 2xx 统一映射为 502，不能透传上游 401。

### 歌词

- 默认：裸 LRC 文本。
- `format=json`：返回 `{lrc, yrc, trans}`。
- 无歌词：502，不能返回 401。

## 9. 图片生成接口

### 创建任务

```http
POST /api/v1/draw/completions
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{
  "model": "gpt-image-2",
  "prompt": "一只猫在草地上",
  "images": ["https://example.com/reference.png"],
  "aspectRatio": "1:1",
  "imageSize": "1K",
  "quality": "high"
}
```

约束：

- `model` 只能是 `gpt-image-2`。
- `prompt` 去除首尾空白后不能为空。
- `images` 最多 8 张，只接受 HTTP/HTTPS URL，不接受 base64。
- 比例、尺寸和质量必须来自 DTO 枚举。

成功：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": "task_xxxxx",
    "status": "IN_PROGRESS"
  }
}
```

### 查询任务

```http
GET /api/v1/draw/result/task_xxxxx
Authorization: Bearer <accessToken>
```

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
    "result": { "imageUrl": "https://example.com/generated.png" },
    "error": null
  }
}
```

客户端约每 3 秒轮询。`IN_PROGRESS` 继续，`COMPLETED` 或 `FAILED` 停止。

## 10. 热更新契约

`/app/bootstrap` 免鉴权且永不返回 401。它同时返回：

- 可用更新。
- 最低支持版本。
- 远程配置。
- 配置版本号。

APK 下载必须支持：

- 全量 200。
- Range 206。
- 越界 416。
- `ETag` 为 sha256。
- `apkSize` 与文件字节数完全一致。

发布管理细节见 [06-release-deployment.md](06-release-deployment.md)。

## 11. 修改接口前核对

- 旧客户端是否依赖字段名、字段类型或裸响应？
- 错误是否可能错误触发令牌刷新？
- 绝对 URL 是否为 HTTPS 且免鉴权？
- 是否需要 `@RawResponse()`？
- 是否需要独立限流桶？（开放搜歌用了独立的 `open-api` 桶，见 §17）
- 是否为新增行为补充契约验证？
- 是否更新本专题和后端 README？

## 12. 鉴权矩阵

| 路由范围 | 桃桃访问令牌 | 管理令牌 | 备注 |
| --- | --- | --- | --- |
| `/auth/register`、`/login`、`/refresh`、`/logout` | 不要求 | 不要求 | 显式公开 |
| `/auth/me` | 必须 | 不使用 | 标准用户接口 |
| `/favorites/**` | 必须 | 不使用 | 标准用户接口 |
| `/search`、`/songs/**` | 必须 | 不使用 | 音乐业务接口 |
| `/draw/**` | 必须 | 不使用 | 图片任务属于当前登录用户调用会话，但任务表当前不存 user_id |
| `/app/bootstrap`、`/app/apk/**`、`/app/patch/**` | 不要求 | 不要求 | Android 热更新通道必须公开 |
| `/app/admin/**` | 不使用 | 管理员会话 | `AdminAuthGuard` + `RolesGuard`；读 `READ_ROLES`，写 `WRITE_ROLES`（观察者 403/4030），写操作另写审计。`/app/admin/users/**` 的**读**用 `PRIVILEGED_READ_ROLES`（返回 email 与听歌历史）。`/app/admin/open-api-keys` 同走管理员会话：读 `READ_ROLES`，写 `WRITE_ROLES` |
| `/open/**` | 不要求（用 `X-API-Key` 或 `Authorization: Bearer tt_...`） | 不使用 | 开放搜歌，`ApiKeyGuard`，鉴权失败 401/4014；与用户访问令牌、管理员会话三套凭据互相独立。详见 §17 |
| `/admin/auth/login`、`/totp-verify`、`/logout` | 不要求 | 不要求 | 显式公开；2FA 第二步额外要求 `temp_token` |
| `/admin/auth/**`（其余） | 不使用 | 管理员会话 | 由 AdminAuthGuard + RolesGuard 校验；角色不足为 403/4030 |
| `/desktop/bootstrap`、`/desktop/artifacts/**`、`/desktop/patches/**` | 不要求 | 不要求 | 桌面更新通道必须公开 |
| `/desktop/admin/**` | 不使用 | 管理员会话 | 与 Android 共用守卫与角色常量，但版本表分开 |
| `/announcements`、`/public/shares/**` | 不要求 | 不使用 | 公开公告、分享元数据和试听 |
| `/shares/songs` | 必须 | 不使用 | 创建短链 |
| `/playback/**`、`/favorites/**`、`/playlists/**` | 必须 | 不使用 | 用户云端数据 |
| `/im/**` | 必须 | 不使用 | IM 会话和同步代理；未启用时返回 503/5031 |

当前图片任务表没有 `user_id` 字段，因此知道合法 taskId 的任意已登录用户都能查询该任务。若产品需要任务归属隔离，必须先给任务表增加 `user_id` 外键，并在创建和查询路径同时校验，不能只在客户端隐藏 taskId。

## 13. 重要响应头

| 响应头 | 出现场景 | 用途 |
| --- | --- | --- |
| `X-Latest-Version-Code` | 所有可正常写头的响应 | 会话中途发现全量新版本 |
| `X-Accel-Buffering: no` | 搜索 NDJSON | 禁止 nginx 缓冲 |
| `Content-Range` | 音频和 APK Range | 断点续传范围 |
| `Accept-Ranges: bytes` | 支持 Range 的资源 | 告知客户端下载能力 |
| `ETag` | APK 下载 | 使用 APK sha256 标识内容 |
| `ETag` | Android 补丁、桌面模块/差分 | 使用对象 sha256 标识内容 |
| `Cache-Control: public, ... immutable` | 内容寻址桌面对象 | sha256 地址内容不可变，可长缓存 |
| `Content-Type` | 所有响应 | 区分 JSON、NDJSON、文本和二进制 |

新增拦截器或自行 `writeHead` 时，要确认不会覆盖已经由全局拦截器设置的响应头。

## 14. 参数错误示例

DTO 校验失败由全局过滤器压平：

```json
{
  "code": 4005,
  "message": "model must be equal to gpt-image-2；images must be a URL address"
}
```

业务主动校验可以返回更精确业务码：

```json
{
  "code": 4007,
  "message": "图片生成提示词不能为空"
}
```

Controller 不应返回 HTTP 200 加错误业务码；失败应同时使用正确 HTTP 状态和业务码。

## 15. 兼容性变更分类

### 通常安全

- 在 `data` 中增加客户端会忽略的可选字段。
- 增加新的独立路由。
- 提升内部日志和超时处理，不改变响应。

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

## 16. 现行扩展路由

下面是旧专题遗漏但当前 Controller 已实现的契约。详细路由数量和源码位置见
[00-code-index.md](00-code-index.md)；新增路由后先更新索引，再修改本节。

### 公告

- `GET /api/v1/announcements` 公开返回最多 20 条启用公告，置顶公告优先。
- 后台 `GET/POST /api/v1/app/admin/announcements`、`POST /:id`、`POST /:id/enabled`、
  `POST /:id/pinned`、`DELETE /:id` 只接受管理员会话。标题最多 80 个字符，正文最多
  5,000 个字符；`enabled` 和 `pinned` 必须是 JSON boolean。
- 同时只能有一条置顶公告，服务端用顾问锁串行化更新；不存在的公告返回 404/4043。

### 收藏

`GET /favorites` 返回当前有效收藏数组（不是分页对象）。`POST` 和 `DELETE`
`/favorites/{source}/{songId}` 不需要请求体；来源只允许 `tencent`/`netease`，歌曲 ID 按字符串
校验。删除是软删除并返回 `{removed}`，重新收藏保留 `createdAt`，只更新当前轮次的
`favoritedAt`。搜索通过一次批量查询填充 `favorited`，不能在循环里逐首读取收藏。

### 最近播放与统计

`POST /playback/sessions` 的最小身份字段是 `sessionId`、`deviceId`、`source`、`songId`、
`startedAt`、`lastPlayedAt`、`listenedMs`；可选 `historyRevision`、`completed`、
`durationSeconds`。同一会话重复上报只计算累计增长量。3 秒后才进入最近播放，单次有效播放阈值为
`min(30 秒, durationSeconds * 50%)`。

- `GET /playback/recent?limit=` 默认 500，最大 500，返回数组。不传 `limit` 时会返回最多 500 首，
  不要按「不传只返回 50 条」做内存或性能假设。
- `GET /playback/recent/state` 返回 `clearedBefore`、`clearedAt`、`revision`、`marker`。
- `GET /playback/stats` 返回累计歌曲数、播放次数和听歌毫秒数。
- `DELETE /playback/recent?marker=<uuid>` 推进清空代际；相同 marker 重试始终返回第一次结果，
  不会重复清空。清空只影响最近列表，不删除累计统计。

`historyRevision` 领先服务端状态返回 409/4096；会话身份字段冲突返回 409/4095。客户端不能用
设备墙钟代替 revision 判断离线清空因果。

### 歌曲分享与试听

- `POST /shares/songs` 需要访问令牌，提交 `source` 以及 `remoteId`/`songId` 或 `mid`，可选
  `type`；同一账号、来源和稳定歌曲身份会复用短码，成功 HTTP 201。
- `GET /public/shares/{token}` 返回元数据、最多 60 秒试听地址和最新 100% Android APK 地址。
  这是普通 JSON 信封，分享 token 只允许 8–24 位 `[A-Za-z0-9_-]`。
- `GET /public/shares/{token}/preview` 转发上游**完整**音频（标准音质），支持 200/206/416，
  不暴露上游直链，也不在服务端裁剪或缓存。上游取址失败归 502/5020，**不能是 401**。
  「最多 60 秒」只由分享页自己守，服务端不下发任何时长限制。

试听不落盘：`song_share` 只存歌曲身份和元数据快照，上游限时链接绝不持久化；生产必须设置 `PUBLIC_BASE_URL`，否则
短链会根据代理头推导出错误协议。

### IM 会话与同步

所有 `/im/**` 都需要桃桃访问令牌，并受独立限流。`IM_ENABLED=false` 时返回 503/5031；聊天正文
和游标由悟空 IM 保存，PostgreSQL 只保存设备 Token 的哈希映射。

| 路径 | 关键输入 | 行为 |
| --- | --- | --- |
| `POST /im/session` | `deviceId`：16–128 位字母/数字/`.`/`_`/`-` | 签发 UID、Token、过期时间和 Gateway 地址；同用户同 `device_flag` 新会话覆盖旧会话 |
| `DELETE /im/session` | 无 | 撤销当前 Android 设备会话 |
| `POST /im/sync/conversations` | `lastMessageSeqs` 最多 20,000 项，`messageCount` 1–20 | 代理悟空会话同步 |
| `POST /im/sync/channel-messages` | UUID `channelId`、序列范围、`limit` 1–50 | 拉取频道消息 |
| `POST /im/messages/revoke` | UUID `channelId`、`messageId`、`clientMsgNo` | 发送悟空内部撤回命令（type 99） |
| `GET /im/contacts?uids=` | 最多 50 个合法 UUID | 返回除自己的用户资料 |
| `POST /im/conversations/read` | UUID `channelId` | 清除会话未读数 |

悟空 HTTP API 超时 5 秒且只接受 2xx；任何内部 API Token、消息正文和完整请求体都不能写日志。

### Windows 桌面更新

`GET /desktop/bootstrap` 至少需要 `versionCode`（正整数），可选 `channel`、`architecture`（默认
`windows-x64`）和 `deviceId`。响应 `update` 描述完整模块和按 `fromSha256` 匹配的差分；只有
100% 放量版本可作为 forced rescue。模块和差分下载是裸字节，使用 sha256 内容寻址、Range、ETag
和不可变缓存。

后台桌面发布路径是 `/desktop/admin/*`，**不是** `/app/admin/*`：先 `POST /desktop/admin/artifacts`
上传原始模块，再 `POST /desktop/admin/releases` 提交清单，最后用 `rollout` 分阶段放量，确认已有
清单和 100% 全量包后才能调用 `min-version`。清单路径不能穿越，单模块最大 500 MiB，最多 512 项。
完整操作手册见 [10-desktop-release.md](10-desktop-release.md)。

### 管理用户、图片 Key 与头像

- `/app/admin/users`、`/:id/playback`、`/:id/disabled`、`DELETE /:id` 只使用管理令牌；禁用用户会
  撤销刷新令牌，已有访问令牌在下一次用户查询时失效；删除通过外键级联清理业务数据。
- `/app/admin/image-keys` 的 Key 值只写入数据库，列表接口不得回传明文；删除仍被任务引用的 Key
  返回 404/4042（数据库 `ON DELETE RESTRICT`）。
- `POST /auth/avatar` 使用 multipart 字段 `file`，仅接受图片 MIME，最大 5 MiB；服务端把文件转发
  到 Lsky，客户端只能拿到 HTTPS 图片地址。`PATCH /auth/profile` 的 `avatarUrl` 也只接受 HTTPS。

### 管理后台认证

`/api/v1/admin/auth/**` 是管理后台自己的登录体系，和 `/app/admin/**` 的静态令牌互不影响。

```http
POST /api/v1/admin/auth/login
Content-Type: application/json

{ "username": "admin", "password": "..." }
```

两种成功响应：

```json
{ "code": 0, "message": "success",
  "data": { "token": "...", "admin": { "id": 1, "username": "admin", "display_name": "超级管理员", "role": "super_admin" } } }
```

```json
{ "code": 0, "message": "success",
  "data": { "requires_totp": true, "temp_token": "...", "admin_id": 1 } }
```

拿到 `requires_totp` 时**没有**会话，必须再调第二步：

```http
POST /api/v1/admin/auth/totp-verify
Content-Type: application/json

{ "temp_token": "...", "token": "123456" }
```

红线：

- `temp_token` 必填。缺它或伪造它一律 401/4011，**不能**退化成“只校验 `admin_id` + 动态码”。
- 票据 5 分钟过期、一次性使用，同一张票不能反复试码。
- 登录失败统一 401/4011，不区分“用户名不存在”和“密码错”。
- 角色不足是 403/4030，不是 401。
- 会话令牌只存 SHA-256 哈希，有效期 24 小时；响应里出现的明文令牌只此一次。

管理接口只认 `Authorization: Bearer`。完整链路、登录失败退避、强制首登改密、LDAP 回落规则和
启动期硬约束见 [11-admin-auth.md](11-admin-auth.md)。

### 当前限流桶

| 桶 | 限制（15 分钟） |
| --- | --- |
| `auth:*` | 每 IP 10 次 |
| `auth:admin-login` | 每 IP 10 次（管理后台登录） |
| `auth:admin-totp` | 每 IP 10 次（2FA 第二步与开关，独立桶） |
| `auth:admin-password` | 每 IP 10 次（管理后台改密码，独立桶） |
| `email-verification` | 每 IP 5 次 |
| `app` | 每 IP 900 次 + 每设备/来源 60 次 |
| `admin` | 每 IP 60 次 |
| `image` | 每用户 10 次 + 每 IP 60 次 |
| `image-status` | 每用户 300 次 + 每 IP 1,800 次 |
| `im-session` | 每用户 30 次 + 每 IP 180 次 |
| `im-sync` | 每用户 300 次 + 每 IP 1,800 次 |
| `open-api` | 每 key 120 次 + 每来源地址 600 次 |

限流是进程内状态，重启清空且多实例不共享。客户端收到 429/4290 时应退避，不能把它当成登录失效。

## 17. 开放搜歌 API

> 本节是补充小节：§1–§16 的既有编号保持不变，开放搜歌 API 追加为 §17。

第三方通过 API Key 调用的开放搜歌接口，前缀 `/api/v1`。它与用户访问令牌、管理员会话是
**三套互相独立的凭据**：开放接口只认 API Key，用户 access token 不能当开放 key 使用。

### 端点

| 方法 | 路径 | 鉴权 | 响应 |
| --- | --- | --- | --- |
| GET | `/open/search?keyword&page&num&limit&quality&source` | `X-API-Key` 或 `Bearer tt_...` | 统一信封，`data = { songs: Song[], meta }` |
| GET | `/open/search/stream?...` | 同上 | 裸 NDJSON（`@RawResponse`），格式同内部 `/search` |
| GET | `/open/songs/:id/lyrics?format&mid&source` | 同上 | 默认 `text/plain`；`format=json` 走信封 |
| GET | `/open/songs/:id/link?quality&mid&type&source` | 同上 | 信封，`data = { songId, url, quality, requestedQuality, kbps, fallback }` |

### 鉴权

- Header 二选一：`X-API-Key: tt_<random>`（优先），或 `Authorization: Bearer tt_<random>`。
- 缺失、无效、已禁用或已吊销 → **HTTP 401 且业务码 4014**。4010–4013 已被占用，4014 是新码；
  **绝不能返回 403**。
- 用户访问令牌是 `payload.signature` 形状、不以 `tt_` 开头，不能当开放 key。

```bash
curl -H "X-API-Key: tt_xxxxxxxx" \
  "https://music.example/api/v1/open/search?keyword=周杰伦&page=1&num=10"
# 或
curl -H "Authorization: Bearer tt_xxxxxxxx" \
  "https://music.example/api/v1/open/search?keyword=周杰伦&page=1&num=10"
```

### 两种搜索响应

JSON 信封（`/open/search`）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "songs": [
      { "id": 97773, "mid": "...", "favorited": false, "vip": false, "playable": true }
    ],
    "meta": { "dropped": 0, "quality": 10 }
  }
}
```

NDJSON 每行形状（`/open/search/stream`，与内部 `/search` 同格式）：

```text
{"type":"song","data":{"id":97773,"mid":"...","favorited":false,"vip":false,"playable":true}}
{"type":"end","meta":{"dropped":0,"droppedBySource":{},"quality":10}}
```

### 开放侧 Song 与内部 `/search` 的差异

- `favorited` 恒为 `false`（开放侧没有用户身份，跳过收藏填充）。
- **不下发 `audioUrl`**：外部调用方改用 `/open/songs/:id/link` 换取播放直链。
- `lyricUrl` 指向 `/api/v1/open/songs/...`（开放侧歌词路径），不是内部 `/songs/...`。

开放 JSON 端点下发 `access-control-allow-origin: *`（不带 credentials）；NDJSON stream 内部
已有的 `*` 同样适用。**内部 `/search` 的契约不受本节任何影响** —— 开放侧只是新增路由，
不改既有 NDJSON 字段、红线和错误映射。

### 错误码与限流

- 鉴权失败：401/4014（见上）。
- 限流桶 `open-api`：按 key 120 次 + 按来源地址 600 次 / 15 分钟，超限返回 429/4290。

### 管理端（需管理员会话）

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/app/admin/open-api-keys` | `READ_ROLES` | 列表（只有 `keyPrefix`，无明文） |
| POST | `/app/admin/open-api-keys` | `WRITE_ROLES` | body `{ name }`，201，`data.apiKey` 明文只返回一次 |
| PATCH | `/app/admin/open-api-keys/:id` | `WRITE_ROLES` | body `{ enabled }` |
| DELETE | `/app/admin/open-api-keys/:id` | `WRITE_ROLES` | 204 吊销 |

写操作审计 action：`open_api_key.create` / `open_api_key.set_enabled` / `open_api_key.revoke`；
审计只记 `keyPrefix`，不记明文。模块在 `server/src/open-api/`（`OpenApiModule`，imports
`MusicModule` + `UpstreamModule` + `AdminAuthModule`），表 `open_api_key` 只存 `sha256(key)`，建表在
`migrations.ts`。
