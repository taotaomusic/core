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
ADMIN_INITIAL_PASSWORD=
APK_DIR=./data/apk
DESKTOP_RELEASE_DIR=./data/desktop
COURGETTE_PATH=
DEFAULT_CHANNEL=release
PUBLIC_BASE_URL=http://127.0.0.1:4500
APISWEET_BASE_URL=https://apisweet.com
LSKY_UPLOAD_URL=https://img.kiwiyyds.cn/api/index.php
LSKY_API_KEY=
# 可选：传输加密 PSK。缺任一项握手全部 503/5031，链路保持明文（本地开发可不配）。
# CRYPTO_PSK_ID=dev-psk
# CRYPTO_PSK_HEX=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
SMTP_HOST=
SMTP_PORT=587
SMTP_USER=
SMTP_PASSWORD=
SMTP_FROM=
IM_ENABLED=false
IM_INTERNAL_API_BASE_URL=http://127.0.0.1:5001
IM_EXTERNAL_GATEWAY_URL=tcp://im.xydaigua.cn:5100
IM_API_TOKEN=
IM_SESSION_LIFETIME_SECONDS=900
TOTP_ISSUER=桃桃音乐管理后台
# 可选：LDAP/SSO。不配置则只用本地管理员账号。
# LDAP_URL=ldap://ldap.example.com:389
# LDAP_BIND_DN=cn=admin,dc=example,dc=com
# LDAP_BIND_PASSWORD=
# LDAP_USER_SEARCH_BASE=ou=users,dc=example,dc=com
# LDAP_TLS_REJECT_UNAUTHORIZED=true
# LDAP_TIMEOUT_MS=10000
# 只在可信反向代理之后才打开，否则 IP 白名单可被伪造请求头绕过。
# TRUST_PROXY=1
```

`.env` 不提交。环境中已有的变量优先于 `.env`。

## 3. 配置项

| 变量 | 默认值 | 是否必需 | 说明 |
| --- | --- | --- | --- |
| `PORT` | `4500` | 否 | 监听端口 |
| `DATABASE_URL` | 无 | 是 | PostgreSQL 连接串 |
| `AUTH_SECRET` | 无 | **是** | 至少 32 字符。没有开发兜底值，缺失或过短直接启动失败 |
| `ADMIN_INITIAL_PASSWORD` | 空 | 否 | 默认超管初始口令，至少 12 字符；留空则随机生成并只打印一次。两种来源都强制首登改密 |
| `CORS_ALLOWED_ORIGINS` | 空 | 否 | 跨域来源白名单；留空不下发任何 CORS 头 |
| `APK_DIR` | `./data/apk` | 否 | Android APK 和补丁文件目录 |
| `DESKTOP_RELEASE_DIR` | `./data/desktop` | 否 | Windows 内容寻址模块和差分目录 |
| `COURGETTE_PATH` | 空 | 否 | PE 文件差分工具；未配置时使用 bsdiff-wasm |
| `DEFAULT_CHANNEL` | `release` | 否 | 默认发布渠道 |
| `PUBLIC_BASE_URL` | 按请求推导 | 生产建议 | APK 和播放占位地址的外部基地址 |
| `SEARCH_CONCURRENCY` | `8` | 否 | 类型化配置仍保留；当前搜索实现不读取该字段 |
| `APISWEET_BASE_URL` | `https://apisweet.com` | 否 | 图片生成上游地址 |
| `LSKY_UPLOAD_URL` | `https://img.kiwiyyds.cn/api/index.php` | 否 | 头像图床地址 |
| `LSKY_API_KEY` | 空 | 头像上传必需 | 只在服务端使用 |
| `LSKY_PUBLIC_HOSTS` | 空 | 否 | 头像图床公网域白名单；上传返回的 URL 必须落在白名单内才入库 |
| `CRYPTO_PSK_ID` / `CRYPTO_PSK_HEX` | 空 | 否 | 传输加密 PSK 标识与 32 字节 hex 密钥；缺任一项握手全 503/5031、链路明文 |
| `ADMIN_RATE_LIMIT` | `60` | 否 | 管理端 `admin` 限流桶每 15 分钟次数；仅供契约验证调大，生产勿设 |
| `BODIAN_DEVICE_ID` | `md5("taotao-music-server")` | 否 | 波点客户端设备号覆盖项 |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USER` / `SMTP_PASSWORD` / `SMTP_FROM` | 空/587 | 发码时必需 | 注册、绑定和换绑邮箱验证码 |
| `IM_ENABLED` | `false` | 否 | 是否启用悟空 IM |
| `IM_INTERNAL_API_BASE_URL` | `http://127.0.0.1:5001` | IM 启用时必需 | 悟空 IM 产品 HTTP API |
| `IM_EXTERNAL_GATEWAY_URL` | `tcp://im.xydaigua.cn:5100` | IM 启用时必需 | 下发给客户端的原生 TCP 地址 |
| `IM_API_TOKEN` | 空 | 否 | 悟空 IM 服务端 API Token |
| `IM_SESSION_LIFETIME_SECONDS` | `900` | IM 启用时 60–86400 | 会话凭据续签周期 |
| `EMAIL_VERIFICATION_TEST_CODE` | 空 | 仅 `NODE_ENV=test` | 契约验证固定验证码 |
| `TOTP_ISSUER` | `桃桃音乐管理后台` | 否 | 管理员 2FA 在验证器里显示的名称 |
| `LDAP_URL` / `LDAP_BIND_DN` / `LDAP_BIND_PASSWORD` / `LDAP_USER_SEARCH_BASE` | 空 | 否 | LDAP 对接最小必填组，缺任一项即视为未配置 |
| `LDAP_USER_SEARCH_FILTER` | `(uid={{username}})` | 否 | 用户搜索过滤器，`{{username}}` 会被转义后替换 |
| `LDAP_GROUP_SEARCH_BASE` / `LDAP_GROUP_SEARCH_FILTER` | 空 | 否 | 组搜索；过滤器可用 `{{userDn}}` |
| `LDAP_ROLE_MAPPING` | 空 | 否 | JSON 对象，LDAP 组 DN → 角色；写坏了只告警不崩 |
| `LDAP_TLS_REJECT_UNAUTHORIZED` | `true` | 否 | 只有显式 `false`/`0`/`no`/`off` 才关闭 LDAPS 证书校验 |
| `LDAP_TIMEOUT_MS` | `10000` | 否 | 单次 LDAP 操作超时，连接和搜索都受它约束 |
| `TRUST_PROXY` | 关闭 | 否 | 只有 `1`/`true` 才采信 `X-Forwarded-For` |
| `ENV_FILE` | `.env` | 否 | 指定其它配置文件 |

