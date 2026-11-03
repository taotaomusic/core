# @taotao/hotupdate-node —— 热更新 Node 客户端、运维 SDK 与自建服务

配套 `hotupdate/`（Kotlin 客户端模块）的后端侧工具集，**零运行时依赖**（仅 Node 20+ 内置能力），
供六类调用方使用：

| 调用方 | 用什么 |
| --- | --- |
| 其它 Node 服务 / 机器人 | `HotUpdateClient`：调 `/app/bootstrap` 判断有没有新版本/补丁（带超时与网络级重试） |
| 下载侧脚本 | `downloadToFile`：断点续传 + sha256 校验的安装包/补丁下载 |
| 运维脚本 / CI 步骤 | `HotUpdateAdmin`：发布、放量、补丁、远程配置的管理接口 SDK |
| 自测 / 联调 | `bucketOf` / `pickGrayCandidate`：与服务端**同源**的灰度分桶算法 |
| 直连管理接口的脚本 | `totp()`：本地生成 2FA 动态码（与服务端同一实现） |
| 想要独立热更新后端的系统 | `createHotUpdateServer`：零依赖可自建服务（与主服务端同契约，见下文） |

## 快速开始

```powershell
cd hotupdate-node
npm install
npm run verify   # = tsc 构建 + node --test 全部用例
```

## 快速调用

### 1. 检查更新（代替真实客户端做联调）

```ts
import { HotUpdateClient } from "@taotao/hotupdate-node";

const client = new HotUpdateClient("https://music.xydaigua.cn", "MyBot/1.0");
const { result, latestVersionCode } = await client.check({
  channel: "release",
  versionCode: 71,          // 本机 versionCode
  sdk: 35,                  // Android SDK_INT；非 Android 联调时服务端要求 >0，可传真实值
  deviceId: "dev-1",        // 与客户端相同的稳定设备号
  token: null,              // 未登录传 null，服务端退回按设备号分桶
});

if (result.release) console.log("有新版本", result.release.versionCode, result.release.apkUrl);
if (result.patch) console.log("有补丁", result.patch.patchVersion, "目标版本", result.patch.targetVersionCode);
console.log("远程配置", result.config);
```

### 2. 灰度预判（本地推算某个主体能不能拿到某版本）

```ts
import { bucketOf, pickGrayCandidate, bucketSubject } from "@taotao/hotupdate-node";

// 服务端对 release.id=<数据库主键> 的发布按 SHA-1("%d:%s") % 100 分桶：
const bucket = bucketOf(123, bucketSubject({ id: 42 }, "")); // user:42
// 拿管理端 releases 列表预判命中：
const hit = pickGrayCandidate(releases, "user:42"); // 与 /app/bootstrap 同判据
```

> 权威判定永远在 `/app/bootstrap`；这里的实现与服务端
> `server/src/release/release.repository.ts#bucketOf` **逐字节一致**，测试里用同算法互验。

### 3. 运维脚本：登记版本 → 放量 → 抬强制下限

```ts
import { HotUpdateAdmin, totp } from "@taotao/hotupdate-node";

const admin = new HotUpdateAdmin("https://music.xydaigua.cn");
// 密码 + 2FA 登录（动态码用 TOTP 密钥本地生成，不用手抄验证器）
await admin.login("admin", process.env.ADMIN_PASSWORD!, totp(process.env.ADMIN_TOTP_SECRET!)[1]);
// 或者用已有会话令牌：admin.withToken(process.env.ADMIN_TOKEN!)

// 登记外链版本（GitHub Release 产物；不上传字节）
await admin.linkRelease({
  versionCode: 72,
  versionName: "1.0.72",
  apkUrl: "https://github.com/taotaomusic/core/releases/download/v1.0.72/TaotaoMusic-1.0.72-release.apk",
  sha256: "<64 位十六进制>",
  rollout: 0,               // 默认不放量，先自测
});
await admin.rollout("release", 72, 10);   // 灰度 10%
await admin.rollout("release", 72, 100);  // 全量
await admin.minVersion("release", 72);    // 需要强制更新时才抬
```

上传 APK 字节的登记用 `admin.publishRelease({ apkPath, versionCode, versionName })`；
热修补丁用 `admin.publishPatch({ patchPath, targetVersionCode, patchVersion })` 与
`admin.patchRollout({ targetVersionCode, patchVersion, percent })`（紧急下架传 `enabled: false`）；
远程配置用 `admin.setConfig({ key, value, minVersionCode?, maxVersionCode? })`（`value: null` 删除）。

