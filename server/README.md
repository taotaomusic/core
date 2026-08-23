# 桃桃音乐后端服务

> 后端架构、接口、数据库与运维说明统一整理在 [WIKI.md](WIKI.md)。

NestJS + TypeScript 实现的音乐接口适配服务。媒体、图片和歌词只做实时转发；PostgreSQL 保存用户账号、刷新令牌哈希、收藏、热更新发布记录、第三方 API Key 和图片任务元数据，不保存媒体内容。

## 启动

先准备数据库（只需一次）：

```powershell
psql -U postgres -c "CREATE DATABASE music"
```

建表由服务启动时自动完成（幂等 DDL + 顾问锁），不需要手工执行迁移。然后：

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

**PostgreSQL 必须先于本服务启动。** 数据库换成独立进程后多了一种失败模式：机器重启时若 Node 先起来，连接会失败。启动时有 10 次 × 1 秒的重试兜底，超过就退出交给进程管理器；用 systemd 的话建议加 `After=postgresql.service`。

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
  database/               PostgreSQL 连接池与建表迁移
  auth/                   注册、登录、令牌轮换、访问令牌守卫
  favorites/              收藏
  upstream/               第三方接口适配（成功码、字段名、音质降级都收敛在此）
  music/                  搜索、播放转发、歌词
  image-generation/       gpt-image-2 图片生成任务适配
  release/                热更新：客户端引导、安装包分发、发布管理
  health/                 健康检查
```

### 鉴权是显式白名单

全局 `AccessTokenGuard` 默认要求所有路由携带访问令牌，公开路由必须用 `@Public()` 标注。

这替掉了早先「靠门禁那行代码的位置来保证鉴权」的隐式约定 —— 那种写法下，新增路由放错位置就是安全漏洞，且看代码不容易发现。现在漏标 `@Public()` 只会让接口意外要求登录（能被立刻发现），而不是意外裸奔。

### 数据层的四条硬规矩

从 SQLite 迁过来时踩到的坑，改 SQL 前先看这几条 —— 违反它们**不会报错**，只会让功能静默失效。

**① 别名必须加双引号。** PostgreSQL 把不加引号的标识符折叠成小写，`AS songId` 得到的字段是 `songid`。客户端读不到 `songId` 后所有歌都显示未收藏，且没有任何报错。写 `AS "songId"`。

**② 存 `Date.now()` 的列一律 `bigint`，其它整数一律 `integer`。** 毫秒时间戳约 1.7e12，超出 int4 的 21 亿上限；反过来 pg 默认把 int8 解析成**字符串**，所以 `database.service.ts` 里注册了 `INT8 → Number` 的解析器。这个解析器成立的前提是 bigint 列只存时间戳 —— 今后别把真正的 64 位 ID 放进 bigint 列。

**③ `app_release.enabled` 是 `smallint` 0/1，不是 `boolean`。** 两个调用点写法不一致：`release.service.ts` 是 `enabled === 1`（严格等于数字），`release.controller.ts` 是 `!enabled`（真值判断）。改成 boolean 会让前者恒假，抬高最低可用版本的守卫就永远返回 409。

**④ `bucketOf` 必须保持同步。** 它在 `Array.prototype.find` 的回调里被调用，一旦变成 async，回调返回的 Promise 恒为真值，`find` 会命中第一个候选版本 —— 灰度静默失效成全量下发。

另外两处竞态是这次迁移顺带修掉的，别改回两步写法：刷新令牌的 `consume` 是**单条** `UPDATE … RETURNING`（拆成 SELECT + UPDATE 会让并发刷新双双成功，一次性令牌就不再一次性）；注册的唯一约束冲突在 `users.create` 里翻译成 409/4090（查重和插入之间夹着约 100ms 的 scrypt，连接池下挡不住并发，不翻译会漏成 502）。

## 配置

服务启动时读取同目录的 `.env`（已存在的环境变量优先），可参考 `.env.example`。`.env` 保存密钥，不要提交到版本库。

| 变量 | 说明 |
| --- | --- |
| `PORT` | 监听端口，默认 `4500` |
| `DATABASE_URL` | PostgreSQL 连接串，如 `postgres://postgres:密码@localhost:5432/music`。**没有默认值**，缺失或不是 `postgres://` 开头会启动即失败 |
| `AUTH_SECRET` | 访问令牌签名密钥，生产环境必须为至少 32 位随机值（启动时校验） |
| `ADMIN_TOKEN` | 发布管理令牌，留空则管理接口全部拒绝 |
| `APK_DIR` | APK 存放目录，默认 `./data/apk` |
| `DEFAULT_CHANNEL` | 默认渠道，默认 `release` |
| `PUBLIC_BASE_URL` | 对外基地址，用于拼装 APK 下载地址 |
| `SEARCH_CONCURRENCY` | 搜索时解析播放地址的并发上限，默认 `8` |
| `ENV_FILE` | 指定 `.env` 的其它路径 |
| `APISWEET_BASE_URL` | 图片生成服务地址，默认 `https://apisweet.com` |

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

