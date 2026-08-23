# 接口与客户端契约

[返回文档中心](README.md)

## 1. 基础约定

- 普通 API 前缀：`/api/v1`。
- 健康检查：`/health`，不带前缀。
- JSON 请求体上限：16KB。
- 普通接口默认需要 `Authorization: Bearer <accessToken>`。
- 管理接口使用 `X-Admin-Token`。
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
| `GET /api/v1/songs/{id}/play` | 音频流 | 支持 Range 和播放器直读 |
| `GET /api/v1/songs/{id}/lyrics` | 默认 `text/plain` | 旧客户端直接展示响应体 |
| `GET /api/v1/app/apk/{versionCode}` | APK 字节 | 下载器要求 Range/ETag |

`lyrics?format=json` 仍由 Controller 自行返回带信封的 JSON。

## 4. 业务码

| HTTP | 业务码示例 | 含义 |
| --- | --- | --- |
| 400 | 4001、4003、4005、4007 | 输入不合法 |
| 401 | 4010、4011、4012、4013 | 用户、刷新令牌或管理令牌无效 |
| 404 | 4040、4041、4042 | 路由、版本或图片任务不存在 |
| 409 | 4090、4091 | 唯一约束或发布守卫冲突 |
| 429 | 4290、4291 | 本地或图片上游限流 |
| 502 | 5020、5021 | 音乐或图片上游失败 |
| 503 | 5031、5032 | 图片服务未配置或额度不足 |

新增业务码前先搜索现有使用点，不能复用语义不同的旧码。

## 5. 认证接口

### 注册

```http
POST /api/v1/auth/register
Content-Type: application/json
```

```json
{
  "username": "用户名",
  "password": "至少6位密码"
}
```

成功 HTTP 201。`accessToken`、`refreshToken`、`expiresIn` 和 `user` 必须平铺在 `data` 下。

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

## 6. 搜索契约

```http
GET /api/v1/search?keyword=周杰伦&page=1&num=60&quality=10
Authorization: Bearer <accessToken>
```

每首歌一行：

```json
{"type":"song","data":{"id":97773,"mid":"...","favorited":false,"vip":false}}
```

末行：

```json
{"type":"end","meta":{"dropped":0,"quality":10}}
```

红线：

- `data.id` 是 JSON number。
- 收藏接口的 `songId` 是 JSON string。
- `coverUrl` 是绝对 HTTPS。
- `lyricUrl` 是带 `/api/v1/` 的相对路径。
- `favorited`、`vip` 是 boolean。
- 响应头包含 `X-Accel-Buffering: no`。
- 搜索只返回元信息，不逐首解析播放地址。

## 7. 播放和歌词

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

## 8. 图片生成接口

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

## 9. 热更新契约

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

## 10. 修改接口前核对

- 旧客户端是否依赖字段名、字段类型或裸响应？
- 错误是否可能错误触发令牌刷新？
- 绝对 URL 是否为 HTTPS 且免鉴权？
- 是否需要 `@RawResponse()`？
- 是否需要独立限流桶？
- 是否为新增行为补充契约验证？
- 是否更新本专题和后端 README？
