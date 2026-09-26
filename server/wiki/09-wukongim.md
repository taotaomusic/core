# 悟空 IM 接入与部署

[返回文档中心](README.md)

本项目使用悟空 IM `v2.2.5-20260422` 提供聊天连接、消息顺序和离线同步；桃桃音乐 NestJS
服务继续负责账号、登录、连接凭据、群成员权限和业务审计。聊天消息正文不复制进 PostgreSQL。

## 1. 身份与会话边界

悟空 IM UID 是服务端生成的 UUID，例如 `550e8400-e29b-41d4-a716-446655440000`。新注册用户会
立即写入 `users.im_uid`；历史账号在首次申请 IM 会话时自动补齐。因此 UID 稳定却不可枚举；严禁
使用递增的 `users.id`、用户名或昵称。

Android 在已有桃桃访问令牌的前提下调用：

```text
POST /api/v1/im/session
Authorization: Bearer <桃桃访问令牌>
Content-Type: application/json

{"deviceId":"<应用持久化 UUID>"}
```

成功响应沿用项目统一信封，`data` 内含 `uid`、`token`、`tokenExpiresAt`、`deviceFlag`、
`deviceLevel` 和 `gatewayUrl`。其中 Token 仅交给悟空 Android SDK；服务端数据库只保存 SHA-256
哈希。客户端退出登录前调用 `DELETE /api/v1/im/session`，服务端会关闭该帐号的 Android 设备会话。

当前悟空 IM 的设备退出按 `device_flag` 生效，因此 MVP 中一个帐号重新在另一台 Android 登录，
旧 Android 会话会被覆盖；未来支持多台 Android 并行时，必须先确认 Gateway 对 `device_id` 与 Token
的逐设备校验能力，不能只改 PostgreSQL 约束。

`IM_SESSION_LIFETIME_SECONDS` 是客户端续签周期，不代替悟空 IM Gateway 的连接鉴权。上线前必须
用真实失效 Token 验证 CONNECT 会被拒绝；仅调用 `/user/token` 不能证明连接端已启用 Token 校验。

## 2. 历史端口探测与防火墙基线

以下是 2026-08-26 对 `114.66.23.232` 的一次只读探测记录，**不是当前在线状态**；部署或排障前必须
用现场云安全组、主机防火墙和 `Test-NetConnection` 重新确认：

| 端口 | 探测结果 | 正确用途 | 应否公网开放 |
| --- | --- | --- | --- |
| 5001/TCP | `GET /health` 返回 200 | 悟空 IM 产品 HTTP API | 否，仅本机/私网 |
| 5100/TCP | HTTP 请求被直接断开 | WKProto 原生 TCP Gateway | 是，供客户端连接 |
| 5200/TCP | TCP 可连通 | WebSocket Gateway | 仅需要 Web/WSS 时开放 |
| 5300/TCP | HTTP 返回 401 | 悟空 IM Manager | 否，仅 VPN/堡垒机 |
| 4500/TCP | 桃桃音乐 NestJS | 音乐业务 HTTP API | 否，由现有反向代理转发 |
| 5432/TCP | PostgreSQL | 业务数据库 | 否，仅私网 |

因此 `http://114.66.23.232:5100` 不是 HTTP API，不能配置给 `HttpURLConnection` 或悟空服务端
管理请求。Android Gateway 地址必须是：

```text
tcp://im.xydaigua.cn:5100
```

NestJS 调用悟空 IM 产品 API 必须使用：

```text
http://127.0.0.1:5001
```

若使用 UFW，推荐规则是：对外仅允许 80/443（现有网站）和 5100/TCP（悟空原生客户端）；删除
5001、5200、5300、4500、5432 的公网入站规则。上次探测曾显示 5001 可从公网访问，必须确认已改为悟空 IM
监听 `127.0.0.1:5001` 或用云安全组仅允许本机/私网。`127.0.0.1` 不会跨机器可达；若 NestJS 与
悟空 IM 分机部署，应改为私网 IP，并在安全组精确放行两台机器之间的 5001/TCP。

## 3. 环境变量

生产 `.env`：

```dotenv
IM_ENABLED=true
IM_INTERNAL_API_BASE_URL=http://127.0.0.1:5001
IM_EXTERNAL_GATEWAY_URL=tcp://im.xydaigua.cn:5100
IM_API_TOKEN=
IM_SESSION_LIFETIME_SECONDS=900
```

`IM_API_TOKEN` 只有在悟空 IM 产品 API 已配置服务端访问令牌时才填写，绝不能返回客户端或写入日志。
启用 IM 后，配置校验会拒绝把 5100 写成 `http://`，防止再次把原生 TCP Gateway 当作 HTTP 服务。

## 4. 上线顺序

