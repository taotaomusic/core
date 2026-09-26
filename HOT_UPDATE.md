# 桃桃音乐热更新设计

## 1. 目标与范围

本文保留原始设计依据，并记录当前已落地的能力。整包更新、远程配置和 DEX 逻辑热修都已实现；
实际发布步骤以 [RELEASE.md](RELEASE.md) 为准。

> **实现状态说明**：本文第 2、3 节保留迁移前的路由和 SQLite 设计，用于解释约束，文中的
> `server/src/routes/api.ts`、旧脚本路径和 SQLite DDL **不是当前文件布局或可直接执行的实现**。
> 现行 NestJS 模块、104 条路由、PostgreSQL 表结构和契约边界以
> [server/wiki/00-code-index.md](server/wiki/00-code-index.md)、
> [server/wiki/03-api-contracts.md](server/wiki/03-api-contracts.md) 与
> [server/wiki/04-database.md](server/wiki/04-database.md) 为准。

| 能力 | 说明 |
| --- | --- |
| 整包 APK 应用内更新 | 服务端维护版本清单与 APK 文件，客户端检查、断点续传下载、校验完整性、唤起系统安装 |
| 远程配置 | 服务端下发开关、文案、公告，不发版即可改行为；为后续意见采集预留开关位 |
| DEX 逻辑热修 | 对 `data`、`player`、`update` 的稳定方法入口插桩，补丁即时生效并可自愈回滚 |

下发控制能力：**强制更新**（低于最低可用版本无法继续使用）+ **灰度放量**（按百分比逐步放开）。

不在范围：Compose 界面函数热修和新增类/字段。Compose 函数不能在入口提前返回，新增类/字段也无法注入已经安装的 APK；这两类变更必须发整包。

### 1.1 热修边界与设计准则

热修不是“任何代码都能改”，而是“已发布包里预先插过桩的**稳定业务方法**可以替换”。当前
`data`、`player`、`update` 包下的非构造、非合成方法都在范围内；`ui` 保持排除。

- 新业务逻辑优先下沉到上述包的协调器，Compose 只负责渲染与分发事件。
- 播放会话的结束落盘与补传已收敛在 `data/PlaybackSyncCoordinator`；播放器以 `STATE_ENDED`
  主动通知页面，避免依赖页面协程或杀后台才收尾。
- 补丁只能替换已存在的方法实现，不能给旧安装包凭空新增播放器回调。因此，已经发布但没有
  该回调的版本仍需一次整包升级；升级后该协调器的重试和统计规则即可用 DEX 紧急修正。

## 2. 从现有代码发现的约束

设计阶段必须绕开的坑，按严重程度排列。

### 2.1 更新检查接口必须免鉴权

`server/src/routes/api.ts:16` 有一道总门禁：

```ts
if (!authenticate(request)) return json(response, 401, { code: 4010, message: "请先登录" });
```

它之后注册的所有路由都需要有效访问令牌。**更新检查必须注册在这一行之前。** 理由是最需要强制更新的场景恰恰是"上一个版本把登录搞坏了"——如果检查接口本身要登录，这些客户端永远收不到升级通知，只能靠用户自己发现。

令牌改为可选：带了且有效就解析出 `userId` 用于灰度分桶，没带就退回客户端上报的匿名设备号。

### 2.2 强制更新的目标必须绕过灰度

如果抬高 `min_supported_version_code` 之后，把升级目标也走灰度筛选，那么不在灰度名单里的用户会被拦在门外却拿不到升级包——直接变砖，且只能靠卸载重装恢复。

**规则：被判定为强制更新的客户端，一律返回放量 100% 的最新版本，永不返回灰度版本。** 服务端若找不到满足条件的 100% 版本，必须返回"无更新"并打警告日志，而不是返回一个灰度包。

### 2.3 灰度分桶必须稳定，且按发布维度独立

- 不能用随机数：每次检查结果会翻转，更新弹窗时有时无。
- 不能只对用户标识做哈希：那样永远是同一批人当小白鼠。

规则：`bucket = uint32(sha1(releaseId + ":" + subjectId)[0..4]) % 100`，命中条件 `bucket < rollout_percent`。把 `releaseId` 混进哈希，每个版本得到一组互相独立又对单个用户稳定的分桶。

### 2.4 版本号陷阱：APK 的 versionCode 比 version.properties 小 1