ApiSweet API Key 存在 PostgreSQL `api_key` 表，不使用环境变量。

`BSDIFF_BIN` 虽然仍由 `AppConfigService` 读取以兼容旧配置，但当前桌面差分代码使用内置 `bsdiff-wasm`，不需要在开发机安装外部 bsdiff。

## 4. 常用命令

```powershell
# 开发模式，源码变更后自动重启
npm run dev

# 完整生产构建（共 6 步，见 06-release-deployment.md）
npm run build

# 运行 dist/main.js
npm start

# 对已启动的验证实例执行契约测试
npm run verify -- http://127.0.0.1:4720

# 酷我 KPK 原生签名向量与波点逐字歌词解析自检
npm run verify:kpk
npm run verify:lrcx

# 单独构建管理后台和分享播放器
npm run build:frontend
npm run build:web-player
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
4. 执行建表、加列、约束和索引等幂等 DDL。
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
4. 需要管理员会话鉴权时用 `@UseGuards(AdminAuthGuard, RolesGuard)`，并且**必须在
   `imports` 里加 `AdminAuthModule`**，否则启动报 `UnknownDependenciesException`。控制器里既有
   公开又有受保护方法时，守卫只能挂方法，不能提到类上。
5. 需要公开时添加 `@Public()`。
6. 需要独立限流时扩展 `RateLimitBucket` 和 `RateLimitService`。
7. 普通 JSON 返回领域数据，由 `EnvelopeInterceptor` 包装。
8. 流式或二进制响应添加 `@RawResponse()` 并自行结束响应。
9. 更新接口和架构文档。

新增桌面发布或 IM 路由时，还要更新 [00-code-index.md](00-code-index.md) 的路由计数和配置索引。

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
$env:ADMIN_INITIAL_PASSWORD="verify-initial-123456"
$env:NODE_ENV="test"; $env:EMAIL_VERIFICATION_TEST_CODE="123456"
$env:IM_ENABLED="false"; $env:CORS_ALLOWED_ORIGINS="https://verify.example"
$env:ADMIN_RATE_LIMIT="1000"   # 不调大会在验证中途吃 4290 假失败
npm run build:frontend     # /admin 下的 CSP 与主题脚本断言需要 dist/public
npm run build:web-player   # 分享页缓存头断言需要 dist/share-player
npm run dev
```

