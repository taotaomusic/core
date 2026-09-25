# 桃桃音乐后端文档中心

这里是桃桃音乐后端的专题文档目录。每篇文档只负责一个主题，修改功能时应同步更新对应专题，避免把所有内容继续堆回单个 README。

## 📚 文档导航

### 核心文档（必读）

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [00-code-index.md](00-code-index.md) | CodeGraph 基准、模块地图、完整 104 条路由、24 张表、配置与同步规则 | 先确认源码当前形状、查路由或判断文档应该改在哪里 |
| [01-architecture.md](01-architecture.md) | 模块结构、请求链路、全局守卫与拦截器、静态资源处理 | 第一次接触项目、准备新增模块、需要理解请求链路时 |
| [02-development.md](02-development.md) | 本地环境搭建、启动命令、热重载、调试、测试执行 | 搭建本地环境、运行、构建、调试和执行测试时 |
| [03-api-contracts.md](03-api-contracts.md) | 响应信封、错误码、不能破坏的客户端契约、NDJSON 流 | 新增或修改接口、与客户端联调、处理错误码时 |
| [04-database.md](04-database.md) | 表结构、PostgreSQL 特性、类型陷阱、并发控制、幂等性 | 修改表结构、Repository、SQL 或排查 PostgreSQL 问题时 |

### 专题文档（按需查阅）

| 文档 | 核心内容 | 适用场景 |
| --- | --- | --- |
| [05-image-generation.md](05-image-generation.md) | gpt-image-2 任务创建、Key 池管理、额度扣减、状态轮询 | 维护 gpt-image-2、Key 池、额度和图片任务时 |
| [06-release-deployment.md](06-release-deployment.md) | 后端构建与部署、APK 发布、灰度放量、热修复补丁、桌面发布入口 | 部署后端、发布 Android 或桌面版本、灰度或回滚时 |
| [07-troubleshooting.md](07-troubleshooting.md) | 启动失败、连接超时、401/502 排查、热更新失效诊断 | 服务启动失败、接口异常或线上行为不符合预期时 |
| [09-wukongim.md](09-wukongim.md) | 悟空 IM 接入、TCP/WebSocket 连接、凭据签发、频道管理 | 接入悟空 IM、配置端口、排查聊天连接或凭据问题时 |
| [10-desktop-release.md](10-desktop-release.md) | Windows 模块清单、内容寻址上传、Courgette/bsdiff 差分、灰度与最低版本 | 构建、发布或排查桌面端更新时 |
| [11-admin-auth.md](11-admin-auth.md) | 管理员账号、数据库会话、TOTP 2FA、角色权限、IP 白名单、审计与 LDAP/SSO | 维护管理后台登录、权限或对接企业目录时 |

歌单接口的字段与同步语义见 [03-api-contracts.md](03-api-contracts.md) 的“云端歌单”章节，
数据库表与并发顺序约束见 [04-database.md](04-database.md) 的 `playlists` 小节。需要确认“代码里到底有
哪些路由”时，以 [00-code-index.md](00-code-index.md) 的 CodeGraph 清单为入口。

## 🔗 上位文档

项目级文档，位于项目根目录：

| 文档 | 说明 | 关键内容 |
| --- | --- | --- |
| [后端 README](../README.md) | 后端服务总览 | 接口完整列表、启动命令、模块结构、配置项说明 |
| [项目 README](../../README.md) | 项目总览 | 完整播放链路、功能清单、反复踩过的坑 |
| [AGENTS.md](../../AGENTS.md) | 开发规范 | 代码风格、模块划分、测试、签名、提交规范 |
| [RELEASE.md](../../RELEASE.md) | 发布与热更新手册 | **版本号铁律、不能破坏的客户端契约、发布流程** |
| [HOT_UPDATE.md](../../HOT_UPDATE.md) | 热更新设计 | 热更新能力边界、设计动机、自愈机制 |

> **⚠️ 改后端接口前必读 [RELEASE.md](../../RELEASE.md)**：其中列出了 15+ 条不能破坏的客户端契约，违反会导致装机客户端功能静默失效。

## 📋 文档维护规则

1. **语言统一**：文档、代码注释、错误提示统一使用简体中文
2. **同步更新**：
   - 新模块：先更新架构和接口专题
   - 数据层变化：同时更新数据库专题
   - 客户端可感知的响应变化：必须写入接口契约专题
   - 新环境变量：同时更新 `.env.example`、后端 README 和开发专题
3. **安全第一**：不在文档中写入真实密码、API Key、数据库连接串或签名信息
4. **示例规范**：示例密钥只能使用明显的占位文本，例如 `替换为真实Key`
5. **可执行性**：文档中的命令应能从标注的工作目录直接执行
6. **加密同步**：后端消费的加密层（独立仓库 `hdppppppp/tools` 产出的 `.node`）协议格式或密钥规则变化时，同步更新该仓库 `README.md` 的协议章节与 `SECURITY.md`；本仓库的 `crypto/dist/` 只放产物，不放文档
7. **索引同步**：新增或删除路由、表、环境变量或模块后先刷新 CodeGraph，再更新 `00-code-index.md` 和对应专题；不要凭旧 README 猜测路由。

## 🚀 快速开始

### 本地开发（首次启动）

