# Windows 桌面模块化发布

[返回文档中心](README.md)

桌面发布由 `src/desktop-release/` 实现，与 Android `src/release/` 分开保存版本记录和最低支持版本。两者共享 `AdminAuthGuard`（只接受管理员会话 `Authorization: Bearer`）、灰度哈希和 `app_config`，但 **不能共用 versionCode 表或最低版本字段**。当前默认架构是 `windows-x64`。

桌面端消费的加密产物是 `crypto/dist/windows/taotao_crypto.dll`（JNI，随桌面应用打包），它属于客户端运行时，与本文描述的服务端 `/api/v1/desktop/*` 接口无关，也不影响本发布流程；服务端用的 Node 产物见 [06-release-deployment.md](06-release-deployment.md) 的部署文件一节。

## 1. 发布对象

一个桌面版本由一份发布记录和 1 至 512 个模块文件组成：

```json
{
  "channel": "release",
  "architecture": "windows-x64",
  "versionCode": 120,
  "versionName": "1.2.0",
  "entrypoint": "app/taotao.exe",
  "releaseNote": "修复播放列表同步",
  "files": [
    {
      "path": "app/taotao.exe",
      "category": "app",
      "size": 7340032,
      "sha256": "64 位小写十六进制",
      "url": "上传后由服务端生成"
    }
  ]
}
```

服务端校验（发布清单 JSON 请求体单独允许 1 MiB；普通 JSON 路由仍是 16 KiB）：

- `channel`、`architecture`、`versionName`、模块分类符合 ASCII 约束；架构默认 `windows-x64`。
- `versionCode` 为正整数；`files` 数量为 1–512。
- 每个 `path` 是安装目录相对路径，不能是绝对路径、不能包含 `..`、反斜杠或重复项。
- 单个模块 1–500 MiB，`sha256` 必须是 64 位小写十六进制；`entrypoint` 必须存在于 `files`。
- `category` 由清单决定，服务端不根据扩展名猜测。

上传内容必须先以 sha256 内容寻址文件存在，再发布清单。发布时如果某个模块尚未上传，整个请求失败，不会生成可见的半套版本。

## 2. 接口与鉴权

路径均需加 `/api/v1`，后台接口挂 `AdminAuthGuard`（**只接受管理员会话 `Authorization: Bearer <token>`**），
并受管理 IP 限流（60 次/15 分钟）。静态 `X-Admin-Token` 兼容通道已整体移除。

| 方法 | 路径 | 请求体/参数 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/desktop/bootstrap` | `channel`、`architecture`、`versionCode`、`deviceId` | 公开；返回更新描述、模块和可用差分 |
| `GET/HEAD` | `/desktop/artifacts/:sha256` | Range 可选 | 公开内容寻址模块，支持 200/206/416 |
| `GET/HEAD` | `/desktop/patches/:sha256` | Range 可选 | 公开内容寻址差分，支持 200/206/416 |
| `GET` | `/desktop/admin/releases` | `channel`、`architecture` | 管理端查看版本 |
| `POST` | `/desktop/admin/artifacts` | 原始字节，query `sha256` | 单文件上传，最多 500 MiB |
| `POST` | `/desktop/admin/releases` | 上述 JSON 清单 | 校验、落库、生成差分 |
| `POST` | `/desktop/admin/rollout` | `{channel,architecture,versionCode,percent,enabled?}` | 修改灰度或启用状态 |
| `POST` | `/desktop/admin/min-version` | `{channel,architecture,versionCode}` | 设置桌面最低支持版本 |

`/desktop/bootstrap` 会尝试解析可选的桃桃访问令牌：有效令牌按用户分桶，无令牌或无效令牌按 `deviceId` 分桶；两种情况都必须返回 200，不能因为旧客户端令牌过期而阻断更新通道。

## 3. 更新响应

普通成功由全局拦截器包装：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "update": {
      "available": true,
      "forced": false,
      "versionCode": 120,
      "versionName": "1.2.0",
      "entrypoint": "app/taotao.exe",
      "releaseNote": "修复播放列表同步",
      "totalSize": 7340032,
      "minSupportedVersionCode": 90,
      "files": [
        {
          "path": "app/taotao.exe",
          "category": "app",
          "size": 7340032,
          "sha256": "...",
          "url": "https://example.invalid/api/v1/desktop/artifacts/...",
          "patch": {
            "algorithm": "bsdiff",
            "fromSha256": "...",
            "size": 120000,
            "sha256": "...",
            "url": "https://example.invalid/api/v1/desktop/patches/..."
          }
        }
      ]
    },
    "config": {},
    "configVersion": 0
  }
}
```