`GET /api/v1/search?keyword=歌曲名&page=1&num=60&quality=10`

`num` 范围 1–60（也接受 `limit`），默认 60，`quality` 范围 0–18。返回 NDJSON 流，每行一个 `{"type":"song","data":{...}}`，末行为 `{"type":"end","meta":{...}}`。

**搜索只返回元信息，不解析播放地址。** 早先每首歌都要额外向上游要一次播放链接、还要探测首字节，20 首约 1.8 秒，60 首最坏能打 240 次上游请求；而客户端拿到后又会把这个地址丢掉，改用自己拼的播放地址 —— 那些请求换来的只是「把拿不到地址的歌过滤掉」。现在改成客户端点播时再走 `/songs/{id}/link` 单独解析，搜索只剩一次上游请求，**60 首实测 130–900ms**（取决于上游缓存）。

代价是付费/下架的歌现在会出现在结果里。搜索结果带 `vip` 字段（上游本来就返回 `pay`，之前没读），界面据此加标记；真正拿不到地址的歌在点播时才提示。`meta.dropped` 因此恒为 0，保留只为兼容装机的旧客户端。

每行还带上：

- `favorited` —— 当前用户是否已收藏，由一次批量查询填入（`song_id = ANY($3::text[])`，走 `UNIQUE (user_id, source, song_id)` 索引）。这替掉了客户端原来「为每首歌拉一次完整收藏列表再线性查找」的做法。批量查询刻意放在 `writeHead` **之前**：响应头一旦发出，异常就只能截断连接。
- `mid` / `type` —— 解析播放地址要用。`songID` 为 0 的歌只能靠 `mid` 解析，拆成独立接口后不下发它们就永远拿不到地址。

响应带 `X-Accel-Buffering: no`：nginx 默认 `proxy_buffering on` 会把整个响应缓完再转发，逐行下发就白做了。代理配置不在版本库里，只能由服务端主动声明。

`GET /api/v1/songs/{id}/link?quality=10&mid=&type=` 解析播放地址，返回**上游直链**：

```json
{ "code": 0, "data": { "songId": 97773, "url": "https://ws.stream.qqmusic.qq.com/...",
  "quality": 10, "requestedQuality": 12, "kbps": "1644kbps", "fallback": true } }
```

客户端直接拉 QQ 的 CDN，音频字节不再经过本服务。`quality` 是**实际拿到的**档位 —— 上游不会自动降级，阶梯是我们自己走的，`fallback` 为真表示发生了降级，客户端据此提示「这首只有 320kbps」。拿不到地址走 502，**绝不能 401**。

`GET /api/v1/songs/{id}/info?mid=` 给出歌曲信息与**真实存在**的音质档位（含各档字节数），已过滤掉 `size` 为 0 的档位。客户端的音质选择器用它只列出能选的档，并在下载前提示体积。