`androidApp/build.gradle.kts:35` 用 `finalizedBy("incrementVersion")`，而 `versionProperties` 在配置阶段读取。因此顺序是「读到 N → 产出 versionCode=N 的 APK → 把文件写成 N+1」。实测印证：构建输出 `版本已更新为 1.0.54 (55)`，而 `output-metadata.json` 里是 `versionCode: 54 / versionName: 1.0.53`。

**发布脚本必须从 `output-metadata.json` 读 versionCode 和 versionName，绝不能读 `version.properties`**，否则登记的版本号会比 APK 实际值大 1，客户端下载后装不上（系统认为不是更高版本），且会陷入"提示更新→装完还提示"的死循环。

另外该脚本对 `assembleDebug` 也生效，所以 versionCode 存在跳号——这没问题，只要单调递增即可。

### 2.5 限流桶必须与登录分开

`server/src/rate-limit.ts` 的 `allowAuthAttempt(key)` 按 key 计数。更新检查会被客户端周期性调用，如果复用登录的 key 前缀，正常轮询会把用户的登录尝试额度烧掉，表现为"登录提示请求过于频繁"。更新检查用独立前缀，且阈值放宽。

### 2.6 签名密钥不可更换

`taotao-release.jks` 一旦丢失或更换，所有已安装用户都无法覆盖安装，只能卸载重装（丢本地下载与登录态）。这是自签名分发的单点风险，密钥必须离线备份。

### 2.7 APK 下载需要支持 Range

现有 `streamMedia`（`api.ts:76`）不支持 Range 请求，只做全量转发。APK 约 14 MB，弱网下必须能断点续传，所以 APK 路由要单独实现 206 / `Content-Range` / `Accept-Ranges`，不复用 `streamMedia`。

## 3. 服务端设计

### 3.1 数据模型

追加三张表，与其它表放在一处。

> 落地时的偏移：建表 SQL 现在集中在 `server/src/database/migrations.ts`，且数据库已从 SQLite 换成 PostgreSQL，下面的 DDL 是当初的 SQLite 版本，实际类型见该文件。

```sql
-- 发布记录：一条 = 一个可下发的 APK
CREATE TABLE IF NOT EXISTS app_release (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  channel           TEXT    NOT NULL DEFAULT 'release',
  version_code      INTEGER NOT NULL,
  version_name      TEXT    NOT NULL,
  apk_file          TEXT    NOT NULL,           -- 相对 data/apk 的文件名
  apk_size          INTEGER NOT NULL,
  apk_sha256        TEXT    NOT NULL,
  release_note      TEXT    NOT NULL DEFAULT '',
  rollout_percent   INTEGER NOT NULL DEFAULT 0, -- 0=已发布但不下发，100=全量
  min_sdk           INTEGER NOT NULL DEFAULT 24,
  enabled           INTEGER NOT NULL DEFAULT 1,
  published_at      INTEGER NOT NULL,
  UNIQUE(channel, version_code)
);

-- 渠道级策略：强制更新下限
CREATE TABLE IF NOT EXISTS app_channel (
  channel                     TEXT    PRIMARY KEY,
  min_supported_version_code  INTEGER NOT NULL DEFAULT 0,
  updated_at                  INTEGER NOT NULL
);

-- 远程配置：键值 + 可选的版本区间约束
CREATE TABLE IF NOT EXISTS app_config (
  key               TEXT    PRIMARY KEY,
  value             TEXT    NOT NULL,
  min_version_code  INTEGER,                    -- NULL = 不限
  max_version_code  INTEGER,
  updated_at        INTEGER NOT NULL
);
```

`app_config` 的版本区间用于"新配置只对新版本生效"，避免下发老版本不认识的开关。

### 3.2 接口

新增 `server/src/routes/app.ts`，在 `handleApi` 的鉴权门禁**之前**分流：

```ts
// api.ts，插在 auth 路由之后、authenticate 门禁之前
if (path.startsWith("/api/v1/app/")) return handleApp(url, request, response);
```

#### `GET /api/v1/app/bootstrap`

启动时一次请求同时拿到更新信息和远程配置，减少往返。

请求参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `channel` | 否 | 默认 `release` |
| `versionCode` | 是 | 客户端当前 versionCode |
| `sdk` | 是 | `Build.VERSION.SDK_INT`，用于过滤 minSdk 不兼容的包 |
| `deviceId` | 是 | 客户端首次启动生成并持久化的 UUID，未登录时作为灰度分桶主体 |