`IM_ENABLED` 必须显式设为 `false`（`.env` 里通常是 `true`），否则「未启用 IM 时会话入口返回
503/5031」会变成 502/5020；`CORS_ALLOWED_ORIGINS` 要包含 `https://verify.example`，
否则跨域白名单那条断言会失败。

运行前还要清掉 `data/desktop`：内容寻址存储在命中已有对象时会走
「删除临时文件」的分支，上一轮留下的对象会让上传路径和首次运行时不同。

另一个终端执行：

```powershell
node tools/verify-contract.mjs http://127.0.0.1:4720
```

以验证脚本的实际汇总数量为准，必须全部通过。

验证实例首次启动时会自动创建默认管理员 `admin`（`super_admin`），口令取自 `ADMIN_INITIAL_PASSWORD`
（未设置则随机生成并只打印一次），并且带「首次登录必须改密」标记 —— 契约脚本的第一段就是
用这个口令完成首登、改密、再用新口令重登，从而拿到后续断言要用的管理会话。

注意验证库每次被 `reset-db.mjs` 清空后都要**重启服务**，让 `AdminBootstrapService` 重新创建
这个账号，否则相关断言会因为登不进去而失败。脚本读的是**脚本自己环境**里的
`ADMIN_INITIAL_PASSWORD`，必须与服务启动时给的值一致。

## 10. 提交前流程

```powershell
cd server
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720
git diff --check
git status --short
```

检查：

- 没有 `.env`、API Key、密码或测试 APK 被暂存。
- TypeScript 正常缩进，没有压缩源码。
- 数据层变化已经过独立 PostgreSQL 验证库验证。
- 文档与错误提示使用简体中文。
- 没有顺手提交工作区内其它模块的改动。

## 11. 本地完整联调示例

### 注册并取得访问令牌

```powershell
$base = "http://127.0.0.1:4500/api/v1"
$register = Invoke-RestMethod -Method Post `
  -Uri "$base/auth/register" `
  -ContentType "application/json" `
  -Body '{"username":"local_dev","password":"pass123456"}'
$token = $register.data.accessToken
```

重复执行时用户名会冲突，可以改用户名或调用登录接口：

```powershell
$login = Invoke-RestMethod -Method Post `
  -Uri "$base/auth/login" `
  -ContentType "application/json" `
  -Body '{"username":"local_dev","password":"pass123456"}'
$token = $login.data.accessToken
```

### 调用鉴权接口

```powershell
$headers = @{ Authorization = "Bearer $token" }
Invoke-RestMethod -Uri "$base/auth/me" -Headers $headers
```

### 验证搜索是裸 NDJSON

```powershell
curl.exe "$base/search?keyword=周杰伦&num=3" `
  -H "Authorization: Bearer $token"
