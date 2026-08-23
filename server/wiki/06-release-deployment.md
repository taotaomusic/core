# 发布与部署

[返回文档中心](README.md)

本篇说明后端部署和 APK 发布的操作入口。版本号与热更新的最终规则以 [项目 RELEASE.md](../../RELEASE.md) 为准。

## 1. 后端生产构建

```powershell
cd server
npm install
npm run build
```

构建脚本会：

1. 清理 `dist/`。
2. 使用 `tsc -p tsconfig.json` 编译。
3. 生成生产使用的 `dist/package.json`。

产物是完整 `dist/` 目录树，不是单文件。

## 2. 部署文件

需要上传：

```text
dist/
package-lock.json
```

目标服务器在 `dist` 同级目录执行：

```powershell
npm install --omit=dev
node dist/main.js
```

不要上传开发 `.env`。生产 `.env` 在服务器单独维护。

## 3. 生产配置检查

| 配置 | 要求 |
| --- | --- |
| `DATABASE_URL` | 明确指向正式 PostgreSQL，不能使用验证库 |
| `AUTH_SECRET` | 至少 32 字符随机值 |
| `ADMIN_TOKEN` | 非空且妥善保管 |
| `APK_DIR` | 服务进程可写、磁盘空间充足 |
| `PUBLIC_BASE_URL` | 外部 HTTPS 地址 |
| `APISWEET_BASE_URL` | 默认 `https://apisweet.com` |

ApiSweet Key 在数据库，不在生产 `.env`。

## 4. 启动顺序

PostgreSQL 必须先于后端服务启动。systemd 建议：

```ini
[Unit]
After=network.target postgresql.service

[Service]
ExecStart=/usr/bin/node /path/to/server/dist/main.js
Restart=always
```

应用自身会进行 10 次数据库连接重试，超过后退出并交给进程管理器重启。

## 5. 部署后检查

```powershell
curl.exe https://你的域名/health
```

检查：

- HTTP 200。
- 响应 `code` 为 0。
- 日志出现“数据库已就绪”。
- 日志出现“桃桃音乐代理服务已启动”。
- 新增数据库表已由启动迁移创建。
- `/app/bootstrap` 使用无效令牌仍返回 200。

## 6. 后端发布前验证

必须先在独立验证库：

```powershell
npm run build
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

当前要求 88 项全部通过。数据层修改还要执行对应专项验证，例如 Key 并发扣额、失败退额和任务结果回写。

## 7. APK 构建

从项目根目录：

```powershell
.\gradlew.bat :androidApp:assembleRelease
```

产物：

```text
androidApp/build/outputs/apk/release/androidApp-release.apk
```

构建会在结束后递增 `version.properties`。刚生成 APK 的版本只能从以下文件读取：

```text
androidApp/build/outputs/apk/release/output-metadata.json
```

不能读取构建后的 `version.properties` 作为本包版本，也不能回滚该文件。

## 8. APK 登记

请求体是 APK 原始字节，不是 JSON、base64 或 multipart：

```powershell
$apk = "androidApp\build\outputs\apk\release\androidApp-release.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

curl.exe -X POST "https://你的域名/api/v1/app/admin/releases?versionCode=版本号&versionName=版本名&note=更新说明&rollout=0&sha256=$sha" `
  -H "X-Admin-Token: $env:ADMIN_TOKEN" `
  --data-binary "@$apk"
```

首次登记保持 `rollout=0`，先验证下载和安装。

## 9. 下载验证

```powershell
# Range 应返回 206
curl.exe -D - -o NUL -H "Range: bytes=100-199" "https://你的域名/api/v1/app/apk/版本号"

# 越界应返回 416
curl.exe -o NUL -w "%{http_code}`n" -H "Range: bytes=999999999-" "https://你的域名/api/v1/app/apk/版本号"
```

全量下载后再次核对 sha256 和文件大小。

## 10. 灰度与全量

```powershell
curl.exe -X POST "https://你的域名/api/v1/app/admin/rollout" `
  -H "content-type: application/json" `
  -H "X-Admin-Token: $env:ADMIN_TOKEN" `
  -d '{"versionCode":123,"percent":10}'
```

确认稳定后逐步提高至 100。只有 100% 放量版本才能进入 `X-Latest-Version-Code` 响应头。

## 11. 最低支持版本

抬高下限前必须已有版本号不低于目标、启用且放量 100% 的发布，否则接口返回 409/4091：

```powershell
curl.exe -X POST "https://你的域名/api/v1/app/admin/min-version" `
  -H "content-type: application/json" `
  -H "X-Admin-Token: $env:ADMIN_TOKEN" `
  -d '{"versionCode":123}'
```

顺序永远是先全量目标版本，再抬高下限。

## 12. 回滚

后端部署前备份现有 `dist/` 和生产依赖清单。出现以下任一情况立即回滚：

- `/health` 失败。
- `/app/bootstrap` 返回异常或 401。
- 搜索不再是裸 NDJSON。
- 登录令牌错误变成 403。
- APK Range 下载异常。
- 数据库迁移失败导致服务无法启动。

数据库 DDL 回滚要单独评估。不要在没有备份和兼容方案时删除列或表。

## 13. 反向代理要求

nginx 示例骨架：

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

注意：

- 不要在代理层把 `/search` 的 NDJSON 缓冲到完整响应后再发送。
- APK 上传请求体可能超过默认限制，需要配置匹配实际 APK 大小的 `client_max_body_size`。
- APK 和音频下载必须允许 Range 请求头和 206 响应。
- 应正确传递 `X-Forwarded-Proto`，否则服务在未配置 `PUBLIC_BASE_URL` 时可能生成 HTTP 地址。
- 外部必须使用 HTTPS，Android 默认会阻止明文资源。

## 14. systemd 示例

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

目录和用户仅为示例。确保运行用户可读 `.env`、可写 `APK_DIR`，但其他系统用户不能读取密钥配置。

## 15. 部署后冒烟测试

按顺序执行：

```powershell
$origin = "https://你的域名"

# 1. 健康
curl.exe -i "$origin/health"

# 2. 未登录门禁必须是 401，不是 403
curl.exe -i "$origin/api/v1/favorites"

# 3. bootstrap 必须公开
curl.exe -i "$origin/api/v1/app/bootstrap?versionCode=1&sdk=36&deviceId=deploy-check"

# 4. bootstrap 带假令牌仍不能 401
curl.exe -i "$origin/api/v1/app/bootstrap?versionCode=1&sdk=36&deviceId=deploy-check" `
  -H "Authorization: Bearer expired.token"
```

涉及真实账号、图片生成和发布管理的冒烟测试应使用专用测试账号，并避免在命令历史里写入令牌或 Key。

## 16. 数据库发布注意事项

- 先备份正式数据库。
- 新版本首次启动会在监听端口前执行 DDL。
- 多实例同时启动依赖顾问锁串行建表。
- 新增表和索引通常可直接上线；破坏性结构变化必须分阶段。
- 回滚旧代码前确认旧代码能忽略新表和新列。
- 不要在部署脚本里调用 `reset-db.mjs`。
