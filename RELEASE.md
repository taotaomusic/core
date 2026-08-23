# 发布与热更新手册

这份文档记录**发布一个新版本的完整流程和每一个坑**。热更新通道是唯一能给装机客户端推修复的手段,它一旦被推坏就没有补救办法,所以每条规则都值得照着做。

后端接口细节见 [server/README.md](server/README.md),热更新的设计动机见 [HOT_UPDATE.md](HOT_UPDATE.md)。

---

## 一、版本号的三条铁律

版本号存在根目录 `version.properties`,由 `tools/Update-Version.ps1` 递增,而 `androidApp/build.gradle.kts` 把 `incrementVersion` 挂成了 `assembleDebug` / `assembleRelease` 的 `finalizedBy`。

### ① 版本号只能从 `output-metadata.json` 读,不能读 `version.properties`

递增发生在**构建结束之后**,所以构建一完成,`version.properties` 里的值就已经比刚产出的那个包大 1 了。

```powershell
# 正确：问构建产物自己是什么版本
node -e "const d=require('./androidApp/build/outputs/apk/release/output-metadata.json').elements[0];console.log(d.versionCode, d.versionName)"
```

登记错了的后果是客户端陷入死循环:提示有新版本 → 装完发现自己版本号就是那个 → 还提示有新版本。

### ② 绝不能回滚 `version.properties`

`incrementVersion` 对 **debug 构建也生效**,所以这个文件天然会跑在已发布版本前面,看起来"多跳了几号"是正常的,不要手工改回去,也不要 `git checkout` 它。

**这个坑真实发生过:** 有一次提交前把 `version.properties` 回滚了,下一次构建于是又产出同一个 versionCode,而登记接口是 `ON CONFLICT DO UPDATE` —— 它**静默覆盖**了已发布记录的 sha256。结果是已装该版本的客户端看到 `available=false`,更新推不出去;而还没下载完的客户端会因为 sha 不匹配而校验失败。

修复方式只能是往前发一个新版本,并把被污染的那一版停用:

```powershell
curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/rollout" `
  -H "content-type: application/json" -H "X-Admin-Token: $env:ADMIN_TOKEN" `
  -d '{"versionCode":<被污染的版本>,"percent":0,"enabled":false}'
```

### ③ 版本号跳号无所谓,重号是事故

客户端只比较 `versionCode` 的大小,中间空几号完全没影响。所以宁可跳号,也不要为了"连续"去复用一个号。

---

## 二、发布流程

### 1. 构建

```powershell
.\gradlew.bat :androidApp:assembleRelease
```

产物在 `androidApp/build/outputs/apk/release/androidApp-release.apk`。

Release 构建保持 `isMinifyEnabled = false`(见 AGENTS.md),签名配置读 `local.properties`,该文件不进版本库。

### 2. 登记(默认不放量)

请求体是 **APK 原始字节**,不是 base64、不是 multipart。`rollout` 不传就是 0,也就是"登记但不下发给任何人"—— 这是刻意的默认值,给你留一个先自测的机会。

```powershell
$apk = "androidApp\build\outputs\apk\release\androidApp-release.apk"
$meta = node -e "const d=require('./androidApp/build/outputs/apk/release/output-metadata.json').elements[0];console.log(d.versionCode+' '+d.versionName)"
$vc, $vn = $meta.Split(' ')
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash.ToLower()

curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/releases?versionCode=$vc&versionName=$vn&note=修复内容&rollout=0&sha256=$sha" `
  -H "X-Admin-Token: $env:ADMIN_TOKEN" --data-binary "@$apk"
```

**一定要传 `sha256`。** 服务端会边写盘边算哈希并与之比对,不一致直接拒绝(4006)。不传就等于放弃了这道校验,坏包会一路发到用户手上。

### 3. 验证下载路径

放量之前先确认包真的能下、能续传:

```powershell
# 206 与 Content-Range
curl.exe -D - -o NUL -H "Range: bytes=100-199" "https://music.xydaigua.cn/api/v1/app/apk/$vc"
# 越界应为 416
curl.exe -o NUL -w "%{http_code}`n" -H "Range: bytes=99999999-" "https://music.xydaigua.cn/api/v1/app/apk/$vc"
# 全量下载后核对 sha256
```

### 4. 放量

```powershell
curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/rollout" `
  -H "content-type: application/json" -H "X-Admin-Token: $env:ADMIN_TOKEN" `
  -d "{\"versionCode\":$vc,\"percent\":10}"
```

灰度命中条件是 `sha1(发布ID + ":" + 主体标识) % 100 < rollout_percent`。混入发布 ID 是为了让每个版本得到互相独立的分桶,否则永远是同一批用户当小白鼠;用哈希而不是随机数是为了对同一用户稳定,否则更新提示会时有时无。

**重新上传同一个 versionCode 时 `rollout_percent` 会被保留**(`ON CONFLICT DO UPDATE` 里刻意没有它),所以覆盖发布不会把已经放到 100% 的版本打回 0。改写那段 SQL 时别顺手补上。

### 5. 确认能被看到

```powershell
curl.exe "https://music.xydaigua.cn/api/v1/app/bootstrap?versionCode=<上一个版本>&sdk=36&deviceId=check"
```

`update.available` 必须是 `true`,且 `versionCode` / `apkUrl` / `apkSha256` 齐全。

### 6. 提交

`version.properties` **要一起提交**(见铁律 ②)。

---

