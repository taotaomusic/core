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

## 2. 当前端口事实与防火墙

2026-08-26 对 `114.66.23.232` 的只读探测结果：

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
tcp://114.66.23.232:5100
```

NestJS 调用悟空 IM 产品 API 必须使用：

```text
http://127.0.0.1:5001
```

若使用 UFW，推荐规则是：对外仅允许 80/443（现有网站）和 5100/TCP（悟空原生客户端）；删除
5001、5200、5300、4500、5432 的公网入站规则。5001 当前可从公网访问，必须尽快改为悟空 IM
监听 `127.0.0.1:5001` 或用云安全组仅允许本机/私网。`127.0.0.1` 不会跨机器可达；若 NestJS 与
悟空 IM 分机部署，应改为私网 IP，并在安全组精确放行两台机器之间的 5001/TCP。

## 3. 环境变量

生产 `.env`：

```dotenv
IM_ENABLED=true
IM_INTERNAL_API_BASE_URL=http://127.0.0.1:5001
IM_EXTERNAL_GATEWAY_URL=tcp://114.66.23.232:5100
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
5. 在 Android SDK 连接 `tcp://114.66.23.232:5100`，发送文字消息并测试离线、重连和退出登录。
6. 最后再把聊天页面加入手写导航的 `switchTab`、`AnimatedContent` 和 `BackHandler` 三处。

## 5. 故障定位

- 业务接口返回 `5031`：`IM_ENABLED` 未开启。
- 业务接口返回 `5020`：NestJS 无法访问 `IM_INTERNAL_API_BASE_URL`，先在应用宿主机执行
  `curl.exe http://127.0.0.1:5001/health`。
- 把 `IM_EXTERNAL_GATEWAY_URL` 写成 `http://...:5100`：启动时会被环境校验拒绝；5100 是 TCP。
- 能获得 Token 但任意 Token 都能 CONNECT：Gateway Token 校验尚未生效，不能上线。
- 登录另一台 Android 后前一台断线：这是当前 `device_flag=Android` 的单设备策略，符合 MVP 设计。
