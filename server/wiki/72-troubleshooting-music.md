# 排障:搜索播放、图片、分享、IM 与加密

[返回文档中心](README.md)

最后更新:2026-09-30

业务域排障合集。错误码速查见 [74-error-codes.md](74-error-codes.md);各业务域契约见 31–37 与 50–53 各篇。

## 1. 搜索、播放和歌词

### 搜索显示 0 首但服务返回 200

检查响应是否被成功信封包裹。`/search` 首行必须直接包含 `type: song`,Content-Type 必须包含 `application/x-ndjson`。

### 搜索迟迟不展示

检查 `X-Accel-Buffering: no` 是否存在,以及 nginx 是否仍缓冲响应。

### 搜索歌曲不可播放

搜索只返回元信息。检查客户端是否随后请求 `/songs/{id}/link`,并携带搜索结果里的 `mid` 和 `type`。

### 播放请求得到上游 401

后端不能透传。检查 StreamService 是否把所有上游非 2xx 统一映射为 502。

### 歌词变成 JSON 文本

旧客户端默认需要裸文本。只有显式 `format=json` 时才返回 JSON。

## 2. 图片生成

### 创建返回 503/5032

原因:没有 `GPTIMAGE2` Key,或所有 Key 额度不足。

安全查询:

```sql
SELECT id, channel, quota
FROM api_key
WHERE channel = 'GPTIMAGE2'
ORDER BY quota DESC, id;
```

不要查询或输出 `key` 列。

### 创建返回 502/5021

检查:

- ApiSweet 服务状态。
- Key 是否有效、过期或被禁用。
- IP 白名单。
- 上游账户余额。
- `APISWEET_BASE_URL`。

第三方 401/402 被转换为 502 是预期行为。

### 创建失败但额度减少

查看日志是否出现「图片生成额度归还失败」。正常失败路径会按 `api_key.id` 归还预扣额度;若数据库同时故障,退款 SQL 可能失败,需要人工核对(一致性边界见 [44-database-operations.md](44-database-operations.md))。

### 轮询返回 404/4042

本地 `image_generation_task` 没有该任务。只允许轮询通过当前后端成功创建并落库的任务。

### 轮询一直 IN_PROGRESS

检查上游任务状态和客户端总超时。不要无限提高轮询频率;建议间隔约 3 秒,并设置最大等待时间。

### 轮询提示任务不属于当前 Key

检查任务的 `api_key_id` 是否仍指向创建时的 Key。不要覆盖旧 Key 内容,应向 Key 池 INSERT 新行。

### 完成但没有图片

上游 `COMPLETED` 必须同时提供 `result.image_url`。缺失会按无效上游响应返回 502。

## 3. IM

### IM 返回 503/5031

检查 `IM_ENABLED=true` 是否通过环境校验,重启后确认 `IM_INTERNAL_API_BASE_URL` 可访问。启用时:

- 内部 API 必须是 `http://`/`https://`;外部 Gateway 必须是 `tcp://`;
- `IM_SESSION_LIFETIME_SECONDS` 必须在 60–86400;
- 5001 只允许本机/私网访问,5100 才是客户端 TCP 端口。

### IM 返回 502/5020 或同步格式错误

从 NestJS 主机执行悟空 IM `/health`,检查 HTTP 超时和服务端 Token。不要把悟空原始响应完整写日志,只记录 HTTP 状态、用户 ID 和无敏感字段的错误摘要。聊天消息正文不在 PostgreSQL,不能用 SQL 查「消息是否丢失」。

## 4. 头像和邮件

### 头像上传失败

`POST /auth/avatar` 必须是 multipart 字段 `file`,按文件头嗅探 PNG / JPEG / GIF / WebP,≤5 MiB。头像存在 `user_avatars` 表(bytea),下载走公开的 `GET /files/avatars/:token`;上传成功但头像不显示时先确认返回的 URL 与 `PUBLIC_BASE_URL`(或请求推导的来源)一致、再查该表有没有对应行。

### 注册验证码发送失败

确认 `SMTP_HOST`、`SMTP_USER`、`SMTP_PASSWORD`、`SMTP_FROM` 四项完整,`SMTP_PORT` 在 1–65535;同邮箱 60 秒内不能重复发码。契约测试可在 `NODE_ENV=test` 使用六位 `EMAIL_VERIFICATION_TEST_CODE`,普通开发/生产进程填写该变量会在启动时拒绝。