```

输出应该是一行一个 JSON 对象，而不是最外层 `{code,data}`。

### 创建并轮询图片任务

先按 [图片生成专题](05-image-generation.md) 向验证库添加测试 Key，再执行：

```powershell
$created = Invoke-RestMethod -Method Post `
  -Uri "$base/draw/completions" `
  -Headers $headers `
  -ContentType "application/json" `
  -Body '{"model":"gpt-image-2","prompt":"测试图片","aspectRatio":"1:1","imageSize":"1K","quality":"high"}'

$taskId = $created.data.taskId
Invoke-RestMethod -Uri "$base/draw/result/$taskId" -Headers $headers
```

真实 ApiSweet 请求会产生费用。本地开发优先使用假上游，并通过 `APISWEET_BASE_URL` 指向本地 HTTP 服务。

## 12. 调试建议

### 只验证类型

```powershell
npx tsc -p tsconfig.json --noEmit
```

正式交付仍要运行 `npm run build`，因为它还负责清理产物和复制生产清单。

### 检查路由是否注册

开发启动日志会输出每个 `Mapped {路径, 方法} route`。如果 Controller 已写但没有日志：

- 检查 Controller 是否加入 Module。
- 检查 Module 是否加入 `AppModule.imports`。
- 检查文件是否位于 `tsconfig.json` 的 include 范围。

### 检查环境变量覆盖

系统环境变量优先于 `.env`。修改 `.env` 不生效时，检查当前 PowerShell 是否已有同名 `$env:` 变量，并重启开发进程。

### 保持错误可复现

- 记录 HTTP 状态码、业务码、路径和不含敏感信息的请求参数。
- 不复制访问令牌、刷新令牌或 Key 到日志。
- 数据问题在验证库构造最小数据，不直接修改正式库复现。

### 管理后台开发代理

`npm run dev:frontend` 使用 Vite `5173`，`vite.config.ts` 将 `/api` 默认代理到后端
`http://localhost:4500`。如果后端使用其它端口，先设置 `VITE_API_PROXY_TARGET=http://localhost:<端口>`；
生产构建后由 NestJS 在 `/admin/` 提供静态文件，不经过 Vite 代理。

### 管理后台登不进去

按顺序确认：

```powershell
# 1. 默认管理员是否建出来了（首次启动日志里应有一行「已创建默认管理员账号」）
#    随机口令的部署要在这行 WARN 里把口令抄下来，它只打印一次
# 2. 直接打登录接口，看返回的是 401 还是别的
curl.exe -i -X POST "http://127.0.0.1:4500/api/v1/admin/auth/login" `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"admin\",\"password\":\"<初始口令>\"}'
```

如果**所有**登录请求都是 401/4013，第一嫌疑是 `@UseGuards` 被挂到了控制器类上 —— 类级守卫
连 `login` 一起拦，谁也进不去。详细排查见 [07-troubleshooting.md](07-troubleshooting.md)。

### 传输加密本地开发

本机**不需要** Rust / Android NDK / wasm-bindgen 交叉编译环境。在项目根目录执行
`powershell tools/fetch-crypto.ps1`，从 GitHub `hdppppppp/tools` 仓库的 Release 拉取对应平台
产物到 `crypto/dist/node/<platform>/taotao_crypto.node`（带 SHA256 校验）。产物缺失时服务只是
启动 WARN 并按明文链路运行，不阻断启动。

要在本地验证加密链路，配置 `CRYPTO_PSK_ID` / `CRYPTO_PSK_HEX`（两值需与客户端侧一致），
然后 `POST /api/v1/crypto/handshake` 应返回 `serverHello`；未配置时该接口固定 503/5031，
可用于证明明文链路健在。改加密协议去 `crypto-src/` 改（经 `tools/sync-repos.ps1` 推到 tools
仓库交叉编译），不要动 `crypto/dist/` 里的产物。