```powershell
# 1. 创建数据库（只需一次）
psql -U postgres -c "CREATE DATABASE music"

# 2. 安装依赖
cd server
npm install

# 3. 配置环境变量
copy .env.example .env
# 编辑 .env，至少填写以下必需项：
#   DATABASE_URL=postgres://postgres:密码@localhost:5432/music
#   AUTH_SECRET=至少32位随机值（必填，任何环境都不得留空或使用默认值）
#
# 管理后台 /admin/ 用的是数据库管理员账号：
# 设了 ADMIN_INITIAL_PASSWORD（至少12位）就用它建 admin 账号，否则启动时随机生成一个
# 并打印在日志里（只打印一次）。首次登录后会被强制改密。

# 4. 启动开发服务器（自动建表 + 热重载）
npm run dev
```

### 常用命令

```powershell
# 开发模式（ts-node + --watch）
npm run dev

# 生产构建（tsc + Terser + vite build）
npm run build

# 启动生产服务（需先构建）
cd dist && npm start
# 或
node dist/main.js

# 只构建管理后台
npm run build:frontend

# 管理后台独立开发服务器（Vite，端口 5173）
npm run dev:frontend

# 契约验证（以脚本实际输出为准，须全绿）
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token

# 重置验证数据库
node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
```

### 健康检查

```powershell
# 服务是否正常
curl.exe http://127.0.0.1:4500/health
# 预期响应：{"code":0,"message":"success","data":{"status":"up"}}
# 注意 /health 同样经过全局信封拦截器，status 的值是 up 而不是 ok。

# 管理后台
# 浏览器打开：http://localhost:4500/admin/
```

### 接口测试

接口统一前缀是 `/api/v1`，只有健康检查保留在 `/health`。

```powershell
# 公开接口示例（无需令牌）
curl.exe "http://127.0.0.1:4500/api/v1/app/bootstrap?versionCode=1&sdk=34&deviceId=test-device-001&channel=release"

# 需要登录的接口（带 Authorization）
curl.exe "http://127.0.0.1:4500/api/v1/auth/me" -H "Authorization: Bearer <访问令牌>"

# 管理接口（需要管理员会话；静态 X-Admin-Token 通道已移除）
curl.exe "http://127.0.0.1:4500/api/v1/app/admin/releases?channel=release" -H "Authorization: Bearer <管理员会话令牌>"

# 管理后台登录（返回 Bearer 会话令牌，之后用 Authorization: Bearer <token>）
curl.exe -X POST "http://127.0.0.1:4500/api/v1/admin/auth/login" `
  -H "Content-Type: application/json" `
  -d '{\"username\":\"admin\",\"password\":\"<管理员口令>\"}'

# 桌面端更新检查（公开）
curl.exe "http://127.0.0.1:4500/api/v1/desktop/bootstrap?channel=release&architecture=windows-x64&versionCode=1&deviceId=doc-check"
```

管理后台的账号、2FA、角色和 LDAP 细节见 [11-admin-auth.md](11-admin-auth.md)。

## 📖 推荐阅读路径

### 新后端开发者（首次接触项目）

1. **[架构文档](01-architecture.md)**
   先读 [代码索引](00-code-index.md) 确认模块和路由，再理解请求链路、全局守卫与拦截器的执行顺序。

2. **[开发环境](02-development.md)**
   启动本地 PostgreSQL、配置 `.env`、运行开发服务器，跑通健康检查和基础接口。

3. **[接口契约](03-api-contracts.md)**
   了解响应信封格式、错误码规则、NDJSON 流式响应、不能破坏的客户端契约。这份文档决定了你改接口时哪些是红线。

4. **[数据库](04-database.md)**
   开始写 Repository 前必读：SQL 别名加引号、bigint vs integer、`enabled` 是 0/1 不是 boolean、并发控制与幂等性。

### 维护图片生成功能

1. **[图片生成专题](05-image-generation.md)**
   理解 Key 池管理、任务状态轮询、额度扣减与归还、上游错误映射。

2. **[数据库](04-database.md)** 中的 `api_key` 和 `image_generation_task` 表结构
   掌握原子扣额 SQL、Key 选择逻辑、任务状态流转。

3. **[故障排查](07-troubleshooting.md)** 中的 404/502/503 分类
   按错误类型快速定位是上游问题、Key 问题还是网络问题。

### 发布与部署

1. **[发布与部署](06-release-deployment.md)**
   后端构建、APK 发布、灰度放量、热修复补丁的完整流程；桌面端细节见 [10-desktop-release.md](10-desktop-release.md)。

2. **[项目根目录 RELEASE.md](../../RELEASE.md)**
   版本号铁律、客户端契约、服务端契约、每个坑的真实案例。**推版本前必读。**

3. **[故障排查](07-troubleshooting.md)** 中的热更新失效诊断
   `bootstrap` 异常、APK 下载失败、补丁加载失败的排查步骤。

### 维护管理后台

1. **[管理后台认证](11-admin-auth.md)**
   管理员账号与会话、TOTP 2FA 的两步流程、角色权限、IP 白名单、审计日志和 LDAP/SSO 回落规则。

2. **[数据库](04-database.md)** 中的 `admin_users`、`admin_sessions`、`admin_audit_log`
   三张表的字段语义，以及审计外键为什么必须是 `ON DELETE SET NULL`。

3. **[故障排查](07-troubleshooting.md)** 中的管理后台登录排查
   登录恒 401、2FA 验证过期、强制改密 403/4031、账号退避 429/4291 的定位路径。

### 排查线上问题

直接跳到 **[故障排查文档](07-troubleshooting.md)**，按症状分类查找：
- 启动失败 → 数据库连接、环境变量、端口占用
- 接口 401/502 → 令牌失效、上游问题、内部异常映射
- 热更新失效 → `bootstrap` 契约、灰度分桶、APK 校验
- 搜索/播放异常 → 上游适配、音质降级、NDJSON 流
