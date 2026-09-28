# 歌曲分享与试听契约

[返回文档中心](README.md)

最后更新:2026-09-27

分享短链把一首歌变成免登录可看的公开页;试听是**转发上游音频**,服务端不落盘、不裁剪。表设计见 [41-database-tables-core.md](41-database-tables-core.md) 的 `song_share`;排障见 [72-troubleshooting-music.md](72-troubleshooting-music.md)。

## 1. 创建分享

```http
POST /api/v1/shares/songs
Authorization: Bearer <accessToken>
```

- 提交 `source` 以及 `remoteId`/`songId` 或 `mid`,可选 `type`。
- 同一账号、来源和稳定歌曲身份会**复用短码**(不重复建短链),成功 HTTP 201。
- 分享 token 只允许 8–24 位 `[A-Za-z0-9_-]`。
- 生产必须设置 `PUBLIC_BASE_URL`,否则短链会根据代理头推导出错误协议(见 [21-configuration.md](21-configuration.md))。

## 2. 公开元数据

```http
GET /api/v1/public/shares/{token}
```

- 公开,不需要登录;返回普通 JSON 信封。
- 内容:元数据、最多 60 秒试听地址和最新 100% Android APK 地址。
- 「最多 60 秒」**只由分享页自己守,服务端不下发任何时长限制**。

## 3. 试听转发

```http
GET /api/v1/public/shares/{token}/preview
```

- 转发上游**完整**音频(标准音质),支持 200/206/416。
- 不暴露上游直链,不在服务端裁剪或缓存。
- 上游取址失败归 **502/5020,不能是 401** —— 401 会让客户端把上游故障当成自己的令牌失效去续期。
- 上游偶发 `110001` 风控时 `resolveLink` 会回退到 v2 低码率试听链(约 60 秒),此时分享页听到的是片段而不是整首 —— 这是全站播放路径共用的既有兜底,不是试听接口特有的问题。

## 4. 存储红线

- `song_share` 只存歌曲身份和元数据快照。
- **不存任何音频文件路径**:试听是转发上游音频,不是缓存;上游限时直链绝不持久化(旧库的 `preview_file` 列已在迁移里删除)。
- `access_count`、`enabled` 和时间字段用于公开分享统计与失效控制。
- 服务端无任何外部进程调用(历史上的 ffmpeg 裁剪已随转发方案移除),没有磁盘裁剪相关故障面。
