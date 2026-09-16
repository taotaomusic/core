# 故障排查

[返回文档中心](README.md)

## 1. 排查顺序

1. 查看服务是否监听目标端口。
2. 请求 `/health`。
3. 查看启动日志中的数据库状态。
4. 确认请求是否经过正确域名、反向代理和路径前缀。
5. 根据 HTTP 状态码和业务码定位模块。
6. 只在独立验证库复现数据层问题。

不要第一时间重置数据库或修改线上数据。

## 2. 服务无法启动

### 缺少 `DATABASE_URL`

表现：启动阶段直接报缺少数据库连接串。

处理：检查 `.env`、`ENV_FILE` 和进程管理器环境变量。连接串必须以 `postgres://` 或 `postgresql://` 开头。

### PostgreSQL 未就绪

表现：日志每秒输出一次连接失败，10 次后退出。

处理：

- 确认 PostgreSQL 服务状态。
- 检查端口、防火墙、用户名、密码和数据库名。
- 确认 systemd 启动顺序。
- 让进程管理器负责重启，不要把重试改成无限循环。

### NestJS 注入为 undefined

表现：启动时出现 `Cannot read properties of undefined` 或 Provider 构造参数为空。

原因：使用了 esbuild、tsx 或未生成 `emitDecoratorMetadata` 的工具。

处理：只使用 `npm run dev` 或 `npm run build`。

### 端口不合法或被占用

`PORT` 必须是 1–65535 的整数。确认没有旧验证实例仍监听 4500/4720。

## 3. 数据库错误

### `relation does not exist`

常见于重置验证库后没有重启服务。迁移只在应用启动时运行，先重置、再重启。

### 字段变成字符串

若 `createdAt`、`configVersion` 或 `apkSize` 类型异常：

- 检查列是否错误使用 bigint。
- 检查 int8 parser 是否仍注册。
- 普通大小和版本号应使用 integer。

### camelCase 字段为 undefined

检查 SQL 别名是否加双引号：

```sql
AS "songId"
```

### 并发注册偶发 502

检查唯一约束冲突是否在 `UsersRepository` 中翻译为 409/4090。不能只依赖插入前查重。

### 刷新令牌并发使用两次都成功

检查消费逻辑是否仍是单条 `UPDATE ... RETURNING`。拆成 SELECT + UPDATE 会产生竞态。

## 4. 鉴权错误

### 客户端不会自动续期

确认无效访问令牌返回 HTTP 401，而不是 403。客户端只对 401 触发续期重放。

### 用户被意外踢回登录页

检查：

- `/auth/refresh` 是否把数据库故障错误映射成 4xx。
- 音乐或图片上游 401 是否被直接透传。
- “没有歌词”“播放地址失败”等业务错误是否误用了 401。

### bootstrap 返回 401

这是热更新红线。`/app/bootstrap` 必须 `@Public()`，公开守卫只能尝试解析令牌，失败后继续放行。

## 5. 搜索、播放和歌词

### 搜索显示 0 首但服务返回 200

检查响应是否被成功信封包裹。`/search` 首行必须直接包含 `type: song`，Content-Type 必须包含 `application/x-ndjson`。

### 搜索迟迟不展示

检查 `X-Accel-Buffering: no` 是否存在，以及 nginx 是否仍缓冲响应。

### 搜索歌曲不可播放

搜索只返回元信息。检查客户端是否随后请求 `/songs/{id}/link`，并携带搜索结果里的 `mid` 和 `type`。

### 播放请求得到上游 401

后端不能透传。检查 StreamService 是否把所有上游非 2xx 统一映射为 502。

### 歌词变成 JSON 文本

旧客户端默认需要裸文本。只有显式 `format=json` 时才返回 JSON。

## 6. 图片生成

### 创建返回 503/5032

原因：没有 `GPTIMAGE2` Key，或所有 Key 额度不足。

安全查询：

```sql
SELECT id, channel, quota
FROM api_key
WHERE channel = 'GPTIMAGE2'
ORDER BY quota DESC, id;
```

不要查询或输出 `key` 列。

### 创建返回 502/5021

检查：

- ApiSweet 服务状态。
- Key 是否有效、过期或被禁用。
- IP 白名单。
- 上游账户余额。
- `APISWEET_BASE_URL`。

第三方 401/402 被转换为 502 是预期行为。

### 创建失败但额度减少

查看日志是否出现“图片生成额度归还失败”。正常失败路径会按 `api_key.id` 归还预扣额度；若数据库同时故障，退款 SQL 可能失败，需要人工核对。

### 轮询返回 404/4042

本地 `image_generation_task` 没有该任务。只允许轮询通过当前后端成功创建并落库的任务。

### 轮询一直 IN_PROGRESS