## 5. 分享试听和公共链接

### 分享元数据 404/4045

检查 token 是否符合 8–24 位规则、记录 `enabled` 是否为 1,以及 `PUBLIC_BASE_URL` 是否拼出正确域名。公开元数据不需要登录;若反向代理把 `/api/v1/public/shares` 重写到需要 Authorization 的位置,会表现为 401 而不是 404。

### 试听 502/5020 或 Range 异常

试听是**转发上游音频**,不再裁剪也不再落盘,所以没有 ffmpeg / 磁盘相关故障。

- 取不到地址一律归 502/5020(上游风控、歌曲下架),**绝不能是 401** —— 那会让客户端把上游故障当成自己的令牌失效去续期。
- 接口支持 200/206/416,必须保留 `Range`、`Content-Range` 和 `Accept-Ranges`,不要由代理层缓存成 JSON 错误页。
- 上游偶发 `110001` 风控时 `resolveLink` 会回退到 v2 低码率试听链(约 60 秒),此时分享页听到的是片段而不是整首 —— 这是全站播放路径共用的既有兜底,不是试听接口特有的问题。

## 6. 传输加密

加密链路的具体业务码以 `server/src/crypto/` 下 `crypto.controller.ts`、`crypto.middleware.ts` 和 `native-loader.ts` 的实际实现为准;协议说明见 [37-api-crypto.md](37-api-crypto.md)。

### 握手返回 503/5031

`POST /api/v1/crypto/handshake` 返回 503/5031 表示加密链路未启用,客户端应回退明文:

- 加密产物缺失(启动日志出现「当前平台 … 无对应加密产物,传输加密降级为明文」或「未找到 taotao_crypto.node,先执行 tools/fetch-crypto.ps1 拉产物;传输加密降级为明文」)。
- 产物协议版本过低(「加密产物协议版本 … 低于设备绑定要求(>=2),降级为明文」)。
- 加载产物本身抛异常(`ERROR` 级「加载 taotao_crypto.node 失败,降级为明文」)。

部署上这是预期的优雅降级,不影响其它功能;需要启用加密时补齐产物即可。

### 握手返回 400/4013「握手被拒绝」

- `device_id` 与 PSK 派生不匹配 —— `device_id` 折进握手密钥,两侧派生输入不一致(例如客户端重装或换机导致设备号变化)时 MAC 失配,握手被拒。
- 客户端拿到的 PSK 过旧:PSK 由服务端经 `GET /crypto/psk` **动态下发**(需已登录),服务端换了 `CRYPTO_PSK_HEX` 后旧 PSK 的握手会失败,重新拉一次 PSK 再握手即可。
- ClientHello 报文非法。`clientHello` 字段缺失或 base64 格式错误则是 400/4006。

PSK 本身不存在「未配置」的故障形态:未配 `CRYPTO_PSK_HEX` 时服务端启动随机生成一把并照常启用加密(日志有对应 WARN,重启会变);只有加密产物缺失才会整体降级(见上面的 503/5031)。

### 请求返回 426/4007「此接口要求加密访问」

**强制加密白名单**内的接口(当前仅 `GET /api/v1/favorites`,见 `main.ts`)在加密链路已启用时拒绝无加密头的明文请求,防降级攻击。客户端升级到支持加密的版本即可;白名单随客户端逐个接入再扩,其余接口对明文完全透明。

### 请求返回 400/4006

加密头格式错误、AAD 上下文构造失败、请求体解密失败或读取失败统一收敛为 400/4006。

检查客户端 `X-Taotao-Crypto` 头格式,以及 AAD 是否按请求方法 + 路径(含 query)构造;**改路由形状(路径、query 结构)会让走加密的旧客户端解密失败**。

### 请求返回 409/4091「加密会话失效,请重新握手」

加密会话不存在或已过期。客户端重新握手即可,不是业务故障;长期运行的服务由会话清理定时器每分钟回收过期会话。

## 7. 音源账号(酷我/波点)

详见 [53-feature-music-sources.md](53-feature-music-sources.md) 的常见问题一节;速记:

- probe 失败先看 `last_status` / `last_error` 两列,不要凭感觉重复探测。
- 「搜得到放不出」先查 `music_source_account` 有没有启用行(表空 = 匿名链路),再查酷我 `uid` 是否纯数字。
- 改了凭据要等最长 30 秒缓存 TTL;管理写操作会主动清缓存。
