# 桃桃音乐后端服务

Node.js 20 + TypeScript 实现。媒体、图片和歌词仍然只做实时转发；SQLite 仅保存用户账号、密码哈希和基础账号信息，不保存媒体内容。

## 启动

```powershell
npm install
npm run dev
```

生产构建：

```powershell
npm run build
npm start
```

部署时需要同时上传 `dist/app.js` 和 `dist/package.json`，并在同目录执行 `npm install --omit=dev`，因为 SQLite 原生模块 `better-sqlite3` 不会被打进单文件。

构建后的 `dist/app.js` 会打包、压缩并移除注释；`src/` 源码保持正常可读格式。

开发模式使用 `tsx watch`，修改 `src/` 下的 TypeScript 文件后会自动重启服务；如果修改了 `package.json`，需要手动重新运行一次命令。

## 接口

- `GET /health`
- `GET /api/search?word=歌曲名`
- `GET /api/song/{id}`
- `GET /api/media?url=媒体地址`

默认端口为 `4500`。

数据库默认保存到 `data/music.sqlite`，可通过环境变量 `DATABASE_PATH` 修改。生产环境必须设置随机的 `AUTH_SECRET`。

用户接口：

- `POST /api/v1/auth/register`，JSON：`{"username":"用户名","password":"至少6位密码"}`
- `POST /api/v1/auth/login`，JSON：`{"username":"用户名","password":"密码"}`
- `POST /api/v1/auth/refresh`，JSON：`{"refreshToken":"刷新令牌"}`
- `POST /api/v1/auth/logout`，JSON：`{"refreshToken":"刷新令牌"}`
- `GET /api/v1/auth/me`，请求头：`Authorization: Bearer <accessToken>`

收藏接口均需要 `Authorization: Bearer <accessToken>`：

- `POST /api/v1/favorites/tencent/105648974`：收藏腾讯歌曲
- `DELETE /api/v1/favorites/tencent/105648974`：取消收藏
- `GET /api/v1/favorites`：获取当前用户收藏列表

除注册、登录、刷新令牌、注销和 `/health` 外，搜索、播放、歌词、收藏及用户信息接口均必须携带访问令牌。

密码使用随机盐和高成本 scrypt 哈希，不保存明文密码。访问令牌有效期为 15 分钟，刷新令牌有效期为 30 天；刷新令牌只保存 SHA-256 哈希，刷新时会轮换，注销后立即失效。登录和注册接口按来源地址限流。

搜索适配接口：`GET /api/v1/search?keyword=歌曲名&page=1&num=10&quality=10`，其中 `page` 默认 1，`num` 默认 10，范围为 1–60，`quality` 默认 10，范围为 0–16。接口返回 NDJSON 流，每行一个 `{ "type": "song" }`，最后一行为 `{ "type": "end" }`。

搜索结果会遍历每首歌曲的 ID，调用 `geturl` 接口补齐指定品质的真实播放地址。播放流接口：`GET /api/v1/songs/{id}/play?quality=10`。

歌词接口：`GET /api/v1/songs/{id}/lyrics`，返回 LRC 文本；没有歌词时返回错误，不保存歌词内容。

## 配置

服务启动时会读取同目录的 `.env`（已存在的环境变量优先），可参考 `.env.example`。`.env` 保存密钥，不要提交到版本库。

| 变量 | 说明 |
| --- | --- |
| `PORT` | 监听端口，默认 `4500` |
| `DATABASE_PATH` | SQLite 路径，默认 `./data/music.sqlite` |
| `AUTH_SECRET` | 访问令牌签名密钥，生产环境必须为至少 32 位随机值 |
| `ADMIN_TOKEN` | 发布管理令牌，留空则管理接口全部关闭 |
| `APK_DIR` | APK 存放目录，默认 `./data/apk` |
| `DEFAULT_CHANNEL` | 默认渠道，默认 `release` |
| `PUBLIC_BASE_URL` | 对外基地址，用于拼装 APK 下载地址 |

## 热更新接口

设计说明见项目根目录 `HOT_UPDATE.md`。

### 客户端接口（免鉴权）