`GET /api/v1/songs/{id}/play?quality=10` 仍然保留：装机的旧客户端在用，也是新客户端解析失败时的兜底。支持 Range 断点续传（透传给上游并回写 206 与 `Content-Range`）；无 Range 时返回 200 全量。上游非 2xx 一律归成 502，**不透传上游的状态码** —— 上游的 401 会被客户端当成自己的令牌失效。

`GET /api/v1/songs/{id}/lyrics` 默认返回纯 LRC 文本；带 `format=json` 时返回 `{lrc, yrc, trans}`，其中 `yrc` 是逐字时间轴，格式为 `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`。

### 图片生成（需要访问令牌）

`POST /api/v1/draw/completions` 创建 `gpt-image-2` 图片生成任务。服务端从数据库
`api_key` 表读取 `GPTIMAGE2` 渠道的 Key，客户端不能直接接触第三方密钥。

Key 不存环境变量。同一渠道可放多条 Key，创建任务时优先使用剩余额度最高的一条：

```sql
INSERT INTO api_key (channel, key, quota)
VALUES ('GPTIMAGE2', '替换为真实Key', 100)
ON CONFLICT (channel, key) DO UPDATE SET quota = excluded.quota;
```

`quota` 是本地可创建任务次数，每次成功创建任务扣减 1；上游拒绝或连接失败时自动归还。
状态轮询不消耗额度。任务会记录提示词、消耗额度、渠道、状态、完成图片和创建时使用的
`api_key_id`，所以同渠道多 Key 或后续新增 Key 都不会让轮询查错。

```json
{
  "model": "gpt-image-2",
  "prompt": "一只可爱的猫咪在草地上玩耍",
  "aspectRatio": "1:1",
  "imageSize": "1K",
  "quality": "high",
  "images": ["https://example.com/image-1.png"]
}
```

`images` 最多 8 张且只接受 HTTP/HTTPS URL。成功响应沿用统一信封：

```json
{
  "code": 0,
  "message": "success",
  "data": { "taskId": "xxxxx", "status": "IN_PROGRESS" }
}
```

该接口按登录用户 10 次、来源地址 60 次/15 分钟限流。第三方 401/402 会转换为
502，不能原样透传成 401，否则客户端会把服务端密钥或余额问题误判为用户登录失效。

创建成功后，客户端每隔约 3 秒调用 `GET /api/v1/draw/result/{taskId}`。状态为
`IN_PROGRESS` 时继续轮询，`COMPLETED` 或 `FAILED` 时停止：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "taskId": "task_xxxxx",
    "state": "COMPLETED",
    "progress": 100,
    "createdAt": 1773894600,
    "completedAt": 1773894780,
    "result": { "imageUrl": "https://example.com/generated-image.png" },
    "error": null
  }
}
```

状态查询独立限流：按用户 300 次、来源地址 1800 次/15 分钟。第三方返回 404 时映射为
本服务的 404/4042；第三方 401 仍转换为 502，不能触发客户端令牌续期。

### 上游接口与音质降级

搜索与播放链接用 v3（`https://api.vkeys.cn/music/tencent`，路径里不带版本号前缀），歌词用 v2（`https://api.vkeys.cn/v2/music/tencent`）—— 只有 v2 的歌词接口同时给出逐字时间轴与翻译。

实测发现文档与实际不符，实现以实际响应为准：

