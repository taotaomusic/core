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