只有目标版本 `rollout_percent = 100` 才能作为强制更新救援版本。灰度版本只在命中稳定哈希桶时返回 `available=true`；若客户端版本低于平台最低支持版本但没有可下载的全量救援版本，服务端不会伪造更新描述。

差分只在本地文件 sha256 与 `fromSha256` 完全匹配时可用。客户端应用失败必须回退到完整模块下载，不能把差分失败当作登录或服务器错误。

## 4. 推荐发布顺序

下面所有 `curl.exe` 示例都用 `$env:ADMIN_SESSION_TOKEN` 承载管理员会话。它的取法是先登录一次，把返回的 `data.token` 存进环境变量：

```powershell
$login = curl.exe -s -X POST "https://你的域名/api/v1/admin/auth/login" `
  -H "content-type: application/json" `
  -d '{"username":"admin","password":"<你的管理员口令>"}' | ConvertFrom-Json
$env:ADMIN_SESSION_TOKEN = $login.data.token   # 若账号开了 2FA，需再走一步 totp-verify
```

会话有效期 24 小时，过期后重新登录即可。**默认口令首次登录会被要求先改密码**（此时除 `/me` 与改密接口外一律 403/4031）。

### 4.1 计算清单

在桌面构建机生成每个模块的大小和 sha256，清单中只放相对路径：

```powershell
$root = "C:\build\taotao-desktop"
Get-ChildItem $root -Recurse -File | ForEach-Object {
  $relative = $_.FullName.Substring($root.Length + 1).Replace("\\", "/")
  $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLower()
  [pscustomobject]@{ path = $relative; size = $_.Length; sha256 = $hash }
}
```

不要把构建机绝对路径、临时目录或密钥写进发布 JSON。

### 4.2 上传模块

```powershell
$origin = "https://你的域名"
$sha = (Get-FileHash "C:\build\taotao-desktop\app\taotao.exe" -Algorithm SHA256).Hash.ToLower()

curl.exe -X POST "$origin/api/v1/desktop/admin/artifacts?sha256=$sha" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  --data-binary "@C:\build\taotao-desktop\app\taotao.exe"
```

服务端边写入边计算哈希；请求中的 sha256 与实际内容不一致返回 400/4006，空文件或参数错误返回 400/4005。相同哈希的重复上传可以直接复用已存在对象。

### 4.3 发布清单

```powershell
$manifest = Get-Content .\desktop-release.json -Raw
Invoke-RestMethod -Method Post `
  -Uri "$origin/api/v1/desktop/admin/releases" `
  -Headers @{ "Authorization" = "Bearer $env:ADMIN_SESSION_TOKEN" } `
  -ContentType "application/json" `
  -Body $manifest
```

新版本先写入 `desktop_release`（登记阶段暂不放量），再在一个事务中整体替换该版本的 `desktop_jar` 并清理旧差分；随后为上一版本同路径模块生成差分，最后才更新灰度和启用状态。差分大于目标完整模块 90% 时丢弃，清单只保留完整下载。

差分算法选择：

1. `.dll`、`.exe` 等 PE 文件且配置了 `COURGETTE_PATH`：先尝试 Courgette。
2. 其它文件或 Courgette 失败：使用内置 `bsdiff-wasm`。
3. 差分失败或不划算：记录警告并继续发布完整模块。

