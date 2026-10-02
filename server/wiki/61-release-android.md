# Android 发布与热更新契约

[返回文档中心](README.md)

最后更新:2026-10-02

本篇是 Android 版本发布的服务端操作:登记、验证、灰度、抬高下限,以及 `/app/bootstrap` 必须守住的热更新契约。版本号铁律以 [项目 RELEASE.md](../../RELEASE.md) 为准;热更新设计动机见 [HOT_UPDATE.md](../../HOT_UPDATE.md);排障见 [73-troubleshooting-release.md](73-troubleshooting-release.md)。

## 1. 热更新契约(`/app/bootstrap`)

`/app/bootstrap` **免鉴权且永不返回 401**。它同时返回:可用更新、最低支持版本、远程配置和配置版本号。

APK 下载(`/app/apk/{versionCode}`)必须支持:

- 全量 200。
- Range 206(带 `Content-Range`)。
- 越界 416。
- `ETag` 为 APK sha256。
- `apkSize` 与文件字节数完全一致。

补丁下载(`/app/patch/{targetVersionCode}/{patchVersion}`)同样要求 Range/ETag;补丁只对指定宿主版本有效,不能当成「高版本 APK」处理(表见 [42-database-tables-release.md](42-database-tables-release.md))。

`X-Latest-Version-Code` 响应头只有 `rollout_percent = 100` 且启用的最高版本才会进入。

## 2. APK 构建

### 云端构建(默认路径)

APK 由单仓 taotaomusic/core 根 CI 的 `client-android` job 构建(**Windows** runner,`incrementVersion` 依赖 PowerShell),流程见 [62-ci-cloud-build.md](62-ci-cloud-build.md):

- 签名 Secrets 配置在 **taotaomusic/core** 仓库:`ANDROID_KEYSTORE_BASE64`(keystore 的 base64,名称沿用)+ `TAOTAO_STORE_PASSWORD` / `TAOTAO_KEY_ALIAS` / `TAOTAO_KEY_PASSWORD`(2026-10-02 起 Secret 名对齐为 `TAOTAO_*`,与本地 `local.properties` 的变量名一致;workflow 内 env 变量名仍是 `ANDROID_*`,不受影响)。job 把 keystore 还原为根目录 `taotao-release.jks` 并写入 `local.properties` 后跑 `:androidApp:assembleRelease`。
- **未配置 `ANDROID_KEYSTORE_BASE64` 时自动改走 `:androidApp:assembleDebug`**,产 Debug 包(文件名 `TaotaoMusic-<版本>-debug.apk`),仅验证工具链;两种情况都会发布到 core 的滚动 Release `latest`。
- 登记发布时版本号取自构建产物 `output-metadata.json`(CI 也用它判定 release/debug),不能读 `version.properties`。

### 本地构建

从项目根目录:

```powershell
.\gradlew.bat :androidApp:assembleRelease
```

产物:

```text
androidApp/build/outputs/apk/release/androidApp-release.apk
```

构建会在结束后递增 `version.properties`(本地与云端一致,`incrementVersion` 是构建任务的 `finalizedBy`)。刚生成 APK 的版本**只能**从以下文件读取:

```text
androidApp/build/outputs/apk/release/output-metadata.json
```

不能读取构建后的 `version.properties` 作为本包版本,也不能回滚该文件(重号会静默覆盖已发布记录的 sha256,导致更新推不出去;跳号无害)。

## 3. APK 登记

请求体是 APK **原始字节**,不是 JSON、base64 或 multipart:

```powershell
$apk = "androidApp\build\outputs\apk\release\androidApp-release.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

curl.exe -X POST "https://你的域名/api/v1/app/admin/releases?versionCode=版本号&versionName=版本名&note=更新说明&rollout=0&sha256=$sha" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  --data-binary "@$apk"
```

管理员会话的取法见 [80-admin-auth-login.md](80-admin-auth-login.md)(登录拿 `data.token`,开了 2FA 再走 `totp-verify`)。首次登记保持 `rollout=0`,先验证下载和安装。

发布登记依赖 `ON CONFLICT DO UPDATE`:同渠道同版本号重复登记会更新元数据(表约束见 [42-database-tables-release.md](42-database-tables-release.md))。

**2026-10-01 起云端发版可免手工登记**:CI 的 `client-publish` 发版时把 APK 与 `metadata.json`(versionCode/versionName/sha256/size)一起传上 Release `latest`;配好 `GITHUB_WEBHOOK_SECRET` 后,`POST /app/github-webhook` 收到 GitHub webhook 会自动拉取并登记版本。上面的手工登记仍然可用,也是 webhook 失联时的兜底(机制见 [62-ci-cloud-build.md](62-ci-cloud-build.md))。

## 4. 下载验证

```powershell
# Range 应返回 206
curl.exe -D - -o NUL -H "Range: bytes=100-199" "https://你的域名/api/v1/app/apk/版本号"

# 越界应返回 416
curl.exe -o NUL -w "%{http_code}`n" -H "Range: bytes=999999999-" "https://你的域名/api/v1/app/apk/版本号"
```

全量下载后再次核对 sha256 和文件大小。

**构件上云后的路径说明**:`app_release.apk_url` 有值时,bootstrap 与下载链接下发的是带 `DOWNLOAD_PROXY_PREFIX` 前缀的 GitHub Release 代理直链,服务器不再转发字节;`apk_url` 为空(历史登记)才由本机 `/app/apk` 提供字节。本节的 Range/416 探针验证的是回落路径,直链路径以 GitHub Release 侧为准(机制见 [62-ci-cloud-build.md](62-ci-cloud-build.md))。

## 5. 灰度与全量

```powershell
curl.exe -X POST "https://你的域名/api/v1/app/admin/rollout" `
  -H "content-type: application/json" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  -d '{"versionCode":123,"percent":10}'
```

- 灰度主体按用户/设备稳定哈希分桶,命中才可见。
- 确认稳定后逐步提高至 100。只有 100% 放量版本才能进入 `X-Latest-Version-Code` 响应头。

写操作记 `release.rollout` 审计(见 [81-admin-roles-audit.md](81-admin-roles-audit.md))。

## 6. 最低支持版本

抬高下限前必须已有版本号不低于目标、启用且放量 100% 的发布,否则接口返回 409/4091:

```powershell
curl.exe -X POST "https://你的域名/api/v1/app/admin/min-version" `
  -H "content-type: application/json" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  -d '{"versionCode":123}'
```

**顺序永远是先全量目标版本,再抬高下限。** 没有可下载的全量 APK 就抬高下限,等于把旧用户锁死在更新循环里(见 [73-troubleshooting-release.md](73-troubleshooting-release.md))。

## 7. 热修复补丁

补丁登记走 `/app/admin/patches` 与 `/app/admin/patch-rollout`,语义与 APK 灰度一致但目标锁定宿主 `targetVersionCode`。路由清单见 [00-code-index.md](00-code-index.md);完整流程见 [RELEASE.md](../../RELEASE.md)。
