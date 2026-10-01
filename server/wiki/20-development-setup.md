# 开发环境搭建与常用命令

[返回文档中心](README.md)

最后更新:2026-09-30

环境变量的完整清单与每个变量的校验行为见 [21-configuration.md](21-configuration.md);契约验证的完整流程单独成篇 [22-contract-verification.md](22-contract-verification.md)。

## 1. 环境要求

| 软件 | 要求 |
| --- | --- |
| Node.js | 20 或更高版本 |
| npm | 随 Node.js 安装 |
| PostgreSQL | 本机或独立开发实例 |
| PowerShell | 项目示例命令使用 PowerShell |

后端不依赖 Android SDK 或 JDK,但整个项目的 Android 构建要求 JDK 21。

传输加密的原生产物**不需要本地交叉编译环境**(Rust / Android NDK / wasm-bindgen 都不用),从 GitHub Release 拉取即可,见 [37-api-crypto.md](37-api-crypto.md) 的本地开发一节。

## 2. 首次初始化

从项目根目录执行:

```powershell
cd server
npm install
Copy-Item .env.example .env
```

> 仓库里的 `.env.example` 现在是 **docker-compose 部署模板**:`POSTGRES_USER` / `POSTGRES_PASSWORD` / `POSTGRES_DB` / `APP_PORT` 只被 `docker-compose.yml` 消费,后端代码不读它们。本地开发复制后按下面的最小配置补齐 `DATABASE_URL` 等应用变量;全部变量的语义见 [21-configuration.md](21-configuration.md)。

创建数据库:

```powershell
psql -U postgres -c "CREATE DATABASE music"
```

`.env` 的最小可启动配置:

```env
PORT=4500
DATABASE_URL=postgres://postgres:密码@localhost:5432/music
AUTH_SECRET=change-me-to-a-random-string-at-least-32-chars
ADMIN_INITIAL_PASSWORD=
APK_DIR=./data/apk
DEFAULT_CHANNEL=release
PUBLIC_BASE_URL=http://127.0.0.1:4500
APISWEET_BASE_URL=https://apisweet.com
SMTP_HOST=
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
SMTP_FROM=
IM_ENABLED=false
TOTP_ISSUER=桃桃音乐管理后台
```

可选段(传输加密 PSK、IM 完整配置、LDAP、TRUST_PROXY)见 [21-configuration.md](21-configuration.md)。

`.env` 不提交。环境中已有的系统变量优先于 `.env`。

## 3. 常用命令

```powershell
# 开发模式,源码变更后自动重启
npm run dev

# 完整生产构建(共 6 步,见 60-deploy-backend.md)
npm run build

# 运行 dist/main.js
npm start

# 对已启动的验证实例执行契约测试
npm run verify -- http://127.0.0.1:4720

# 酷我 KPK 原生签名向量与波点逐字歌词解析自检
npm run verify:kpk
npm run verify:lrcx

# TOTP 自实现的 RFC 向量验证
npm run verify:totp

# 单独构建管理后台和分享播放器
npm run build:frontend
npm run build:web-player
```

**不能使用** `tsx src/main.ts` 或 `esbuild src/main.ts` 启动:它们不会生成 NestJS 构造器注入需要的 `emitDecoratorMetadata`,启动即报注入为 undefined。

## 4. 数据库建表

应用启动时自动执行 `src/database/migrations.ts`(机制详见 [40-database-overview.md](40-database-overview.md)):

1. 连接 PostgreSQL。
2. 开启事务,取事务级顾问锁。
3. 执行建表、加列、约束和索引等幂等 DDL。
4. 提交事务。

开发时新增表或索引,只修改 `migrations.ts`,不要手工维护另一套 SQL 文件。正式环境在服务重启后自动应用新增的幂等 DDL。

## 5. 调试技巧

### 只验证类型

```powershell
npx tsc -p tsconfig.json --noEmit
```

正式交付仍要运行 `npm run build`,因为它还负责清理产物和复制生产清单。

### 检查路由是否注册

开发启动日志会输出每个 `Mapped {路径, 方法} route`。如果 Controller 已写但没有日志:

- 检查 Controller 是否加入 Module。
- 检查 Module 是否加入 `AppModule.imports`。
- 检查文件是否位于 `tsconfig.json` 的 include 范围。

### 检查环境变量覆盖

系统环境变量优先于 `.env`。修改 `.env` 不生效时,检查当前 PowerShell 是否已有同名 `$env:` 变量,并重启开发进程。

### 保持错误可复现

- 记录 HTTP 状态码、业务码、路径和不含敏感信息的请求参数。
- 不复制访问令牌、刷新令牌或 Key 到日志(日志红线见 [74-error-codes.md](74-error-codes.md))。
- 数据问题在验证库构造最小数据,不直接修改正式库复现。

### 管理后台开发代理

`npm run dev:frontend` 使用 Vite `5173`,`vite.config.ts` 将 `/api` 默认代理到后端 `http://localhost:4500`。如果后端使用其它端口,先设置 `VITE_API_PROXY_TARGET=http://localhost:<端口>`;生产构建后由 NestJS 在 `/admin/` 提供静态文件,不经过 Vite 代理。

### 管理后台登不进去

按顺序确认:

```powershell
# 1. 默认管理员是否建出来了(首次启动日志里应有一行「已创建默认管理员账号」)
#    随机口令的部署要在这行 WARN 里把口令抄下来,它只打印一次
# 2. 直接打登录接口,看返回的是 401 还是别的
curl.exe -i -X POST "http://127.0.0.1:4500/api/v1/admin/auth/login" `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"admin\",\"password\":\"<初始口令>\"}'
```

如果**所有**登录请求都是 401/4013,第一嫌疑是 `@UseGuards` 被挂到了控制器类上 —— 类级守卫连 `login` 一起拦,谁也进不去。详细排查见 [71-troubleshooting-auth.md](71-troubleshooting-auth.md)。