检查上游任务状态和客户端总超时。不要无限提高轮询频率；建议间隔约 3 秒，并设置最大等待时间。

### 轮询提示任务不属于当前 Key

检查任务的 `api_key_id` 是否仍指向创建时的 Key。不要覆盖旧 Key 内容，应向 Key 池 INSERT 新行。

### 完成但没有图片

上游 `COMPLETED` 必须同时提供 `result.image_url`。缺失会按无效上游响应返回 502。

## 7. 热更新

### 登记后客户端看不到版本

检查：

- `rollout_percent` 是否仍为 0。
- 发布是否 `enabled = 1`。
- 客户端 versionCode 是否小于发布版本。
- SDK 是否满足 `min_sdk`。
- 灰度主体是否命中。

### 安装后仍反复提示更新

登记时可能错误读取了构建后的 `version.properties`。实际 APK 版本必须取自 `output-metadata.json`。

### APK 下载永久卡住

核对数据库 `apk_size` 与文件真实字节数；检查 Range 206、`Content-Range` 和越界 416。

### X-Latest-Version-Code 不更新

只有 `rollout_percent = 100` 且启用的最高版本进入响应头。调整放量后确认缓存已失效。

## 8. 契约验证失败

先区分：

- 代码真的破坏契约。
- 外部腾讯音乐接口临时波动。
- 验证库未正确重置或服务未重启。
- 4720 端口运行的是旧进程。

推荐顺序：

1. 停止旧验证实例。
2. 重置 `music_verify`。
3. 用最新源码启动 4720。
4. 再运行契约脚本。
5. 只针对失败分组定位，不修改正式库。

## 9. 日志安全

禁止输出：

- API Key。
- `DATABASE_URL` 完整连接串。
- 用户密码。
- 访问令牌和刷新令牌。
- `ADMIN_TOKEN`。

允许输出：

- Key 的数据库 ID。
- 渠道名。
- 剩余额度。
- 上游 HTTP 状态码。
- 不含凭据的任务 ID 和错误码。

## 10. 状态码快速定位

| HTTP/业务码 | 首先检查 |
| --- | --- |
| 400/4001 | 搜索关键词 |
| 400/4005 | DTO、ParseIntPipe、query 参数 |
| 400/4007 | 图片模型、提示词、URL、taskId |
| 401/4010 | Authorization 是否缺失或过期 |
| 401/4012 | 刷新令牌是否已轮换、撤销或过期 |
| 401/4013 | `X-Admin-Token` |
| 404/4040 | 路径和全局 `/api/v1` 前缀 |
| 404/4041 | Android/桌面版本、补丁或发布对象不存在 |
| 404/4042 | 图片任务、图片 Key 或本地桌面对象不存在 |
| 404/4043 | 公告不存在 |
| 404/4044 | 后台用户不存在 |
| 404/4045 | 歌单或分享短链不存在 |
| 409/4090 | 用户名唯一约束 |
| 409/4091 | 最低版本守卫和全量发布 |
| 409/4092–4094 | 邮箱已注册、绑定状态或换绑状态 |
| 409/4095 | 播放会话身份冲突 |
| 409/4096 | 播放会话 historyRevision 领先服务端 |
| 429/4290 | 本地通用限流桶 |
| 429/4291 | ApiSweet 上游限流 |
| 502/5020 | 腾讯/网易音乐、IM、流式代理或未知内部错误 |
| 502/5021 | ApiSweet Key、余额或上游错误 |
| 503/5031 | IM 未启用 |
| 503/5032 | 发布记录读写失败或 GPTIMAGE2 无 Key/额度不足 |
| 503/5034 | 分享试听 ffmpeg 不可用 |

## 11. 只读诊断命令

检查端口：

```powershell
Get-NetTCPConnection -State Listen | Where-Object LocalPort -In 4500,4720
```

检查 Node 进程：

```powershell
Get-Process node -ErrorAction SilentlyContinue
```

检查健康响应：

```powershell
curl.exe -i http://127.0.0.1:4500/health
```

检查数据库连通性：

```powershell
psql "$env:DATABASE_URL" -c "SELECT 1"
```

检查所需表是否存在，不读取业务数据：

```sql
SELECT table_name
FROM information_schema.tables
WHERE table_schema = 'public'
ORDER BY table_name;
```

检查图片任务统计，不读取提示词和 Key：

```sql
SELECT channel, state, count(*) AS count
FROM image_generation_task
GROUP BY channel, state
ORDER BY channel, state;
```

## 12. 何时停止自行处理

遇到以下情况应暂停写操作并先备份或请求确认：

