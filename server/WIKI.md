# 桃桃音乐后端 Wiki

本文是桃桃音乐后端的开发与运维入口，面向后端开发、客户端联调和部署维护人员。

- 项目总览与接口细节：[README.md](README.md)
- 发布和热更新红线：[../RELEASE.md](../RELEASE.md)
- 后端源码规范：[../AGENTS.md](../AGENTS.md)

## 1. 服务定位

后端基于 NestJS、TypeScript 和 PostgreSQL，主要承担以下职责：

- 用户注册、登录、访问令牌和刷新令牌轮换。
- 收藏数据持久化。
- 腾讯音乐搜索、歌曲信息、播放地址和歌词适配。
- Android APK 发布、灰度放量、远程配置和下载。
- `gpt-image-2` 图片生成任务创建与状态轮询。

音乐媒体、封面、歌词和生成图片不落本地文件系统，服务仅返回或转发上游地址。数据库保存业务状态、发布记录、第三方 API Key 和图片任务元数据。

## 2. 技术栈

| 分类 | 选型 |
| --- | --- |
| 运行时 | Node.js 20 或更高版本 |
| Web 框架 | NestJS 11 + Express |
| 语言 | TypeScript，严格模式 |
| 数据库 | PostgreSQL |
| 配置 | `@nestjs/config` + `.env` |
| 开发运行 | `ts-node` |
| 生产构建 | `tsc` |

不能使用 esbuild 或 tsx 构建、运行后端。NestJS 的构造器注入依赖 TypeScript 生成的 `emitDecoratorMetadata`，缺失后会导致运行时注入失败。

## 3. 目录结构

```text
server/
├─ src/
│  ├─ main.ts                 # 应用启动、全局前缀、JSON 解析与 ValidationPipe
│  ├─ app.module.ts           # 根模块与全局守卫、过滤器、拦截器
│  ├─ auth/                   # 注册、登录、令牌签发与轮换
│  ├─ common/                 # 错误、装饰器、守卫、拦截器、限流
│  ├─ config/                 # 环境变量加载与类型化配置
│  ├─ database/               # PostgreSQL 连接与幂等建表
│  ├─ favorites/              # 收藏业务
│  ├─ image-generation/       # 图片生成、Key 池与任务持久化
│  ├─ music/                  # 搜索、播放、歌词接口
│  ├─ release/                # APK 发布、灰度、远程配置
│  └─ upstream/               # 腾讯音乐上游适配
├─ tools/
│  ├─ reset-db.mjs            # 仅允许重置 verify/test 数据库
│  └─ verify-contract.mjs     # 后端契约验证
├─ .env.example
├─ package.json
└─ tsconfig.json
```

## 4. 本地启动

### 4.1 准备环境

需要安装：

- Node.js 20+
- PostgreSQL

创建开发数据库：

```powershell
psql -U postgres -c "CREATE DATABASE music"
```

安装依赖并准备配置：

```powershell
cd server
npm install
Copy-Item .env.example .env
```

至少配置：

```env
PORT=4500
DATABASE_URL=postgres://postgres:密码@localhost:5432/music
AUTH_SECRET=至少32位随机字符串
ADMIN_TOKEN=发布管理令牌
PUBLIC_BASE_URL=https://你的域名
APISWEET_BASE_URL=https://apisweet.com
```

ApiSweet 的 API Key 不写入 `.env`，由数据库管理，见“图片生成”章节。

### 4.2 启动和构建

```powershell
# 开发模式
npm run dev

# 生产构建
npm run build

# 运行生产产物
npm start
```

默认接口前缀是 `/api/v1`，健康检查是例外：

```text
GET /health
```

## 5. 请求处理链路

普通请求依次经过：

```text
请求
  → AccessTokenGuard
  → RateLimitGuard
  → Controller / Service / Repository
  → EnvelopeInterceptor
  → LatestVersionHeaderInterceptor
  → SecurityHeadersInterceptor
  → 响应
```

全局规则：

- 路由默认需要访问令牌，公开接口必须显式添加 `@Public()`。
- 普通成功响应由拦截器包装成统一信封。
- 业务异常由 `AllExceptionsFilter` 转成稳定的状态码和中文错误体。
- 流式、文本和二进制接口使用 `@RawResponse()`，不套成功信封。
- 新增路由不能自行绕开全局鉴权与错误约定。

## 6. 鉴权

访问令牌通过请求头传递：

```http
Authorization: Bearer <accessToken>
```

访问令牌有效期 15 分钟，刷新令牌有效期 30 天。刷新令牌只保存 SHA-256 哈希，刷新成功后立即轮换，旧令牌不能再次使用。

必须遵守：

- 无效访问令牌返回 HTTP 401，不能返回 403。
- `/auth/refresh` 只有刷新令牌确实无效时才能返回 4xx。
- 数据库或上游故障必须返回 5xx，不能借用 401。
- `/app/bootstrap` 即使携带过期令牌也必须继续返回 200。

## 7. 响应格式

普通成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

失败响应：

```json
{
  "code": 4001,
  "message": "请输入搜索关键词"
}
```

