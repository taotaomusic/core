# 桃桃音乐后端服务

NestJS + TypeScript 实现的无状态音乐接口适配服务。媒体、图片和歌词只做实时转发；SQLite 仅保存用户账号、刷新令牌哈希、收藏和热更新发布记录，不保存媒体内容。

## 启动

```powershell
npm install
npm run dev
```

生产构建与部署：

```powershell
npm run build
```

产物是 `dist/` 目录树（不是单文件）。**部署步骤**：

1. 上传 `dist/` 整个目录和 `package-lock.json` 到服务器
2. 在 `dist/` 同级执行 `npm install --omit=dev`（`dist/package.json` 只列出生产依赖）
3. `node dist/main.js` 启动，或在 `dist/` 内 `npm start`

> 迁移说明：早先用 esbuild 打成单文件 `dist/app.js`。NestJS 的构造器注入依赖
> `emitDecoratorMetadata` 生成的 `design:paramtypes` 元数据，而 esbuild 没有类型检查器、
> 无法生成这份元数据，因此改用 `tsc` 输出目录树。

开发模式用 `ts-node`（`npm run dev`，Node 的 `--watch` 负责重启）。

**不能用 tsx / esbuild 跑开发**：它们不支持 `emitDecoratorMetadata`，NestJS 的构造器注入拿不到 `design:paramtypes`，启动时会报 `Cannot read properties of undefined`。同理，构建也必须用 `tsc` 而不是 esbuild。

## 模块结构

```
src/
  main.ts                 启动引导：全局前缀、守卫/拦截器/过滤器、按路由挂载 body parser
  app.module.ts           根模块

  common/                 跨模块的基础设施
    api.exception.ts        带业务码的异常
    filters/                统一错误信封
    interceptors/           统一成功信封、安全响应头
    decorators/             @Public @RawResponse @RateLimit @CurrentUser
    guards/                 管理令牌校验
    rate-limit/             三桶滑动窗口限流
    semaphore.ts            并发上限

  config/                 环境变量读取与校验
  database/               SQLite 连接与建表迁移
  auth/                   注册、登录、令牌轮换、访问令牌守卫
  favorites/              收藏
  upstream/               第三方接口适配（成功码、字段名、音质降级都收敛在此）
  music/                  搜索、播放转发、歌词
  release/                热更新：客户端引导、安装包分发、发布管理
  health/                 健康检查
```

### 鉴权是显式白名单

全局 `AccessTokenGuard` 默认要求所有路由携带访问令牌，公开路由必须用 `@Public()` 标注。

这替掉了早先「靠门禁那行代码的位置来保证鉴权」的隐式约定 —— 那种写法下，新增路由放错位置就是安全漏洞，且看代码不容易发现。现在漏标 `@Public()` 只会让接口意外要求登录（能被立刻发现），而不是意外裸奔。

## 配置

服务启动时读取同目录的 `.env`（已存在的环境变量优先），可参考 `.env.example`。`.env` 保存密钥，不要提交到版本库。

| 变量 | 说明 |
| --- | --- |
| `PORT` | 监听端口，默认 `4500` |
| `DATABASE_PATH` | SQLite 路径，默认 `./data/music.sqlite` |
| `AUTH_SECRET` | 访问令牌签名密钥，生产环境必须为至少 32 位随机值（启动时校验） |
| `ADMIN_TOKEN` | 发布管理令牌，留空则管理接口全部拒绝 |
| `APK_DIR` | APK 存放目录，默认 `./data/apk` |
| `DEFAULT_CHANNEL` | 默认渠道，默认 `release` |
| `PUBLIC_BASE_URL` | 对外基地址，用于拼装 APK 下载地址 |
| `SEARCH_CONCURRENCY` | 搜索时解析播放地址的并发上限，默认 `8` |
| `ENV_FILE` | 指定 `.env` 的其它路径 |

## 响应约定

成功统一为 `{ "code": 0, "message": "success", "data": ... }`，失败为 `{ "code": <业务码>, "message": "<中文文案>" }`。客户端判断成功的唯一依据是 `code == 0`。

以下端点自己写响应体，**不套信封**（代码里用 `@RawResponse()` 标注）：