- 连接串可能指向正式库，但无法确认。
- 需要 DROP、TRUNCATE、删除 APK 或删除 Key。
- 正式库表结构与当前迁移定义不一致。
- 同一个 taskId 对应多个外部账单或疑似重复扣费。
- 热更新最低版本已抬高但没有可下载的全量 APK。
- 回滚需要恢复数据库结构而不仅是替换 `dist/`。

## 13. 桌面更新与内容寻址文件

### `desktop/bootstrap` 返回无更新

按顺序确认：

1. `channel`、`architecture` 和客户端 `versionCode` 与后台发布记录一致。
2. 目标 `desktop_release.enabled = 1`，灰度比例命中当前用户/设备哈希。
3. 每个 `desktop_jar` 的对象确实存在于 `DESKTOP_RELEASE_DIR`，sha256 和文件大小一致。
4. 客户端是否低于桌面最低版本；强制更新只能使用同架构 100% 放量版本。

无效或过期 Authorization 不应导致 bootstrap 401；若出现 401，先检查 `AccessTokenGuard` 的
`@Public()` 元数据和反向代理是否篡改了路径。

### 桌面上传 400/4006 或下载 404/4042

上传接口接收原始字节和 query `sha256`，不能发送 JSON/base64/multipart。代理层检查：

- `client_max_body_size` 是否超过模块大小（单模块最多 500 MiB）；
- 是否启用了压缩/转码导致字节改变；
- `DESKTOP_RELEASE_DIR` 运行用户是否可写；
- 发布清单里的相对路径是否包含 `..`、反斜杠或重复项。

数据库有记录但对象不存在时不要手工改 sha256；重新上传同一对象或重新发布清单，并保留旧对象供
正在下载的客户端完成请求。

### 差分没有出现

只有上一版本同路径文件存在、源 sha256 匹配且生成结果小于目标完整模块 90% 时才会下发差分。
Courgette 失败会回退 bsdiff-wasm；两者都失败或差分过大时返回完整模块是预期行为。

## 14. IM、头像和邮件

### IM 返回 503/5031

检查 `IM_ENABLED=true` 是否通过环境校验，重启后确认 `IM_INTERNAL_API_BASE_URL` 可访问。启用时：

- 内部 API 必须是 `http://`/`https://`；外部 Gateway 必须是 `tcp://`；
- `IM_SESSION_LIFETIME_SECONDS` 必须在 60–86400；
- 5001 只允许本机/私网访问，5100 才是客户端 TCP 端口。

### IM 返回 502/5020 或同步格式错误

从 NestJS 主机执行悟空 IM `/health`，检查 HTTP 超时和服务端 Token。不要把悟空原始响应完整写日志，
只记录 HTTP 状态、用户 ID 和无敏感字段的错误摘要。聊天消息正文不在 PostgreSQL，不能用 SQL 查“消息是否丢失”。

### 头像上传失败

`POST /auth/avatar` 必须是 multipart 字段 `file`，MIME 以 `image/` 开头且不超过 5 MiB；服务端必须
配置 `LSKY_API_KEY`。Lsky 非 2xx 或返回结构不完整会映射为 400/4000，不能把图床令牌错误当成用户 401。

### 注册验证码发送失败

确认 `SMTP_HOST`、`SMTP_USER`、`SMTP_PASSWORD`、`SMTP_FROM` 四项完整，`SMTP_PORT` 在 1–65535；
同邮箱 60 秒内不能重复发码。契约测试可在 `NODE_ENV=test` 使用六位
`EMAIL_VERIFICATION_TEST_CODE`，普通开发/生产进程填写该变量会在启动时拒绝。

## 15. 分享试听和公共链接

### 分享元数据 404/4045

检查 token 是否符合 8–24 位规则、记录 `enabled` 是否为 1，以及 `PUBLIC_BASE_URL` 是否拼出正确
域名。公开元数据不需要登录；若反向代理把 `/api/v1/public/shares` 重写到需要 Authorization 的
位置，会表现为 401 而不是 404。

### 试听 503/5034 或 Range 异常

确认 `FFMPEG_BIN` 可执行、`SHARE_PREVIEW_DIR` 可写且磁盘有空间；首次请求会下载上游低音质并裁剪
最多 60 秒 64 kbps MP3。试听接口支持 200/206/416，必须保留 `Range`、`Content-Range` 和
`Accept-Ranges`，不要由代理层缓存成 JSON 错误页。

## 16. 代码索引与文档漂移

发现文档写了不存在的路由或漏掉新模块时，先运行：

```powershell
codegraph index server
codegraph status server --json
codegraph query --path server --kind route --limit 200 --json ""
```

再对照 [00-code-index.md](00-code-index.md) 的 89 条路由和 21 张表。不要通过 `grep` 猜测 Controller
是否已注册，也不要在未确认索引状态时直接修改契约文档。