`Authorization: Bearer <token>` **可选**。有效时用 `userId` 作为分桶主体，否则用 `deviceId`。

响应沿用现有 `{ code, message, data }` 信封（`utils/http.ts` 的 `success()`）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "update": {
      "available": true,
      "forced": false,
      "versionCode": 60,
      "versionName": "1.0.59",
      "apkUrl": "https://music.xydaigua.cn/api/v1/app/apk/60",
      "apkSize": 14118093,
      "apkSha256": "3f2a…",
      "releaseNote": "修复长时间播放闪退",
      "minSupportedVersionCode": 40
    },
    "config": {
      "feedback.enabled": "true",
      "notice.text": ""
    },
    "configVersion": 17
  }
}
```

无更新时 `update` 为 `{ "available": false, "forced": false, "minSupportedVersionCode": 40 }` —— 即使没有可用更新，也始终回传下限，客户端据此判断自己是否已被弃用。

`configVersion` 取 `app_config` 里最大的 `updated_at`，客户端可带 `If-None-Match` 换 304。

#### `GET /api/v1/app/apk/:versionCode`

免鉴权（升级包不是敏感资产，且令牌失效的客户端必须能下载）。要求：

- `Content-Type: application/vnd.android.package-archive`
- `Content-Length`、`Accept-Ranges: bytes`、`ETag`（用 sha256）
- 支持 `Range: bytes=N-`，命中返回 `206` + `Content-Range`
- 只允许读 `data/apk/` 下、且在 `app_release` 中登记的文件名，防路径穿越

#### 预留

`/api/v1/app/feedback`、`/api/v1/app/events` 命名空间保留给后续的意见采集与更新漏斗埋点，本轮不实现。

### 3.3 版本判定算法

```
输入: channel, clientVersionCode, sdk, subjectId
1. floor = app_channel.min_supported_version_code (缺省 0)
2. forced = clientVersionCode < floor
3. 候选集 = app_release WHERE channel=? AND enabled=1
                     AND min_sdk <= sdk
                     AND version_code > clientVersionCode
4. if forced:
       目标 = 候选集中 rollout_percent = 100 的最大 version_code
       若为空 → 返回 available:false 并打警告（配置错误：抬高了下限却没有全量版本）
   else:
       目标 = 候选集中满足 bucket(releaseId, subjectId) < rollout_percent 的最大 version_code
5. 返回目标 + forced + floor
```

分桶函数：

```ts
function bucketOf(releaseId: number, subject: string): number {
  const digest = createHash("sha1").update(`${releaseId}:${subject}`).digest();
  return digest.readUInt32BE(0) % 100;
}
```

### 3.4 发布流程

不做管理后台 —— 与项目现有「本地构建 + 手工交付」的节奏一致，也不引入新的鉴权面。

> 落地时的偏移：当初设想的是本地脚本直连数据库，实际实现成了需要管理员会话（`Authorization: Bearer`）的管理接口（见 `server/README.md` 的「热更新」一节），下面这几个脚本并不存在。

```powershell
# 1. 构建
.\gradlew.bat :androidApp:assembleRelease

# 2. 登记（脚本从 output-metadata.json 读版本号，算 sha256，拷贝 APK 到 server/data/apk/）
node server\tools\publish-release.mjs --note "修复长时间播放闪退" --rollout 0

# 3. 灰度放量
node server\tools\rollout.mjs --version 60 --percent 10
node server\tools\rollout.mjs --version 60 --percent 50
node server\tools\rollout.mjs --version 60 --percent 100