1. 先在悟空 IM Gateway 启用并验证 Token 鉴权，使用一个失效 Token 的 CONNECT 做拒绝测试。
2. 把悟空 IM 产品 API 绑定到 `127.0.0.1:5001`，收紧 5001、5200、5300 的云安全组和主机防火墙。
3. 在 NestJS `.env` 启用 `IM_ENABLED=true`，重启服务，让幂等迁移创建 `im_device_session`。
4. 使用真实测试帐号调用 `POST /api/v1/im/session`，确认悟空 IM `/user/token` 收到成功响应。
5. 在 Android SDK 连接 `tcp://im.xydaigua.cn:5100`，发送文字消息并测试离线、重连和退出登录。
6. 最后再把聊天页面加入手写导航的 `switchTab`、`AnimatedContent` 和 `BackHandler` 三处。

## 5. 故障定位

- 业务接口返回 `5031`：`IM_ENABLED` 未开启。
- 业务接口返回 `5020`：NestJS 无法访问 `IM_INTERNAL_API_BASE_URL`，先在应用宿主机执行
  `curl.exe http://127.0.0.1:5001/health`。
- 把 `IM_EXTERNAL_GATEWAY_URL` 写成 `http://...:5100`：启动时会被环境校验拒绝；5100 是 TCP。
- 能获得 Token 但任意 Token 都能 CONNECT：Gateway Token 校验尚未生效，不能上线。
- 登录另一台 Android 后前一台断线：这是当前 `device_flag=0`（悟空 Android SDK 固定值）的单设备策略，符合 MVP 设计。

## 6. 当前 HTTP 接口契约

所有接口实际前缀为 `/api/v1`，都需要桃桃访问令牌；IM 未启用时统一返回 HTTP 503、业务码 5031。
`/im/**` 与其它 `/api/v1/*` 路由一样处于传输加密中间件的保护范围内（客户端携带 `X-Taotao-Crypto`
加密头时逐请求解密回帧；未加密请求完全透明）。
会话和同步请求分别使用独立的进程内限流桶：会话每用户 30 次 + 每 IP 180 次/15 分钟，
同步/撤回/已读每用户 300 次 + 每 IP 1,800 次/15 分钟。

| 路径 | 输入约束 | 返回/副作用 |
| --- | --- | --- |
| `POST /im/session` | `deviceId` 必须是 16–128 位 `[A-Za-z0-9._-]` | 返回 `uid`、`token`、`tokenExpiresAt`、`deviceFlag`、`deviceLevel`、`gatewayUrl`；同用户同 `device_flag` 新会话覆盖旧会话 |
| `DELETE /im/session` | 无 | 撤销当前 Android 会话，重复调用安全 |
| `POST /im/sync/conversations` | `lastMessageSeqs` 最多 20,000；`messageCount` 1–20，默认 10；`version` 默认 0 | 代理悟空会话同步，服务端规范化数组/对象响应 |
| `POST /im/sync/channel-messages` | `channelId` UUID；序列范围合法；`limit` 1–50，默认 50；`pullMode` 0/1 | 拉取频道消息 |
| `POST /im/messages/revoke` | `channelId` UUID，`messageId` ≤128，`clientMsgNo` ≤256 | 发送悟空内部命令 type 99；不存在公开 `/message/revoke` 路由 |
| `GET /im/contacts?uids=` | 最多 50 个 UUID，自动排除当前用户 | 从业务库读取昵称、头像和 IM UID |
| `POST /im/conversations/read` | `channelId` UUID | 调用悟空接口清除未读 |

悟空 HTTP API 超时 5 秒，只接受 2xx。服务端不会记录内部 API Token、设备 Token、消息正文或完整
请求体；故障统一收敛为 502/5020，不能把悟空的 401 直接透传成桃桃用户登录失效。

## 7. 数据库与生命周期细节

`users.im_uid` 使用部分唯一索引。新注册用户立即生成 UUID，历史账号在首次申请会话时懒生成；
生成失败会回滚本次会话，不会返回没有 UID 的半成品凭据。`im_device_session` 只保存
`device_id_hash`、`token_hash`、过期和撤销时间，Token 明文只在签发响应中出现一次。

客户端退出桃桃账号时应先调用 `DELETE /im/session`，再清理本地悟空 Token。重新登录同一 Android
账号会覆盖旧 `device_flag=0` 凭据，这是当前 MVP 的单 Android 设备策略；扩展多设备前需要先验证
Gateway 对 `device_id` 的逐设备校验，不能只放宽数据库主键。

## 8. 上线后验证清单

```powershell
$origin = "https://你的域名"

# 业务层：无效桃桃令牌必须是 401；IM 未启用必须是 503/5031
curl.exe -i "$origin/api/v1/im/session" -H "content-type: application/json" -d '{"deviceId":"short"}'

# 网络层：5001 仅供 NestJS 访问，5100 用 TCP 客户端探测，不用 curl 当 HTTP 调用
curl.exe http://127.0.0.1:5001/health
Test-NetConnection im.xydaigua.cn -Port 5100
```

真正上线前还要用失效悟空 Token 做 CONNECT 拒绝测试、验证离线消息和频道同步、验证撤回/已读，
并确认日志没有出现 Token 或消息正文。端口是否公网开放应以当前云安全组和主机防火墙配置为准，本文
的端口表只描述代码默认值，不代替现场探测记录。