错误体的顶层 `message` 必须始终是字符串。

以下接口不套普通信封：

| 接口 | 响应类型 |
| --- | --- |
| `GET /api/v1/search` | NDJSON 流 |
| `GET /api/v1/songs/{id}/play` | 音频字节流 |
| `GET /api/v1/songs/{id}/lyrics` | 默认纯文本 |
| `GET /api/v1/app/apk/{versionCode}` | APK 二进制 |

## 8. 接口索引

### 8.1 认证

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | 公开 | 注册 |
| POST | `/api/v1/auth/login` | 公开 | 登录 |
| POST | `/api/v1/auth/refresh` | 公开 | 刷新并轮换令牌 |
| POST | `/api/v1/auth/logout` | 公开 | 注销刷新令牌 |
| GET | `/api/v1/auth/me` | 访问令牌 | 当前用户 |

### 8.2 收藏与音乐

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/favorites` | 收藏列表 |
| POST | `/api/v1/favorites/{source}/{songId}` | 添加收藏 |
| DELETE | `/api/v1/favorites/{source}/{songId}` | 删除收藏 |
| GET | `/api/v1/search` | 搜索歌曲，返回 NDJSON |
| GET | `/api/v1/songs/{id}/info` | 歌曲信息和可用音质 |
| GET | `/api/v1/songs/{id}/link` | 解析上游播放直链 |
| GET | `/api/v1/songs/{id}/play` | 服务端播放转发兜底 |
| GET | `/api/v1/songs/{id}/lyrics` | 歌词 |

### 8.3 图片生成

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/v1/draw/completions` | 创建图片生成任务 |
| GET | `/api/v1/draw/result/{taskId}` | 查询任务状态 |

两个接口都需要访问令牌。创建接口按用户和来源地址限流；状态查询使用独立限流桶，避免轮询消耗创建额度。

### 8.4 热更新

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/app/bootstrap` | 客户端启动配置和更新检查 |
| GET/HEAD | `/api/v1/app/apk/{versionCode}` | APK 下载 |
| GET | `/api/v1/app/admin/releases` | 发布列表 |
| POST | `/api/v1/app/admin/releases` | 上传 APK 原始字节 |
| POST | `/api/v1/app/admin/rollout` | 调整灰度比例 |
| POST | `/api/v1/app/admin/min-version` | 设置最低可用版本 |
| GET/POST | `/api/v1/app/admin/config` | 远程配置管理 |

管理接口使用 `X-Admin-Token`，不使用普通访问令牌。

## 9. 数据库

服务启动时通过 `database/migrations.ts` 在事务和 PostgreSQL 顾问锁内执行幂等 DDL，不需要单独运行迁移命令。

### 9.1 表说明

| 表 | 用途 |
| --- | --- |
| `users` | 用户账号和密码哈希 |
| `refresh_tokens` | 刷新令牌哈希、过期时间和撤销状态 |
| `favorites` | 用户收藏 |
| `app_release` | APK 发布记录与灰度配置 |
| `app_channel` | 渠道最低支持版本 |
| `app_config` | 远程配置 |
| `api_key` | 第三方 API Key 池和本地额度 |
| `image_generation_task` | 图片生成任务与结果 |

### 9.2 数据类型红线

- `Date.now()` 毫秒时间戳使用 `bigint`。
- 普通整数、版本号、大小和额度使用 `integer`。
- 表示布尔值的数据库字段使用 `smallint` 0/1。
- SQL 中映射 camelCase 的别名必须加双引号，例如 `AS "songId"`。
- PostgreSQL `int8` 已由数据库服务统一转换为 JavaScript `number`，不要用它保存真正的 64 位 ID。

## 10. 图片生成

### 10.1 Key 池

`api_key` 表结构：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | integer | Key 的数据库主键 |
| `channel` | text | 渠道，图片生成固定为 `GPTIMAGE2` |
| `key` | text | ApiSweet API Key |
| `quota` | integer | 本地剩余可创建任务数，不能小于 0 |

同一渠道可保存多个 Key。创建任务时按 `quota DESC, id` 选择有额度的 Key，并通过单条 `UPDATE ... RETURNING` 原子扣减，避免并发请求超额。

添加或补充 Key：

```sql
INSERT INTO api_key (channel, key, quota)
VALUES ('GPTIMAGE2', '替换为真实Key', 100)
ON CONFLICT (channel, key)
DO UPDATE SET quota = excluded.quota;
```

只查看额度，不输出密钥：

```sql
SELECT id, channel, quota
FROM api_key
ORDER BY channel, quota DESC, id;
```

注意：

- API Key 不能写入 `.env`、源码、日志或仓库文档。
- 当前每个成功创建的任务消耗 1 份本地额度。
- 上游拒绝、网络失败或任务未能落库时会归还预占额度。
- 不要删除仍被任务引用的 Key，外键会阻止该操作。

### 10.2 任务表

`image_generation_task` 保存：

| 字段 | 说明 |
| --- | --- |
| `task_id` | 上游任务 ID，也是本表主键 |
| `prompt` | 创建任务时的完整提示词 |
| `consumed_quota` | 本任务消耗的本地额度 |
| `channel` | 创建渠道，当前为 `GPTIMAGE2` |
| `state` | `IN_PROGRESS`、`COMPLETED` 或 `FAILED` |
| `completed` | 是否进入终态，0/1 |
| `image_url` | 完成后的图片地址，未完成或失败时为空 |
| `api_key_id` | 创建任务时使用的 Key ID |

轮询先从任务表找到 `api_key_id`，再使用关联的原 Key 请求 ApiSweet。这样同一渠道存在多个 Key 时不会查错任务。

### 10.3 创建请求

```http
POST /api/v1/draw/completions
Authorization: Bearer <桃桃音乐访问令牌>
Content-Type: application/json
```

```json
{
  "model": "gpt-image-2",
  "prompt": "一只可爱的猫咪在草地上玩耍",
  "aspectRatio": "1:1",
  "imageSize": "1K",
  "quality": "high",
  "images": ["https://example.com/reference.png"]
}
```

成功响应：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": "task_xxxxx",
    "status": "IN_PROGRESS"
  }
}
```