# 4. 确认稳定后再抬高强制更新下限（必须在有 100% 版本之后）
node server\tools\min-version.mjs --version 55
```

`min-version.mjs` 内置守卫：若不存在 `rollout_percent = 100` 且 `version_code >= 目标下限` 的发布，拒绝执行并提示，从设计上堵住 2.2 的变砖路径。

## 4. 客户端设计

### 4.1 包结构

新增 `com.taotao.music.update` 包，与 `player` / `data` 平级：

| 文件 | 职责 |
| --- | --- |
| `update/AppUpdateApi.kt` | `bootstrap` 请求。`HttpURLConnection` 风格对齐 `TencentMusicApi`；令牌可选，不复用 `authorized()` 的强制鉴权与重放逻辑 |
| `update/UpdateDownloader.kt` | 断点续传下载到 `filesDir/update/<versionCode>.apk.part`，校验 sha256 后 rename；复用 `OfflineDownloadManager` 的 `.part` + rename 惯例 |
| `update/UpdateInstaller.kt` | 检查 `canRequestPackageInstalls()`，通过 FileProvider 生成 content URI，唤起系统安装器 |
| `update/UpdateModels.kt` + `update/UpdateManager.kt` | 状态机与数据模型（原计划的 `UpdateState.kt` 落地时拆成了两个文件） |
| `data/DeviceIdStore.kt` | 首次启动生成并持久化匿名设备号，用于灰度分桶 |
| `data/RemoteConfigStore.kt` | 缓存远程配置到 SharedPreferences，提供带默认值的类型化读取 |
| `ui/update/UpdateGate.kt` | 强制更新拦截页 + 可选更新对话框 |

### 4.2 状态机

```kotlin
sealed interface UpdateState {
    object Idle : UpdateState
    object Checking : UpdateState
    object UpToDate : UpdateState
    data class Available(val release: UpdateRelease, val forced: Boolean) : UpdateState
    data class Downloading(val release: UpdateRelease, val forced: Boolean, val percent: Int) : UpdateState
    data class ReadyToInstall(val release: UpdateRelease, val apk: File, val forced: Boolean) : UpdateState
    data class Failed(val reason: String, val forced: Boolean) : UpdateState
}
```

`forced` 贯穿所有状态：强制流程下失败也不能放行，只能重试。

### 4.3 界面门禁位置

`TaotaoMusicApp` 现在的门禁顺序是「未登录 → `AuthPage` 并 return」。强制更新必须插在**登录之前**：

```kotlin
// 强制更新优先于登录：坏版本可能连登录都是坏的
if (updateState.isForced) {
    ForceUpdatePage(updateState, onRetry = ..., onInstall = ...)
    return
}
if (!signedIn) { AuthPage(...); return }
```

非强制更新走 `AlertDialog`，可关闭，本次启动内不再重复提示。

### 4.4 检查时机

- 冷启动一次（在 `TaotaoApplication` 之后、首帧渲染之后触发，不阻塞启动）
- 应用从后台回到前台且距上次检查超过一定间隔时再查一次
- **不做轮询**，避免无谓耗电与流量

远程配置随 bootstrap 一起返回，写入 `RemoteConfigStore`；读取时永远有代码内默认值兜底，保证首帧和离线可用。

### 4.5 Manifest 与权限

```xml
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />

<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data android:name="android.support.FILE_PROVIDER_PATHS"
               android:resource="@xml/file_paths" />
</provider>
```

安装意图：

```kotlin
Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(fileProviderUri, "application/vnd.android.package-archive")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
```

Android 8+ 需要用户为本应用开启「安装未知应用」。若 `packageManager.canRequestPackageInstalls()` 为 false，先跳 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 引导授权，再回来安装——不要直接抛安装意图，否则用户只会看到一个没有说明的系统拒绝。

注意：`REQUEST_INSTALL_PACKAGES` 会让应用不符合 Google Play 上架政策。当前是自签名分发，不受影响，但如果将来要上架必须改为商店内更新。

## 5. 安全

| 环节 | 措施 |
| --- | --- |
| 清单与下载传输 | 强制 HTTPS（`ENDPOINT` 已是 `https://`） |
| 包完整性 | 清单内 `apk_sha256`，下载完成后校验，不匹配则删除重下，绝不安装 |
| 包来源 | 系统在安装时强制校验签名密钥一致，与已装应用不同签名会被拒绝——这是最终防线 |
| 路径穿越 | APK 路由只接受数字 versionCode，文件名从数据库取，不拼接用户输入 |
| 降级攻击 | 只接受 `version_code > 当前版本` 的目标，服务端和客户端各校验一次 |

## 6. 分期实施建议

| 阶段 | 内容 |
| --- | --- |
| 一 | 服务端建表 + `bootstrap` + APK 路由（含 Range）+ `publish-release.mjs` |
| 二 | 客户端 `AppUpdateApi` + `DeviceIdStore` + 可选更新对话框（先不做强制） |
| 三 | `UpdateDownloader` 断点续传 + sha256 校验 + `UpdateInstaller` 安装引导 |
| 四 | 强制更新门禁 + `rollout.mjs` / `min-version.mjs` + 灰度分桶 |
| 五 | `RemoteConfigStore` 远程配置接入，开出意见采集开关位 |

先做一、二即可获得"能主动告知用户有新版"的核心价值；强制更新和灰度是运营能力，可以后置。
