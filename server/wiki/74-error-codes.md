# 错误码速查与日志安全

[返回文档中心](README.md)

最后更新:2026-09-27

看到错误码不知道先查什么时用本页;业务码的完整语义与设计原因见 [30-api-conventions.md](30-api-conventions.md)。

## 1. 状态码快速定位

| HTTP/业务码 | 首先检查 |
| --- | --- |
| 400/4001 | 搜索关键词 |
| 400/4005 | DTO、ParseIntPipe、query 参数 |
| 400/4006 | 播放会话字段;传输加密的头格式、AAD 构造或解密失败,握手的 `clientHello` 非法同理 |
| 400/4007 | 图片模型、提示词、URL、taskId;音源账号字段(酷我 uid 必须纯数字等) |
| 400/4013 | 加密握手被拒绝(PSK 未配置或不匹配、`device_id` 派生不一致、报文非法) |
| 401/4010 | Authorization 是否缺失或过期 |
| 401/4011 | 普通登录密码错;管理员登录失败或 2FA 票据过期 |
| 401/4012 | 刷新令牌是否已轮换、撤销或过期 |
| 401/4013 | 管理员会话不被接受(`Authorization: Bearer` 缺失、过期、已撤销,或用了已移除的 `X-Admin-Token`) |
| 401/4014 | 开放 API Key 缺失、无效、禁用或吊销 |
| 403/4030 | 管理员角色不足,或当前 IP 不在白名单中 |
| 404/4040 | 路径和全局 `/api/v1` 前缀 |
| 404/4041 | Android 版本、补丁或发布对象不存在 |
| 404/4042 | 图片任务或图片 Key 不存在 |
| 404/4043 | 公告不存在 |
| 404/4044 | 后台用户不存在 |
| 404/4045 | 歌单或分享短链不存在 |
| 409/4090 | 用户名唯一约束 |
| 409/4091 | 最低版本守卫和全量发布;加密会话失效(不存在或已过期,客户端重新握手即可) |
| 409/4092–4094 | 邮箱已注册、绑定状态或换绑状态 |
| 409/4095 | 播放会话身份冲突 |
| 409/4096 | 播放会话 historyRevision 领先服务端 |
| 429/4290 | 本地通用限流桶(换 IP 或等窗口过去就恢复) |
| 429/4291 | ApiSweet 上游限流;**也是管理账号退避锁定**(换 IP 无用,须等退避,两者按语境区分) |
| 502/5020 | 腾讯/网易音乐、IM、流式代理或未知内部错误 |
| 502/5021 | ApiSweet Key、余额或上游错误 |
| 503/5031 | IM 未启用;传输加密链路未启用(握手收到它表示按预期降级明文,不是故障);LDAP 接管的账号目录不可用不回落 |
| 503/5032 | 发布记录读写失败或 GPTIMAGE2 无 Key/额度不足 |

按入口分流:

- 管理后台登录类错误 → [71-troubleshooting-auth.md](71-troubleshooting-auth.md) §2。
- 加密链路错误 → [72-troubleshooting-music.md](72-troubleshooting-music.md) §6。
- 音源凭据相关 → [53-feature-music-sources.md](53-feature-music-sources.md) §5。

## 2. 日志安全

禁止输出:

- API Key。
- `DATABASE_URL` 完整连接串。
- 用户密码。
- 访问令牌和刷新令牌。
- 管理后台会话令牌(`ADMIN_SESSION_TOKEN` / `localStorage.taotao_admin_token`)。
- 音源账号 token、uid 与完整手机号(对外只允许掩码)。
- 悟空 IM API Token、设备 Token、消息正文;IM 同步代理的完整请求体。

允许输出:

- Key 的数据库 ID。
- 渠道名。
- 剩余额度。
- 上游 HTTP 状态码。
- 不含凭据的任务 ID 和错误码。
- 掩码后的手机号。

## 3. 文档与源码冲突时

以源码和 CodeGraph 最新索引为准,并在同一提交中修正文档(流程见 [73-troubleshooting-release.md](73-troubleshooting-release.md) §4 与 [00-code-index.md](00-code-index.md))。