- `GET /api/v1/search` —— NDJSON 流
- `GET /api/v1/songs/{id}/play` —— 音频流
- `GET /api/v1/songs/{id}/lyrics` —— 默认纯文本（带 `format=json` 时才是信封）
- `GET /api/v1/app/apk/{versionCode}` —— 二进制

## 接口

### 认证

- `POST /api/v1/auth/register`，JSON：`{"username":"用户名","password":"至少6位密码"}`，成功返回 **201**
- `POST /api/v1/auth/login`，JSON：`{"username":"用户名","password":"密码"}`
- `POST /api/v1/auth/refresh`，JSON：`{"refreshToken":"刷新令牌"}`
- `POST /api/v1/auth/logout`，JSON：`{"refreshToken":"刷新令牌"}`，返回 **204** 空体
- `GET /api/v1/auth/me`，需要访问令牌

密码使用随机盐和高成本 scrypt 哈希，不保存明文。访问令牌有效期 15 分钟，刷新令牌 30 天；刷新令牌只保存 SHA-256 哈希，**刷新时轮换**，注销后立即失效。登录和注册按来源地址限流。

访问令牌格式为 `base64url(payload).HMAC-SHA256(payload, AUTH_SECRET)`，刻意没有换成 `@nestjs/jwt` —— 换格式会让所有已安装客户端手里的令牌立刻失效。

### 收藏（需要访问令牌）

- `POST /api/v1/favorites/tencent/105648974`
- `DELETE /api/v1/favorites/tencent/105648974`
- `GET /api/v1/favorites`

### 搜索、播放与歌词（需要访问令牌）

`GET /api/v1/search?keyword=歌曲名&page=1&num=20&quality=10`

`num` 范围 1–60（也接受 `limit`），`quality` 范围 0–16。返回 NDJSON 流，每行一个 `{"type":"song","data":{...}}`，末行为 `{"type":"end","meta":{...}}`。`meta.dropped` 是因所有音质都拿不到播放地址而被丢弃的数量，客户端据此提示用户。

搜索先打一次上游拿列表，再**并发**解析各首的播放地址（并发上限见 `SEARCH_CONCURRENCY`）。串行实现下 20 首约需 11 秒，并发后约 2 秒。

`GET /api/v1/songs/{id}/play?quality=10` 转发音频流，支持 Range 断点续传（透传给上游并回写 206 与 `Content-Range`）；无 Range 时返回 200 全量。

`GET /api/v1/songs/{id}/lyrics` 默认返回纯 LRC 文本；带 `format=json` 时返回 `{lrc, yrc, trans}`，其中 `yrc` 是逐字时间轴，格式为 `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`。

### 上游接口与音质降级

搜索与播放链接用 v3（`https://api.vkeys.cn/music/tencent`，路径里不带版本号前缀），歌词用 v2（`https://api.vkeys.cn/v2/music/tencent`）—— 只有 v2 的歌词接口同时给出逐字时间轴与翻译。

实测发现文档与实际不符，实现以实际响应为准：

- v3 搜索的封面字段是 `cover`，文档写的 `albumImage` 不存在
- v3 播放链接实际只返回 `songID` / `songMID` / `kbps` / `link` / `url`，文档里的歌名、歌手、封面、时长、音质全都没有，因此元信息全部取自搜索结果
- 成功码不统一：v3 用 `0`，v2 歌词用 `200`，两个都认
- v3 播放链接**不会自动降级**，付费歌曲请求 `quality=14` 直接返回 `code=110000`，因此实现了音质阶梯 `[14,11,10,8,4,0]`，最多试 4 档
- 上游会返回 `code=0` 但 `kbps=0kbps` 的死链，所以首字节探测放在阶梯循环**内部**：某一档给出死链时继续往下试，否则会白白放弃后面本来可用的低音质档位

### 热更新

设计说明见项目根目录 `HOT_UPDATE.md`。

客户端接口（**免鉴权**，原因是最需要强制更新的场景恰恰是「上一个版本把登录搞坏了」）：