这两个接口注册在访问令牌门禁之前。原因是最需要强制更新的场景恰恰是「上一个版本把登录搞坏了」，若检查接口自己要求登录，这些客户端永远收不到升级通知。

- `GET /api/v1/app/bootstrap?versionCode=54&sdk=36&deviceId=<uuid>&channel=release`

  一次返回更新信息与远程配置。`Authorization: Bearer <accessToken>` 可选：带了且有效就按用户做灰度分桶，未登录时退回 `deviceId`。

  ```json
  {
    "code": 0,
    "data": {
      "update": {
        "available": true, "forced": false,
        "versionCode": 60, "versionName": "1.0.59",
        "apkUrl": "https://music.xydaigua.cn/api/v1/app/apk/60",
        "apkSize": 14134477, "apkSha256": "4c2626…",
        "releaseNote": "修复长时间播放闪退",
        "minSupportedVersionCode": 55
      },
      "config": { "feedback.enabled": "true" },
      "configVersion": 1787387947529
    }
  }
  ```

- `GET /api/v1/app/apk/{versionCode}`：下载安装包，支持 `Range` 断点续传（206 / `Content-Range`），越界返回 416，`ETag` 为 APK 的 sha256。

### 发布管理接口

全部需要请求头 `X-Admin-Token: <ADMIN_TOKEN>`。

- `GET /api/v1/app/admin/releases?channel=release`：列出发布记录
- `POST /api/v1/app/admin/releases?versionCode=&versionName=&note=&rollout=&minSdk=&sha256=`

  请求体为 **APK 原始字节**（不是 base64、不是 multipart），服务端边写盘边算 sha256。`rollout` 默认 0 表示先登记不放量；传 `sha256` 时会校验一致性。

  ```powershell
  curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/releases?versionCode=54&versionName=1.0.53&note=修复闪退&rollout=0" `
    -H "X-Admin-Token: $env:ADMIN_TOKEN" `
    --data-binary "@androidApp/build/outputs/apk/release/androidApp-release.apk"
  ```

  `versionCode` 和 `versionName` 必须取自 `androidApp/build/outputs/apk/release/output-metadata.json`，**不能读 `version.properties`**：`incrementVersion` 是 `assemble` 的 `finalizedBy`，构建结束时该文件里的值已经比刚产出的 APK 大 1，登记错了客户端会陷入「提示更新 → 装完还提示」的死循环。

- `POST /api/v1/app/admin/rollout`，JSON `{"versionCode":54,"percent":10}`：调整放量比例，可选 `enabled` 下架某个版本
- `POST /api/v1/app/admin/min-version`，JSON `{"versionCode":54}`：抬高最低可用版本（强制更新下限）

  内置守卫：必须已存在放量 100% 且版本号不低于目标下限的发布，否则返回 409 拒绝执行。这是为了堵住变砖路径——被判定为强制更新的客户端只会收到全量版本，若不存在这样的版本，它们会被拦在门外却拿不到升级包。

- `GET /api/v1/app/admin/config`：列出全部远程配置
- `POST /api/v1/app/admin/config`，JSON `{"key":"feedback.enabled","value":"true","minVersionCode":70}`：写入配置，`value` 传 `null` 表示删除。`minVersionCode` / `maxVersionCode` 用于让配置只对特定版本区间生效。

### 灰度分桶

命中条件为 `sha1(发布ID + ":" + 主体标识)` 前 4 字节取模 100 后小于 `rollout_percent`。

混入发布 ID 是为了让每个版本得到互相独立的分桶，否则永远是同一批用户当小白鼠；用哈希而非随机数是为了对同一用户稳定，否则每次检查结果都会翻转，更新提示时有时无。实测 20000 个设备号在 10% / 30% / 50% 三档下的命中率为 9.60% / 29.93% / 50.46%。

### 限流

更新检查与登录使用独立计数器——更新检查是周期性调用，共用会烧掉用户的登录额度，表现成「登录提示请求过于频繁」。更新检查分两层：按设备号 60 次 / 15 分钟，按来源地址 900 次 / 15 分钟（放宽是因为 NAT 环境下大量用户共用出口地址）。管理接口按来源地址 60 次 / 15 分钟。