## 三、强制更新

抬高最低可用版本,低于它的客户端会被判定为强制更新:

```powershell
curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/min-version" `
  -H "content-type: application/json" -H "X-Admin-Token: $env:ADMIN_TOKEN" `
  -d '{"versionCode":63}'
```

接口内置守卫:**必须已经存在放量 100% 且版本号不低于该下限的发布**,否则返回 409/4091。这是为了堵住一条变砖路径 —— 强制更新的客户端只会收到全量版本,若不存在这样的版本,它们会被拦在门外却拿不到升级包。

所以顺序永远是:**先把目标版本放到 100%,再抬高下限。**

---

## 四、服务端发布

产物是 `dist/` 目录树,不是单文件。

```powershell
cd server
npm run build
# 上传 dist/ 与 package-lock.json，在 dist 同级执行：
npm install --omit=dev
node dist/main.js
```

几条容易忘的:

- **PostgreSQL 必须先于本服务启动。** 启动时有 10 次 × 1 秒的连接重试兜底,超过就退出交给进程管理器;systemd 建议加 `After=postgresql.service`。
- **`DATABASE_URL` 没有默认值**,缺失或不是 `postgres://` 开头会启动即失败。
- **不能用 esbuild / tsx 构建或跑开发。** 它们不支持 `emitDecoratorMetadata`,NestJS 的构造器注入拿不到 `design:paramtypes`,启动时报 `Cannot read properties of undefined`。构建用 `tsc`,开发用 `ts-node`。
- **改动后必须跑契约脚本**,当前 82 项要全绿:

  ```powershell
  cd server
  psql -U postgres -c "CREATE DATABASE music_verify"   # 只需一次
  node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
  # 用验证库起一个实例后：
  node tools/verify-contract.mjs http://127.0.0.1:4720 verify-token
  ```

  **重置数据库后必须重启服务**:建表只在启动时执行一次,先重置再让旧进程继续跑,会得到一堆"关系不存在"。

- 部署前**备份线上现有产物**,`bootstrap` 一旦异常立刻回滚。

---

## 五、绝对不能破的客户端契约

线上有装机客户端,以下每一条都能悄无声息地弄坏线上功能。改后端时逐条核对。

| 规则 | 破坏后的表现 |
| --- | --- |
| 访问令牌无效必须返回 **401,不能 403** | 客户端只对 401 触发续期重放,403 会让所有接口失去自动续期 |
| `/auth/refresh` 只有"令牌真无效"才能返回 4xx | 4xx 会让客户端清空会话回登录页,DB 或内部故障必须落 5xx |
| `/app/bootstrap` **永远不能返回 401** | 客户端刻意带着**已过期**的令牌调它;返回 401 会让热更新通道静默失效且无任何报错 |
| 业务错误不能借用 401 | 「没有歌词」「地址解析失败」走 502;借用 401 会触发重放并把用户踢回登录页 |
| `/search` 必须是**裸 NDJSON**,不能套信封 | 客户端取不到 `type` → 0 首歌 + 无任何错误提示,表现为「搜不到东西」 |
| 歌词默认必须是 `text/plain` 裸文本 | 旧客户端把响应体直接当歌词展示;且它的 `Accept` 不含 `text/plain`,不能启用严格内容协商 |
| 错误响应必须含**顶层字符串** `message` | NestJS 校验失败时 `message` 是数组,会被原样显示成 `["..."]` |
| `accessToken`/`refreshToken` 与 `user` 必须**平铺**在 `data` 下 | 客户端用 `getString` 硬取,包一层就抛异常 |
| 搜索结果 `data.id` 必须是 JSON **number**;收藏 `songId` 必须是**字符串** | 类型漂移会让 `remoteId=null`,该歌不可播、不可收藏、无歌词 |
| SQL 别名必须加双引号(`AS "songId"`) | PostgreSQL 折叠成小写 `songid`,客户端读不到 → 所有歌显示未收藏,无报错 |
| 存 `Date.now()` 的列用 `bigint`,其余整数用 `integer`,并注册 `INT8 → Number` 解析器 | pg 默认把 int8 回传成**字符串**,`createdAt`/`apkSize` 会从 number 静默漂成 string |
| `app_release.enabled` 是 `smallint` 0/1,**不是 boolean** | 代码里一处是 `=== 1` 一处是真值判断,改 boolean 会让强制更新守卫永远 409 |
| `coverUrl` / `apkUrl` 必须是**绝对 https 且免鉴权** | 客户端直接交给图片库和下载器,不带 Authorization;明文 HTTP 被系统拦截 |
| APK 的 `apkSize` 必须与文件字节数严格一致 | 客户端有个 416 死锁缺陷:分片长度恰好等于全长时会永久卡住 |

---

## 六、客户端侧的两条易忘规则

- **新增整页时要同步三处**:`switchTab`(底部标签切换时收起)、`AnimatedContent` 的 `targetState`、`BackHandler`。漏掉 `switchTab` 的表现是"点了底部标签却还停在原页面"—— 这个坑踩过两次。
- **队列里只存永不过期的占位地址** `/api/v1/songs/{id}/play?quality=N`,上游直链在 `ResolvingDataSource` 里取流那一刻才换上。直链是限时的,存进 `MediaItem` 或持久化队列后冷启动恢复时就失效了;而占位地址还顺带保证了 `AudioPlayer` 的 `queueHasAllAudio` 判断不会把整条队列塌陷成单首。
