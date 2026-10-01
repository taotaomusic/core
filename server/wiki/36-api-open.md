# 开放搜歌 API

[返回文档中心](README.md)

最后更新:2026-09-30

第三方通过 API Key 调用的开放搜歌接口,前缀 `/api/v1/open/**`。模块在 `server/src/open-api/`(`OpenApiModule`,imports `MusicModule` + `UpstreamModule` + `AdminAuthModule`)。**内部 `/search` 的契约不受本文任何影响** —— 开放侧只是新增路由,不改既有 NDJSON 字段、红线和错误映射(见 [32-api-search-music.md](32-api-search-music.md))。

## 1. 三套互相独立的凭据

开放接口只认 API Key;它与用户访问令牌、管理员会话是**三套互相独立的凭据**:

| 凭据 | 形状 | 能用在哪 |
| --- | --- | --- |
| 用户访问令牌 | `payload.signature`,不以 `tt_` 开头 | `/auth/**`、业务接口;**不能**当开放 key |
| 管理员会话 | 登录签发的 Bearer | `/admin/auth/**`、`/app/admin/**` 等;**不能**当开放 key |
| 开放 API Key | `tt_<random>` | 仅 `/open/**` |

## 2. 端点

| 方法 | 路径 | 鉴权 | 响应 |
| --- | --- | --- | --- |
| GET | `/open/search?keyword&page&num&limit&quality&source` | `X-API-Key` 或 `Bearer tt_...` | 统一信封,`data = { songs: Song[], meta }` |
| GET | `/open/search/stream?...` | 同上 | 裸 NDJSON(`@RawResponse`),格式同内部 `/search` |
| GET | `/open/songs/:id/lyrics?format&mid&source` | 同上 | 默认 `text/plain`;`format=json` 走信封 |
| GET | `/open/songs/:id/link?quality&mid&type&source` | 同上 | 信封,`data = { songId, url, quality, requestedQuality, kbps, fallback }` |

## 3. 鉴权

- Header 二选一:`X-API-Key: tt_<random>`(优先),或 `Authorization: Bearer tt_<random>`。
- 缺失、无效、已禁用或已吊销 → **HTTP 401 且业务码 4014**。4010–4013 已被占用,4014 是新码;**绝不能返回 403**。
- 看到的是 429/4290 而不是 401,说明是 `open-api` 限流桶先命中(见 §6),等窗口过去再试。

```bash
curl -H "X-API-Key: tt_xxxxxxxx" \
  "https://music.example/api/v1/open/search?keyword=周杰伦&page=1&num=10"
# 或
curl -H "Authorization: Bearer tt_xxxxxxxx" \
  "https://music.example/api/v1/open/search?keyword=周杰伦&page=1&num=10"
```

## 4. 两种搜索响应

JSON 信封(`/open/search`):

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

NDJSON 每行形状(`/open/search/stream`,与内部 `/search` 同格式):

```text
{"type":"song","data":{"id":97773,"mid":"...","favorited":false,"vip":false,"playable":true}}
{"type":"end","meta":{"dropped":0,"droppedBySource":{},"quality":10}}
```

## 5. 开放侧 Song 与内部 `/search` 的差异

- `favorited` 恒为 `false`(开放侧没有用户身份,跳过收藏填充)。
- **不下发 `audioUrl`**:外部调用方改用 `/open/songs/:id/link` 换取播放直链。
- `lyricUrl` 指向 `/api/v1/open/songs/...`(开放侧歌词路径),不是内部 `/songs/...`。

开放 JSON 端点下发 `access-control-allow-origin: *` 与 `access-control-allow-headers: x-api-key, authorization, content-type`(不带 credentials);NDJSON stream 内部已有的 `*` 同样适用。

## 6. 错误码与限流

- 鉴权失败:401/4014(见 §3)。
- 限流桶 `open-api`:按 key 120 次 + 按来源地址 600 次 / 15 分钟,超限返回 429/4290。
- 401/4014 **不会**退化成 403;看到 403 说明请求没走到 `ApiKeyGuard`,先核对路径是否真是 `/open/**`。

## 7. 管理端(需管理员会话)

| 方法 | 路径 | 角色 | 说明 |
| --- | --- | --- | --- |
| GET | `/app/admin/open-api-keys` | `READ_ROLES` | 列表(只有 `keyPrefix`,无明文) |
| POST | `/app/admin/open-api-keys` | `WRITE_ROLES` | body `{ name }`(1–64 个字符,超限 400/4000),201,`data.apiKey` 明文**只返回一次** |
| PATCH | `/app/admin/open-api-keys/:id` | `WRITE_ROLES` | body `{ enabled }`(布尔);id 不存在或已是目标状态 → 404/4042 |
| DELETE | `/app/admin/open-api-keys/:id` | `WRITE_ROLES` | 204 吊销(不可逆);已吊销再删仍 404/4042,不写第二条审计 |

- 写操作审计 action:`open_api_key.create` / `open_api_key.set_enabled` / `open_api_key.revoke`;审计只记 `keyPrefix`,不记明文。
- 表 `open_api_key` 只存 `sha256(key)`,建表在 `migrations.ts`(见 [43-database-tables-admin.md](43-database-tables-admin.md))。
- 手工改过库里明文的 key 永远对不上(库里按 `sha256(key)` 匹配),只能重新创建。