> 版本号铁律不变：`versionCode` / `versionName` 只能取自
> `androidApp/build/outputs/apk/release/output-metadata.json`，**不能读 `version.properties`**（构建结束时它已 +1）。

### 4. 直连管理接口的 2FA 动态码

```ts
import { totp, verifyTotp } from "@taotao/hotupdate-node";
const code = totp(secret)[1];          // 取 ±1 窗口的三个码之一，容 30s 时钟漂移
// RFC 4226 附录 D 向量与主流验证器行为都在测试里锁死
```

## 协议兼容锚点

`src/protocol.ts` 的 `UpdateDescriptor` / `PatchDescriptor` 与服务端
`server/src/release/release.service.ts` 的同名类型一一对应；`normalizeBootstrap` 的解析判据
（`available` + `versionCode > 0` + 地址非空、sha256 转小写）与 Kotlin 侧
`hotupdate/src/main/java/com/taotao/music/update/AppUpdateApi.kt` 完全一致。
**改协议必须三方同步**：Kotlin `hotupdate` 模块、本 SDK、服务端 `release` 模块，
并同步更新 `src/test/` 下的兼容测试。

## 自建热更新服务（零依赖一键起）

不想部署整个 NestJS 主服务端时，用 `createHotUpdateServer` 起一个与主服务端**同契约**的轻量实例：

```powershell
cd hotupdate-node
npm start -- --port 4730 --token <你的管理令牌> --data ./data --persist ./data/state.json
```

```ts
// 或者嵌入现有 Node 进程
import { createHotUpdateServer } from "@taotao/hotupdate-node";
const server = createHotUpdateServer({ port: 4730, adminToken: process.env.TOKEN! });
await server.listen();
```

起来之后 `HotUpdateClient` / Kotlin `hotupdate` 模块的 `endpoint` 指过去即可：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/v1/app/bootstrap` | 更新 + 补丁 + 远程配置（公开；灰度分桶与主服务端同算法） |
| GET | `/api/v1/app/apk/:versionCode` | 整包下载（支持 Range 续传 / HEAD / ETag；登记了外链则 302 跳转） |
| GET | `/api/v1/app/patch/:target/:version` | 补丁下载（同上） |
| POST | `/api/v1/admin/releases`（查询参数元数据 + APK 字节体） | 上传登记 |
| POST | `/api/v1/admin/releases/link` | 外链登记 |
| POST | `/api/v1/admin/rollout` / `min-version` | 放量 / 抬强制下限 |
| POST | `/api/v1/admin/patches`（查询参数元数据 + 补丁字节体） | 补丁上传登记（`rollout` 查询参数可同时放量） |
| POST | `/api/v1/admin/patch-rollout` | 补丁放量 / 紧急下架（`enabled:false`） |
| POST | `/api/v1/admin/config` | 远程配置（`value:null` 删除） |
| GET | `/api/v1/admin/releases` / `patches` / `config` | 列表 |

管理接口用静态 Bearer 令牌（不传 `--token` 则全部 401）；存储为内存 + 可选 JSON 快照
（`--persist`），不依赖任何数据库。**定位是参考实现/开发替身**：多实例共享状态、
企业级管理员认证（会话/2FA/审计）请用主服务端 `server/` 的 NestJS 版本。

## 测试覆盖

- `bucket.test.ts`：分桶算法与服务端实现互验（同 SHA-1 算法比对）、稳定性与值域、灰度命中语义。
- `totp.test.ts`：RFC 4226 附录 D 全部 10 个已知向量、base32 往返、时钟漂移容错。
- `protocol.test.ts`：Descriptor 展开判据与 Kotlin 解析语义一致（含字段不完整时拒绝）。
- `client.test.ts`：请求 URL/头/令牌、信封解析、HTTP 与业务错误抛错路径（fetch stub）。
- `server.test.ts`：自建服务全链路 —— 登记→放量→bootstrap（含灰度与强制更新）、
  补丁下发判定（无整包才发 + 目标版本精确匹配）、字节上传→Range/HEAD/ETag/404、管理鉴权与配置读写删除。
- `e2e.test.ts`：真实 HTTP 端到端 —— `HotUpdateClient` 起服务检查更新 → `downloadToFile` 带 sha256 校验落盘；
  校验失败清 .part；超时错误信息。

```powershell
npm test        # 跑 dist/test/*.test.js
npm run verify  # 构建 + 测试一条龙
npm start       # 一键起自建服务（构建 + node dist/server-cli.js）
```
