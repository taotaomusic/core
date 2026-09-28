# 后端构建与部署

[返回文档中心](README.md)

最后更新:2026-09-27

本篇是后端上服务器的操作手册。版本号与热更新的最终规则以 [项目 RELEASE.md](../../RELEASE.md) 为准;APK 发布见 [61-release-android.md](61-release-android.md),云端构建见 [62-ci-cloud-build.md](62-ci-cloud-build.md)。

## 1. 后端生产构建

```powershell
cd server
npm install
npm run build
```

构建脚本(`package.json` 的 `build`)依次执行 **6 步**:

1. `rimraf --glob dist/*` —— 清理 `dist/`。
2. `tsc -p tsconfig.json` —— TypeScript 编译。
3. `npm run minify:server` —— `tools/minify-server.mjs` 用 Terser 压缩 `dist/` 内的 JS。
4. `node tools/copy-manifest.mjs` —— 生成生产使用的 `dist/package.json`。
5. `npm run build:frontend` —— Vite 构建管理后台到 `dist/public`(`main.ts` 要在 `/admin` 提供该目录,**漏掉这一步会导致管理后台 404**)。
6. `npm run build:web-player` —— `tools/build-web-player.mjs` 构建 Kotlin/Wasm 分享播放器。

按 `npm run build` 执行即可完整走完 6 步;手工复刻时不要只做前几步(历史上漏过管理后台与分享播放器两步)。

产物是完整 `dist/` 目录树,不是单文件。**构建必须用 `tsc`,不能用 esbuild 或 tsx** —— 它们不支持 `emitDecoratorMetadata`,NestJS 构造器注入会失效。

## 2. 部署文件

需要上传:

```text
dist/
package-lock.json
```

另外要**拉取加密产物**:从 tools 仓库的 Release 取 `crypto/dist/node/<linux-x64|windows-x64>/taotao_crypto.node`,按运行平台放到服务器上(loader 从 `cwd/../crypto/dist` 或 `cwd/crypto/dist` 查找,见 `server/src/crypto/native-loader.ts`)。产物缺失只会打一条 WARN 并把传输加密降级为明文,**不会阻断启动**;需要加密链路时必须就位。

目标服务器在 `dist` 同级目录执行:

```powershell
npm install --omit=dev
node dist/main.js
```

不要上传开发 `.env`。生产 `.env` 在服务器单独维护。

## 3. 生产配置检查

| 配置 | 要求 |
| --- | --- |
| `DATABASE_URL` | 明确指向正式 PostgreSQL,不能使用验证库 |
| `AUTH_SECRET` | 至少 32 字符随机值。**没有开发兜底值**,缺失或过短会直接拒绝启动;轮换会使全部访问令牌失效 |
| `CRYPTO_PSK_ID` / `CRYPTO_PSK_HEX` | **生产必配**:传输加密 PSK 的标识与 32 字节 hex 原始密钥,二者缺一则握手全部失败、链路保持明文 |
| `CORS_ALLOWED_ORIGINS` | 只在确有第三方网页要跨域读接口时才配置;默认留空即不下发任何 CORS 头 |
| `ADMIN_INITIAL_PASSWORD` | 建议显式设置且足够强(至少 12 字符);留空则随机生成并只在首次启动日志里打印一次。两种来源都强制首登改密 |
| `TOTP_ISSUER` | 可选;管理员 2FA 在验证器里显示的名称 |
| `TRUST_PROXY` | 只在可信反向代理之后设为 `1`;直接暴露公网时必须留空,否则 IP 白名单可被伪造 |
| `LDAP_*` | 可选;对接企业目录时配置,`LDAP_TLS_REJECT_UNAUTHORIZED` 保持默认 `true` |
| `APK_DIR` | 服务进程可写、磁盘空间充足 |
| `DESKTOP_RELEASE_DIR` | 桌面模块和差分对象目录可写、磁盘空间充足 |
| `COURGETTE_PATH` | 可选;PE 模块差分工具 |
| `PUBLIC_BASE_URL` | 外部 HTTPS 地址 |
| `APISWEET_BASE_URL` | 默认 `https://apisweet.com` |
| `SMTP_HOST/PORT/USER/PASSWORD/FROM` | 注册、绑定、换绑验证码发信配置必须完整 |
| `IM_ENABLED` | 不启用 IM 时保持 `false` |
| `IM_INTERNAL_API_BASE_URL` | IM 启用时指向悟空 IM HTTP API(默认 5001,仅内网) |
| `IM_EXTERNAL_GATEWAY_URL` | IM 启用时必须是 `tcp://` Gateway 地址 |

完整变量说明与默认值见 [21-configuration.md](21-configuration.md)。ApiSweet Key 在数据库,不在生产 `.env`。

**默认管理员不再有硬编码口令,且首次登录被强制改密。** 服务第一次启动会创建 `admin`(`super_admin`):口令取 `ADMIN_INITIAL_PASSWORD`,未设置时随机生成并在日志里以 `WARN` 打印**一次**。该账号带 `must_change_password` 标记,改密前除 `me` 与 `change-password` 外所有管理接口一律 403/4031,前端会直接进入全屏改密页。

上线检查清单:

1. 启动日志里确认「已创建默认管理员账号」;用随机口令的部署要在这行 WARN 里把口令抄下来。
2. 登录 `/admin/`,按提示完成首次改密。
3. 立即给该账号开启 2FA,并按需配置 IP 白名单。