- `GET /api/v1/app/bootstrap?versionCode=54&sdk=36&deviceId=<uuid>&channel=release`

  一次返回更新信息与远程配置。`Authorization` 可选：带了有效令牌就按用户做灰度分桶，未登录或**令牌已过期**时退回 `deviceId`。这条路由永远不会返回 401 —— 客户端刻意用未续期的令牌调用它，返回 401 会让热更新通道静默失效。

- `GET /api/v1/app/apk/{versionCode}`：下载安装包，支持 Range（206 / `Content-Range`），越界返回 416，`ETag` 为 APK 的 sha256。

发布管理（需要请求头 `X-Admin-Token`）：

- `GET /api/v1/app/admin/releases?channel=release`
- `POST /api/v1/app/admin/releases?versionCode=&versionName=&note=&rollout=&minSdk=&sha256=`

  请求体为 **APK 原始字节**（不是 base64、不是 multipart），服务端边写盘边算 sha256。这条路由在 `main.ts` 里被排除在 JSON body parser 之外，否则 14MB 的包会被缓进内存或直接 413。

  ```powershell
  curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/releases?versionCode=54&versionName=1.0.53&note=修复闪退&rollout=0" `
    -H "X-Admin-Token: $env:ADMIN_TOKEN" `
    --data-binary "@androidApp/build/outputs/apk/release/androidApp-release.apk"
  ```

  `versionCode` 和 `versionName` 必须取自 `androidApp/build/outputs/apk/release/output-metadata.json`，**不能读 `version.properties`**：`incrementVersion` 是 `assemble` 的 `finalizedBy`，构建结束时那个文件里的值已经比刚产出的 APK 大 1，登记错了客户端会陷入「提示更新 → 装完还提示」的死循环。

- `POST /api/v1/app/admin/rollout`，JSON `{"versionCode":54,"percent":10}`
- `POST /api/v1/app/admin/min-version`，JSON `{"versionCode":54}`

  内置守卫：必须已存在放量 100% 且版本号不低于目标下限的发布，否则返回 409。这是为了堵住变砖路径 —— 被判定为强制更新的客户端只会收到全量版本，若不存在这样的版本，它们会被拦在门外却拿不到升级包。

- `GET /api/v1/app/admin/config`
- `POST /api/v1/app/admin/config`，JSON `{"key":"feedback.enabled","value":"true","minVersionCode":70}`，`value` 传 `null` 表示删除

### 灰度分桶

命中条件为 `sha1(发布ID + ":" + 主体标识)` 前 4 字节取模 100 后小于 `rollout_percent`。

混入发布 ID 是为了让每个版本得到互相独立的分桶，否则永远是同一批用户当小白鼠；用哈希而非随机数是为了对同一用户稳定，否则每次检查结果都会翻转，更新提示时有时无。实测 20000 个设备号在 10% / 30% / 50% 三档下的命中率为 9.60% / 29.93% / 50.46%。

### 限流

三个计数器互相独立 —— 更新检查是周期性调用，与登录共用会烧掉用户的登录额度，表现成「登录提示请求过于频繁」。

| 用途 | 阈值 |
| --- | --- |
| 登录 / 注册 | 按来源地址 10 次 / 15 分钟 |
| 客户端引导与安装包下载 | 按设备号 60 次 + 按来源地址 900 次 / 15 分钟 |
| 发布管理 | 按来源地址 60 次 / 15 分钟 |

外层阈值放得宽，因为校园网、办公网等 NAT 环境下大量用户共用一个出口地址，按 IP 收紧会互相挤占。状态在进程内存中，重启即清空，也不跨实例共享。

## 契约验证

线上有已安装的客户端，且 `/api/v1/app/bootstrap` 本身就是推送修复的通道，改动后必须逐项核对响应形状。用独立的临时数据库起一个实例，然后：

```powershell
$env:PORT="4720"; $env:DATABASE_PATH="./tmp/verify.sqlite"; $env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"; $env:ADMIN_TOKEN="verify-token"
npm run dev
# 另一个终端
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

脚本会核对状态码、业务码、信封形状、NDJSON 行格式、字段类型（`data.id` 必须是 number、`songId` 必须是字符串）、纯文本歌词、Range 行为，以及「无效令牌访问 bootstrap 仍返回 200」这类红线。