`BSDIFF_BIN` 虽然仍被类型化配置读取，但当前实现不执行外部 bsdiff 命令；不要把它当作安装依赖。

### 4.4 下载验收与灰度

```powershell
# 查看版本
curl.exe "$origin/api/v1/desktop/admin/releases?channel=release&architecture=windows-x64" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN"

# 先放 10%
curl.exe -X POST "$origin/api/v1/desktop/admin/rollout" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" -H "content-type: application/json" `
  -d '{"channel":"release","architecture":"windows-x64","versionCode":120,"percent":10,"enabled":true}'

# 确认稳定后放满 100%
curl.exe -X POST "$origin/api/v1/desktop/admin/rollout" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" -H "content-type: application/json" `
  -d '{"channel":"release","architecture":"windows-x64","versionCode":120,"percent":100}'
```

验收至少包括：

- `GET /desktop/bootstrap` 在无令牌、过期令牌和有效令牌下均为 200。
- 返回的每个完整模块 URL 都能下载，sha256 和 size 与清单一致。
- 有匹配旧模块 sha256 时才出现 patch；不匹配时只能出现完整下载。
- Range `bytes=0-99` 返回 206 和正确 `Content-Range`；越界返回 416；HEAD 不返回正文。
- 内容寻址响应带 `ETag`、`Accept-Ranges: bytes` 和不可变缓存头。

### 4.5 抬高最低版本

必须先存在同渠道、同架构、启用且 100% 放量的版本：

```powershell
curl.exe -X POST "$origin/api/v1/desktop/admin/min-version" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" -H "content-type: application/json" `
  -d '{"channel":"release","architecture":"windows-x64","versionCode":120}'
```

没有完整模块清单或没有 100% 版本时返回 409/4091。Android 与桌面最低版本分别存储，抬高一端不会改变另一端。

## 5. 存储与清理

`DESKTOP_RELEASE_DIR` 下的对象按 sha256 内容寻址；数据库的 `desktop_jar.artifact_file` 和 `desktop_patch.patch_file` 指向对象文件。发布删除或覆盖记录不会自动删除磁盘对象，因为旧客户端可能仍在下载。清理脚本必须先扫描：

1. `desktop_jar.artifact_file`、`desktop_patch.patch_file` 的引用；
2. 仍在灰度或最低版本范围内的发布；
3. 反向代理/CDN 缓存和正在进行的下载；
4. 再将未引用对象移入可恢复的隔离目录。

不要直接 `Remove-Item -Recurse` 删除整个发布目录。

## 6. 常见错误

| HTTP/业务码 | 含义 | 首先检查 |
| --- | --- | --- |
| 400/4005 | 版本、架构、路径、清单字段或 sha256 格式错误 | JSON 类型、路径遍历、大小边界 |
| 400/4006 | 实际文件 sha256/size 与声明不一致 | 上传文件是否被代理转码或截断 |
| 404/4041 | 桌面版本、模块或差分不存在 | channel、architecture、versionCode、sha256 |
| 404/4042 | 数据库有记录但本地对象缺失 | `DESKTOP_RELEASE_DIR` 权限和磁盘 |
| 409/4091 | 灰度/最低版本守卫失败 | 是否已有模块清单和 100% 全量版本 |
| 401/4013 | 管理员会话无效 | `Authorization: Bearer` 是否缺失、过期、已撤销 |
| 403/4031 | 会话有效但需先改密码 | 默认口令登录后未完成强制改密 |
| 403/4030 | 角色不足 | 当前账号是否为 `viewer` 只读角色 |
| 502/5020 | 发布记录或外部差分处理失败 | PostgreSQL、磁盘和差分工具日志 |
| 503/5032 | 桌面发布记录读写失败 | 数据库连接、迁移和事务日志 |

详细路由、全局守卫和数据库表见 [00-code-index.md](00-code-index.md)、[03-api-contracts.md](03-api-contracts.md) 和 [04-database.md](04-database.md)。