- v3 搜索的封面字段是 `cover`，文档写的 `albumImage` 不存在
- v3 播放链接实际只返回 `songID` / `songMID` / `kbps` / `link` / `url`，文档里的歌名、歌手、封面、时长、音质全都没有，因此元信息全部取自搜索结果
- 成功码不统一：v3 用 `0`，v2 歌词用 `200`，两个都认
- v3 播放链接**不会自动降级**，付费歌曲请求 `quality=14` 可能返回 `code=110000`，因此实现了音质阶梯 `[14,11,10,8,4,0]`，最多试 4 档
- 上游会返回 `code=0` 但 `kbps=0kbps` 的死链，所以首字节探测放在阶梯循环**内部**：某一档给出死链时继续往下试，否则会白白放弃后面本来可用的低音质档位
- **`/song/info` 的 `qualityInfo[].type` 与 `/song/link` 的 `quality` 参数一一对应**（已用同一首歌逐档核对，返回的文件名完全一致），且 `size == 0` 与「这一档拿不到可用地址」严格对应。所以 `/link` 先问一次 `/song/info`，直接挑一个真实存在的档位 —— 最坏情况从 4 次请求降到 1 次，也不会再把请求打在必定失败的档位上。`info` 自己失败时退回音质阶梯
- 实测档位到 **18**（NAC），不是文档和旧版 README 写的 16
- 一处旧注释的误判：付费歌曲**并非**一律在 `quality=14` 失败 —— 实测付费歌的 14 档同样能拿到地址（6020kbps），报 `110000` 的真正原因是该档 `size` 为 0，与是否付费无关

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

各用途计数器互相独立 —— 更新检查和图片轮询都是周期性调用，与登录或图片创建共用会烧掉其它用途的额度。

| 用途 | 阈值 |
| --- | --- |
| 登录 / 注册 | 按来源地址 10 次 / 15 分钟 |
| 客户端引导与安装包下载 | 按设备号 60 次 + 按来源地址 900 次 / 15 分钟 |
| 发布管理 | 按来源地址 60 次 / 15 分钟 |
| 图片任务创建 | 按用户 10 次 + 按来源地址 60 次 / 15 分钟 |
| 图片任务轮询 | 按用户 300 次 + 按来源地址 1800 次 / 15 分钟 |

外层阈值放得宽，因为校园网、办公网等 NAT 环境下大量用户共用一个出口地址，按 IP 收紧会互相挤占。状态在进程内存中，重启即清空，也不跨实例共享。

## 契约验证

线上有已安装的客户端，且 `/api/v1/app/bootstrap` 本身就是推送修复的通道，改动后必须逐项核对响应形状。用**独立的验证库**（不是正式库）起一个实例：

```powershell
psql -U postgres -c "CREATE DATABASE music_verify"   # 只需一次
node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify

$env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
$env:PORT="4720"; $env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"; $env:ADMIN_TOKEN="verify-token"
npm run dev
# 另一个终端
node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
```

当前共 88 项，必须全绿。脚本会核对状态码、业务码、信封形状、NDJSON 行格式、字段类型（`data.id` 必须是 number、`songId` 必须是字符串、`createdAt` / `configVersion` / `apkSize` 必须是 number）、纯文本歌词、Range 行为，以及「无效令牌访问 bootstrap 仍返回 200」这类红线。

后面三组分别覆盖：PostgreSQL 迁移的四条硬规矩（并发注册、并发刷新同一令牌、停用版本不可下载、灰度分桶稳定）；搜索拆分后的新契约（60 首的耗时、`X-Accel-Buffering`、`favorited` / `vip` / `mid` 字段、批量收藏查询、`/link` 给的是上游直链、请求不存在的档位会降级、`/info` 过滤掉不存在的档位）；以及参数健壮性（`page=abc` 不会拼出 `page=NaN`、`quality=` 空串回落到 10 而不是 0）。

`reset-db.mjs` 会 `DROP SCHEMA public CASCADE`，所以它拒绝库名里不含 `verify` / `test` 的连接串 —— SQLite 时代「删掉那个文件」就够了，现在需要一个显式且带护栏的动作。

类型检查能抓住绝大部分同步改异步的漏改（漏 `await` 会得到 `Promise<T>` 与 `T` 不匹配），但**抓不住 `.find()` / `.map()` 回调里的漏改**，改动数据层后除了 `npx tsc --noEmit` 还要人工看一遍这些回调。