### 10.4 状态轮询

客户端建议每隔约 3 秒调用：

```http
GET /api/v1/draw/result/task_xxxxx
Authorization: Bearer <桃桃音乐访问令牌>
```

- `IN_PROGRESS`：继续轮询。
- `COMPLETED`：停止轮询，读取 `result.imageUrl`。
- `FAILED`：停止轮询，读取 `error.message`。

第三方 401 表示后端保存的 API Key 有问题，服务会转换为 502，不能让客户端误以为自己的登录令牌失效。

## 11. 限流

| 用途 | 阈值 |
| --- | --- |
| 登录 / 注册 | 来源地址 10 次 / 15 分钟 |
| 更新检查与 APK 下载 | 设备 60 次 + 来源地址 900 次 / 15 分钟 |
| 发布管理 | 来源地址 60 次 / 15 分钟 |
| 图片任务创建 | 用户 10 次 + 来源地址 60 次 / 15 分钟 |
| 图片任务轮询 | 用户 300 次 + 来源地址 1800 次 / 15 分钟 |

限流状态保存在进程内存中，服务重启会清空，多实例之间不共享。

## 12. 契约验证

数据库改动和后端发布前必须使用独立验证库：

```powershell
cd server

node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify

$env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
$env:PORT="4720"
$env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"
$env:ADMIN_TOKEN="verify-token"

npm run dev
```

另一个终端运行：

```powershell
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

当前契约脚本共 88 项，必须全部通过。`reset-db.mjs` 会删除整个 schema，因此只允许数据库名包含 `verify` 或 `test`，绝不能指向正式库。

## 13. 生产部署

构建：

```powershell
cd server
npm run build
```

部署内容是整个 `dist/` 目录树和 `package-lock.json`：

```powershell
npm install --omit=dev
node dist/main.js
```

部署检查：

1. PostgreSQL 必须先于 Node 服务启动。
2. 生产环境 `AUTH_SECRET` 至少 32 个字符。
3. `DATABASE_URL` 必须明确指向目标 PostgreSQL 数据库。
4. `PUBLIC_BASE_URL` 应为外部可访问的 HTTPS 地址。
5. 启动日志必须出现“数据库已就绪”和“桃桃音乐代理服务已启动”。
6. 执行 `GET /health` 确认服务可用。
7. 修改数据层后确认契约验证全绿。

## 14. 常见故障

### 服务启动时报 `DATABASE_URL` 错误

检查 `.env` 是否存在，并确认连接串以 `postgres://` 或 `postgresql://` 开头。

### NestJS 注入对象为 undefined

确认使用 `npm run build` 或 `npm run dev`，不要使用 esbuild、tsx 或不带装饰器元数据的构建方式。

### 图片创建返回 503/5032

数据库没有 `GPTIMAGE2` Key，或所有 Key 的 `quota` 都为 0。只查询 `id、channel、quota` 排查，不要把 Key 输出到终端日志或聊天记录。

### 图片轮询返回 404/4042

本地任务表不存在该 `task_id`，或者任务不是通过当前后端创建。轮询只接受已经持久化的任务。

### 图片接口返回 502/5021

检查 ApiSweet 服务、数据库中关联的 Key、Key 有效期、IP 白名单和上游余额。第三方 401/402 会有意转换为 502。

### 搜索返回空结果但没有明显报错

确认 `/search` 仍返回裸 NDJSON，且每行包含 `type`；不能给它套普通成功信封。

### 客户端无法自动续期

检查无效访问令牌是否仍返回 HTTP 401。若误变成 403，客户端不会触发续期重放。

## 15. 提交前检查

- 文档和代码注释使用简体中文。
- TypeScript 源码保持正常缩进和模块职责边界。
- 没有提交 `.env`、API Key、密码或机器专属配置。
- 新路由默认受访问令牌守卫保护，公开路由显式标记 `@Public()`。
- 未破坏 `RELEASE.md` 中列出的装机客户端契约。
- `npm run build` 通过。
- `node tools/verify-contract.mjs` 全绿。
