# 开发环境与工作流

[返回文档中心](README.md)

## 1. 环境要求

| 软件 | 要求 |
| --- | --- |
| Node.js | 20 或更高版本 |
| npm | 随 Node.js 安装 |
| PostgreSQL | 本机或独立开发实例 |
| PowerShell | 项目示例命令使用 PowerShell |

后端不依赖 Android SDK 或 JDK，但整个项目的 Android 构建要求 JDK 21。

## 2. 初始化

从项目根目录执行：

```powershell
cd server
npm install
Copy-Item .env.example .env
```

创建数据库：

```powershell
psql -U postgres -c "CREATE DATABASE music"
```

基础配置：

```env
PORT=4500
DATABASE_URL=postgres://postgres:密码@localhost:5432/music
AUTH_SECRET=change-me-to-a-random-string-at-least-32-chars
ADMIN_TOKEN=
APK_DIR=./data/apk
DEFAULT_CHANNEL=release
PUBLIC_BASE_URL=http://127.0.0.1:4500
APISWEET_BASE_URL=https://apisweet.com
```

`.env` 不提交。环境中已有的变量优先于 `.env`。

## 3. 配置项

| 变量 | 默认值 | 是否必需 | 说明 |
| --- | --- | --- | --- |
| `PORT` | `4500` | 否 | 监听端口 |
| `DATABASE_URL` | 无 | 是 | PostgreSQL 连接串 |
| `AUTH_SECRET` | 开发兜底值 | 生产必需 | 生产环境至少 32 字符 |
| `ADMIN_TOKEN` | 空 | 管理接口必需 | `X-Admin-Token` 的校验值 |
| `APK_DIR` | `./data/apk` | 否 | APK 文件目录 |
| `DEFAULT_CHANNEL` | `release` | 否 | 默认发布渠道 |
| `PUBLIC_BASE_URL` | 按请求推导 | 生产建议 | APK 和播放占位地址的外部基地址 |
| `SEARCH_CONCURRENCY` | `8` | 否 | 搜索相关并发上限 |
| `APISWEET_BASE_URL` | `https://apisweet.com` | 否 | 图片生成上游地址 |
| `ENV_FILE` | `.env` | 否 | 指定其它配置文件 |

ApiSweet API Key 存在 PostgreSQL `api_key` 表，不使用环境变量。

## 4. 常用命令

```powershell
# 开发模式，源码变更后自动重启
npm run dev

# 清理 dist、运行 tsc、复制生产 package.json
npm run build

# 运行 dist/main.js
npm start

# 对已启动的验证实例执行契约测试
npm run verify -- http://127.0.0.1:4720 verify-token
```

不能使用：

```text
tsx src/main.ts
esbuild src/main.ts
```

它们不会生成 NestJS 构造器注入需要的 `design:paramtypes`。

## 5. 数据库建表

应用启动时自动执行 `src/database/migrations.ts`：

1. 连接 PostgreSQL。
2. 开启事务。
3. 获取事务级顾问锁。
4. 执行 `CREATE TABLE/INDEX IF NOT EXISTS`。
5. 提交事务。

开发时新增表或索引，只修改 `migrations.ts`，不要手工维护另一套 SQL 文件。正式环境在服务重启后自动应用新增的幂等 DDL。

## 6. 新增模块

建议结构：

```text
src/example/
├─ dto/
│  └─ create-example.dto.ts
├─ example.controller.ts
├─ example.service.ts
├─ example.repository.ts
└─ example.module.ts
```

步骤：

1. 创建 Module、Controller 和必要的 Service/Repository。
2. 在 `AppModule.imports` 注册模块。
3. 默认依赖全局访问令牌守卫，不要重复实现鉴权。
4. 需要公开时添加 `@Public()`。
5. 需要独立限流时扩展 `RateLimitBucket` 和 `RateLimitService`。
6. 普通 JSON 返回领域数据，由 `EnvelopeInterceptor` 包装。
7. 流式或二进制响应添加 `@RawResponse()` 并自行结束响应。
8. 更新接口和架构文档。

## 7. DTO 与参数校验

全局 `ValidationPipe` 开启 `transform`。DTO 使用 `class-validator`：

```typescript
export class ExampleDto {
  @IsString()
  name: string;

  @IsOptional()
  @IsInt()
  count?: number;
}
```

认证模块刻意不用 DTO 处理部分输入，因为客户端对认证 4xx 有特殊行为。修改认证参数校验前先读源码注释和接口契约专题。

## 8. 错误处理

业务错误使用 `ApiErrors` 或 `ApiException`：

```typescript
throw ApiErrors.badRequest(4007, "参数不合法");
throw ApiErrors.notFound(4042, "任务不存在");
throw ApiErrors.upstream("上游服务不可用");
```

不要直接返回 `{ code, message }`，也不要在 Controller 中捕获所有异常后统一改成 200。

第三方错误处理原则：

- 用户输入错误可映射为 400。
- 第三方任务不存在可映射为 404。
- 第三方限流可映射为 429。
- 第三方 API Key、余额、网络或内部故障映射为 502/503。
- 第三方 401 不能透传为客户端 401。

## 9. 验证数据库

创建独立数据库：

```powershell
psql -U postgres -c "CREATE DATABASE music_verify"
```

每次验证前重置：

```powershell
node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
```

脚本会删除整个 `public` schema，只允许数据库名包含 `verify` 或 `test`。不要把正式连接串复制到该命令。

启动验证实例：

```powershell
$env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
$env:PORT="4720"
$env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"
$env:ADMIN_TOKEN="verify-token"
npm run dev
```

另一个终端执行：

```powershell
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

当前应为 88 项全绿。

## 10. 提交前流程

```powershell
cd server
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
git diff --check
git status --short
```

检查：

- 没有 `.env`、API Key、密码或测试 APK 被暂存。
- TypeScript 正常缩进，没有压缩源码。
- 数据层变化已经过独立 PostgreSQL 验证库验证。
- 文档与错误提示使用简体中文。
- 没有顺手提交工作区内其它模块的改动。