管理后台的账号体系见 [80-admin-auth-login.md](80-admin-auth-login.md)。

## 4. 启动顺序与 systemd

PostgreSQL 必须先于后端服务启动。systemd 示例:

```ini
[Unit]
Description=Taotao Music Backend
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/taotao/server
EnvironmentFile=/opt/taotao/server/.env
ExecStart=/usr/bin/node /opt/taotao/server/dist/main.js
Restart=always
RestartSec=3
User=taotao
Group=taotao

[Install]
WantedBy=multi-user.target
```

目录和用户仅为示例。确保运行用户可读 `.env`、可写 `APK_DIR`/`DESKTOP_RELEASE_DIR`,但其他系统用户不能读取密钥配置。应用自身会进行 10 次数据库连接重试,超过后退出并交给进程管理器重启(`Restart=always`)。

## 5. 反向代理要求

nginx 示例骨架:

```nginx
location /api/ {
    proxy_pass http://127.0.0.1:4500;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;

    # 搜索由应用通过 X-Accel-Buffering: no 单独关闭缓冲。
    proxy_read_timeout 120s;
}

location = /health {
    proxy_pass http://127.0.0.1:4500/health;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

注意:

- 不要在代理层把 `/search` 的 NDJSON 缓冲到完整响应后再发送。
- APK 上传请求体可能超过默认限制,需要配置匹配实际 APK 大小的 `client_max_body_size`;桌面上传单模块最大 500 MiB。
- APK 和音频下载必须允许 Range 请求头和 206 响应。
- 应正确传递 `X-Forwarded-Proto`,否则服务在未配置 `PUBLIC_BASE_URL` 时可能生成 HTTP 地址。
- 外部必须使用 HTTPS,Android 默认会阻止明文资源。
- 反向代理要**覆写**而不是追加 `X-Forwarded-For` 时再考虑 `TRUST_PROXY`(见 [81-admin-roles-audit.md](81-admin-roles-audit.md))。

## 6. 部署后检查

```powershell
curl.exe https://你的域名/health
```

检查:

- HTTP 200,响应 `code` 为 0(`{"code":0,"message":"success","data":{"status":"up"}}`;`/health` 同样经过全局信封拦截器,`status` 的值是 `up` 不是 `ok`)。
- 日志出现「数据库已就绪」和「桃桃音乐代理服务已启动」。
- 新增数据库表已由启动迁移创建。
- `/app/bootstrap` 使用无效令牌仍返回 200。

### 冒烟测试

按顺序执行:

```powershell
$origin = "https://你的域名"

# 1. 健康
curl.exe -i "$origin/health"

# 2. 未登录门禁必须是 401,不是 403
curl.exe -i "$origin/api/v1/favorites"

# 3. bootstrap 必须公开
curl.exe -i "$origin/api/v1/app/bootstrap?versionCode=1&sdk=36&deviceId=deploy-check"

# 4. bootstrap 带假令牌仍不能 401
curl.exe -i "$origin/api/v1/app/bootstrap?versionCode=1&sdk=36&deviceId=deploy-check" `
  -H "Authorization: Bearer expired.token"

# 5. 未配置加密时握手应返回 503/5031(证明明文链路健在、加密层按预期降级)
curl.exe -i -X POST "$origin/api/v1/crypto/handshake" -H "content-type: application/json" -d "{}"
```

涉及真实账号、图片生成和发布管理的冒烟测试应使用专用测试账号,并避免在命令历史里写入令牌或 Key。

## 7. 发布前验证

必须先在独立验证库(完整流程见 [22-contract-verification.md](22-contract-verification.md)):

```powershell
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720
```

以验证脚本的实际汇总数量为准,必须全部通过。数据层修改还要执行对应专项验证,例如 Key 并发扣额、失败退额和任务结果回写。

## 8. 数据库发布注意事项

- 先备份正式数据库。
- 新版本首次启动会在监听端口前执行 DDL。
- 多实例同时启动依赖顾问锁串行建表。
- 新增表和索引通常可直接上线;破坏性结构变化必须分阶段(见 [40-database-overview.md](40-database-overview.md))。
- 回滚旧代码前确认旧代码能忽略新表和新列。
- 不要在部署脚本里调用 `reset-db.mjs`。

## 9. 回滚

后端部署前备份现有 `dist/` 和生产依赖清单。出现以下任一情况立即回滚:

- `/health` 失败。
- `/app/bootstrap` 返回异常或 401。
- 搜索不再是裸 NDJSON。
- 登录令牌错误变成 403。
- APK Range 下载异常。
- 数据库迁移失败导致服务无法启动。

数据库 DDL 回滚要单独评估。不要在没有备份和兼容方案时删除列或表。

## 10. IM 上线检查

IM 启用前验证(接入细节见 [51-feature-wukongim.md](51-feature-wukongim.md)):

- `IM_INTERNAL_API_BASE_URL` 能从 NestJS 主机访问悟空 IM `/health`,且不是公网暴露的 5001。
- `IM_EXTERNAL_GATEWAY_URL` 是 `tcp://`,客户端能连 5100/TCP;不能用 HTTP 客户端探测 5100。
- 用失效 Token CONNECT 必须被 Gateway 拒绝;仅 `/user/token` 返回成功不足以证明 Gateway 开启鉴权。
- `POST /api/v1/im/session`、会话同步、撤回、已读和退出登录均通过验证账号实测。

IM 消息正文不进入 PostgreSQL;日志不得记录 IM API Token、设备 Token 或消息体。
