# 认证与用户资料契约

[返回文档中心](README.md)

最后更新:2026-09-30

管理后台的登录体系(`/admin/auth/**`)是另一套凭据,见 [80-admin-auth-login.md](80-admin-auth-login.md);本文只讲普通用户侧。业务码语义见 [30-api-conventions.md](30-api-conventions.md)。

## 1. 注册

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

- 成功 HTTP 201。
- 新注册必须额外传 `email` 与 `verificationCode`;先调用 `POST /api/v1/auth/email-verification` 发送六位验证码。
- 注册邮箱有域名白名单:对外仅承诺 QQ 邮箱(`qq.com`/`foxmail.com`),不满足返回 400/4008;内部测试域在服务端另行保留。邮箱统一转小写后参与查重。
- 验证码仅存服务进程内存、10 分钟过期,校验成功即删除,累计输错 5 次作废;发码受 `email-verification` 桶限流(每 IP 5 次/15 分钟),同邮箱 60 秒冷却(命中返回 429/4290)。
- `accessToken`、`refreshToken`、`expiresIn` 和 `user` 必须平铺在 `data` 下。
- 除用户名冲突 409/4090 外:邮箱已注册返回 409/4092,验证码错误或已过期返回 400/4009;并发注册最终依赖数据库唯一约束兜底。

## 2. 登录

```http
POST /api/v1/auth/login
```

用户名或密码错误返回 401/4011,不区分「用户名不存在」和「密码错」。

## 3. 刷新

```http
POST /api/v1/auth/refresh
```

```json
{ "refreshToken": "..." }
```

- 刷新令牌确实无效时返回 401/4012;**数据库或内部故障必须保持 5xx**。
- 客户端收到刷新接口 4xx 会清除本地会话 —— 这是「内部故障不能映射成 4xx」的原因。
- 刷新令牌消费必须是单条 `UPDATE ... RETURNING`,防止并发刷新同一令牌时双双成功(见 [44-database-operations.md](44-database-operations.md))。

## 4. 绑定与换绑邮箱

以下接口均需要访问令牌。

- 老账号首次绑定:先调 `POST /api/v1/auth/email/bind-verification`,再把同一个 `email` 与 `verificationCode` 提交到 `POST /api/v1/auth/email/bind`。
- 已绑定账号换绑:使用 `POST /api/v1/auth/email/change-verification` 与 `POST /api/v1/auth/email/change` 两个同形接口。

两种验证码用途互不通用,防止把注册验证码用于篡改已登录账号的邮箱。邮箱状态冲突返回 409/4092–4094。

## 5. 用户资料

- `GET /api/v1/auth/profile` 返回当前账号的 `username`、`nickname`、`avatarUrl`、`email` 与 `created_at`;未设置昵称时回落为用户名。
- `PATCH /api/v1/auth/profile` 接收一个或两个字段:`nickname` 为 1–24 个非控制字符,`avatarUrl` 必须为 HTTPS 地址且不超过 2048 个字符;传 `{"avatarUrl":null}` 可以清除头像,并同步删除 `user_avatars` 里的二进制行。
- 邮箱只在本人资料接口中返回,不对外暴露。

## 6. 头像上传

```http
POST /api/v1/auth/avatar
Content-Type: multipart/form-data
```

- multipart 字段名必须是 `file`,仅接受图片,最大 5 MiB。
- 服务端按**文件魔数**嗅探 PNG / JPEG / GIF / WebP(刻意排除 SVG),不信任客户端声明的 MIME。
- 头像存入 `user_avatars` 表(bytea),客户端只能拿到 HTTPS 图片地址;下载走公开的 `GET /files/avatars/:token`(`Content-Type` 按嗅探结果下发,`Cache-Control: public, max-age=31536000, immutable`)。
- 每次上传都会生成新 token(128 位随机数)并覆盖旧行:旧地址随即 404,客户端与代理对旧地址的缓存天然失效;清除头像时二进制一并删除。
- 上传成功但头像不显示时:先确认返回的 URL 与 `PUBLIC_BASE_URL`(或请求推导的来源)一致,再查 `user_avatars` 表有没有对应行(见 [72-troubleshooting-music.md](72-troubleshooting-music.md))。

`PATCH /auth/profile` 的 `avatarUrl` 同样只接受 HTTPS。
