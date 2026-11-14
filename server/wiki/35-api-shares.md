# 歌曲分享与试听契约

[返回文档中心](README.md)

最后更新:2026-11-07

分享短链把一首歌变成免登录可看的公开页;试听是**转发上游音频**,服务端不落盘、不裁剪。表设计见 [41-database-tables-core.md](41-database-tables-core.md) 的 `song_share`;管理端维护见 §5;排障见 [72-troubleshooting-music.md](72-troubleshooting-music.md)。

## 1. 创建分享

```http
POST /api/v1/shares/songs
Authorization: Bearer <accessToken>
```

- 提交 `source`(默认 `tencent`,必须是受支持的音源)以及 `remoteId`/`songId` 或 `mid`,可选 `type`;身份不完整返回 400/4001。
- 服务端先回源拉取歌曲资料生成快照,同一账号、来源和稳定歌曲身份会**复用短码**(不重复建短链),成功 HTTP 201,返回 `{token, url}`;`url` 是免登录分享页 `{对外基地址}/s/{token}`。
- 分享 token 只允许 8–24 位 `[A-Za-z0-9_-]`;格式不符或查不到短码的公开读取一律 404/4045。
- 生产必须设置 `PUBLIC_BASE_URL`,否则短链会根据代理头推导出错误协议(见 [21-configuration.md](21-configuration.md))。

## 2. 公开元数据

```http
GET /api/v1/public/shares/{token}
```

- 公开,不需要登录;返回普通 JSON 信封。
- `data` 内容:`title`、`artist`、`album`、`coverUrl`、`duration`(已格式化 `mm:ss`)、`songId`、`mid`、`type`、`source`、`vip`、`previewDurationSeconds`(「最多 60 秒」与歌曲时长取小)、`previewUrl`(试听转发地址)和 `appDownloadUrl`(最新 100% 放量 Android 版本的下载地址:登记了 GitHub 外链就返回拼上 `DOWNLOAD_PROXY_PREFIX` 的代理外链,未登记才回落本机 `/api/v1/app/apk/{version}`,与安卓整包更新 `apkUrlOf` 同一套;没有任何可下发版本时回落公开基地址)。酷我源的分享还带 `refrainStartMs` / `refrainEndMs`(整曲毫秒,快照落 NULL 时两键一起缺席、不伪造 0),语义见 [95-playback-refrain.md](95-playback-refrain.md)。
- 读取会异步累计 `access_count`,失败静默,不影响响应。
- 「最多 60 秒」**只由分享页自己守,服务端不下发任何时长限制**。

## 3. 试听转发

```http
GET /api/v1/public/shares/{token}/preview
```

- 转发上游**完整**音频(标准音质档位 4),Range 透传,支持 200/206;Range 越界等上游非 2xx 同样按下面规则归 502。
- 不暴露上游直链,不在服务端裁剪或缓存。
- 上游取址失败归 **502/5020,不能是 401** —— 401 会让客户端把上游故障当成自己的令牌失效去续期。
- 上游偶发 `110001` 风控时 `resolveLink` 会回退到 v2 低码率试听链(约 60 秒),此时分享页听到的是片段而不是整首 —— 这是全站播放路径共用的既有兜底,不是试听接口特有的问题。

## 4. 存储红线

- `song_share` 只存歌曲身份和元数据快照。
- **不存任何音频文件路径**:试听是转发上游音频,不是缓存;上游限时直链绝不持久化(旧库的 `preview_file` 列已在迁移里删除)。
- `access_count`、`enabled` 和时间字段用于公开分享统计与失效控制。
- 服务端无任何外部进程调用(历史上的 ffmpeg 裁剪已随转发方案移除),没有磁盘裁剪相关故障面。

## 5. 打点与管理端维护

### 5.1 公开打点端点(匿名可用)

```http
POST /api/v1/public/shares/{token}/open-app   # 分享页点「进入 App / 下载」时上报
POST /api/v1/public/shares/{token}/play       # 分享页开始试听时上报
```

- 均无请求体,成功 204;非法短码 404/4045(与 metadata 的失效语义一致),数据库层故障由控制器兜底吞掉(打点不能影响分享页,但路由错误必须可见)。
- `open-app` 按「UA + IP 的 SHA-256 指纹」去重:`song_share_visitor` 表 `ON CONFLICT DO NOTHING` 判首访,重复打点不再累加 `visitor_count`。指纹只存派生哈希,不落 UA/IP 原文。
- `play` 不去重,每次开始播放 +1。两者都会刷新 `last_visited_at`(自动清除的判据)。
- 分享页(`webApp`)在「下载/打开 App」按钮与首次进入播放态时经 `navigator.sendBeacon`(兜底 `fetch keepalive`)上报,失败静默。

### 5.2 管理端(前缀 `/api/v1/app/admin/share-links`)

| 方法 | 路径 | 最低角色 |
| --- | --- | --- |
| `GET` | `/` | `READ_ROLES`(keyword/limit/offset 分页,响应带 items/total/summary/expireDays) |
| `GET` | `/summary` | `READ_ROLES`(全站汇总:total/active/accessCount/visitorCount/playCount) |
| `DELETE` | `/:token` | `WRITE_ROLES`(物理删除,审计 `share.delete`,非法短码 400/4001、不存在 404/4045) |
| `POST` | `/:token/disabled` | `WRITE_ROLES`(软删除/恢复,审计 `share.set_disabled`) |
| `POST` | `/purge` | `WRITE_ROLES`(手动触发过期清除,审计 `share.purge`) |

- 统计口径:`accessCount` = 公开元数据被读取次数(既有);`visitorCount` = 进入 App 的去重访客数;`playCount` = 试听播放次数。
- 删除后分享页按既有语义 404/4045,客户端与分享页无需改动;访客去重行随外键 `ON DELETE CASCADE` 清理。
- 管理后台前端对应「分享短链」页签(`ShareLinkManager.vue`),观察者可见(运营数据),写按钮会被 403/4030 拒绝。

### 5.3 自动清除

- `SHARE_EXPIRE_DAYS`(默认 0 = 永不清除):连续 N 天没有任何公开访问(元数据或打点都会刷新 `last_visited_at`)的启用短链在启动时与每 `SHARE_EXPIRE_SWEEP_SECONDS` 秒(默认 6 小时,0 = 仅启动时一次)被删除。判据是 `last_visited_at` 而非 `created_at`——刚创建还没人点的短链不会被误删。
- 清除动作失败只打日志,不影响服务启动;单次最多删 1000 条(防御截断)。
- 两个变量都在 `env.validation.ts` 校验(0–3650 天 / 0–86400 秒),配置写错启动即失败。
