# 发布与热更新手册

这份文档记录**发布一个新版本的完整流程和每一个坑**。热更新通道是唯一能给装机客户端推修复的手段,它一旦被推坏就没有补救办法,所以每条规则都值得照着做。

后端接口细节见 [server/README.md](server/README.md),热更新的设计动机见 [HOT_UPDATE.md](HOT_UPDATE.md)。

---

## 一、两种更新方式

| | 整包更新 | 热修复补丁 |
| --- | --- | --- |
| 内容 | 完整 APK（约 14MB） | 只含改动方法的 DEX（几 KB） |
| 生效 | 用户确认安装 → 重启 | **立即生效，不用重启** |
| 能改什么 | 全部 | **只有 `data` / `player` / `update` 包** |
| 用在 | 功能、界面、依赖升级 | 逻辑 bug 的紧急止血 |

**界面代码不能热修。** Compose 的可组合函数依赖 `startRestartGroup` / `endRestartGroup` 严格配对,在函数开头插入提前 return 会破坏组结构,所以 `ui/` 包刻意没有插桩。UI bug 只能发整包。

**有整包更新时服务端不下发补丁** —— 既然能装新版本就没必要打补丁,补丁只给"来不及发版或用户还没升级"兜底。

### 两种操作方式:管理后台 或 curl

下面所有发布动作都有两条路。**日常用管理后台**(浏览器打开 `https://music.xydaigua.cn/admin/`,注意入口固定在 `/admin/`,服务根路径留给未来的网页版),它把三件事做成了界面:发布列表与放量、补丁列表与放量、强制更新下限。首次进入用**管理员账号密码登录**,登录后拿到的会话令牌存在浏览器 localStorage,有效期 24 小时。

管理后台登录后直接就能操作发布和公告接口 —— 这些接口用的是同一个 `AdminAuthGuard`,只认管理员会话。账号、2FA、角色、强制改密和 LDAP 见 [server/wiki/11-admin-auth.md](server/wiki/11-admin-auth.md)。

本文档保留 curl 版本,因为它们是**唯一能写进脚本、也唯一能在后台挂掉时兜底**的口径。两者打的是同一批接口。

**curl 版本先取一个管理员会话。** 历史上这里用的是静态 `ADMIN_TOKEN`,该通道**已整体移除**(它固定 `super_admin`,同时绕过 2FA、IP 白名单、会话撤销和审计归属)。现在统一改为登录换会话:

```powershell
$login = curl.exe -s -X POST "https://music.xydaigua.cn/api/v1/admin/auth/login" `
  -H "content-type: application/json" `
  -d '{"username":"admin","password":"<管理员口令>"}' | ConvertFrom-Json
$env:ADMIN_SESSION_TOKEN = $login.data.token
```

下面所有 `curl.exe` 都用 `-H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN"`。会话 24 小时过期,过期后重新登录即可。若账号开了 2FA,登录会返回 `temp_token` 而不是会话,需要再走一步 `POST /api/v1/admin/auth/totp-verify`。

后台的实现要点见 [server/README.md](server/README.md#管理后台) —— 有一条不能踩的:静态资源靠 express 中间件在路由之前拦截,**绝不能改 `setGlobalPrefix` 的 `exclude`** 去让前端路由生效。

---

## 二、版本号的三条铁律

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
  -H "content-type: application/json" -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  -d '{"versionCode":<被污染的版本>,"percent":0,"enabled":false}'
```

### ③ 版本号跳号无所谓,重号是事故

客户端只比较 `versionCode` 的大小,中间空几号完全没影响。所以宁可跳号,也不要为了"连续"去复用一个号。

---

## 三、整包发布流程

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
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" --data-binary "@$apk"
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
  -H "content-type: application/json" -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
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

## 四、强制更新

抬高最低可用版本,低于它的客户端会被判定为强制更新:

```powershell
curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/min-version" `
  -H "content-type: application/json" -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  -d '{"versionCode":63}'
```

接口内置守卫:**必须已经存在放量 100% 且版本号不低于该下限的发布**,否则返回 409/4091。这是为了堵住一条变砖路径 —— 强制更新的客户端只会收到全量版本,若不存在这样的版本,它们会被拦在门外却拿不到升级包。

所以顺序永远是:**先把目标版本放到 100%,再抬高下限。**

---

## 五、热修复补丁流程

补丁只能改 `data` / `player` / `update` 包里的方法。开工前先确认目标方法真的被插过桩:

```powershell
.\gradlew.bat :patch:printMethodKeys -Pfilter=TencentMusicApi
```

它直接从 release APK 的 DEX 里读,是唯一不会说谎的口径 —— 源码里有的方法不一定插了桩(构造器、静态初始化块、合成方法都跳过了)。

### 1. 写补丁

改 `patch/src/main/kotlin/com/taotao/music/hotfix/generated/PatchEntryImpl.kt`:

- `targets()` 列出要接管的类(全名,点号形式)
- `isSupport()` 认领方法键,格式就是上一步打印出来的那种
- `dispatch()` 写新实现。`receiver` 是实例方法的 this(静态方法为 null),`args` 是实参且基本类型已装箱

**四条不能踩的:**

1. **不要在 `dispatch` 里调用被自己接管的那个方法** —— 插桩的判断在方法入口,调用它会再次进到这里,无限递归直接 StackOverflow。要原逻辑就自己重写一遍。
2. **不要 new 宿主已有的类并跨边界传递** —— 补丁类加载器加载的同名类与宿主的不是同一个 `Class`,传参会 `ClassCastException`。引用宿主类型只做类型声明(`compileOnly` 已保证不打进补丁),实例一律用传进来的那个。
3. **不要改方法签名** —— 补丁是按宿主那份代码的签名生成的,签名变了匹配不上。
4. **改完补丁要同步改源码** —— 补丁只是让线上先不崩,下一个整包版本里真正的修复必须在原位置也做一遍,否则升级后 bug 回归。

### 2. 生成

补丁要按**已发布的那个 APK** 编译,所以顺序不能颠倒:

```powershell
# 补丁引用宿主类型，必须先有宿主的编译产物
.\gradlew.bat :androidApp:assembleRelease
.\gradlew.bat :patch:buildPatch -PpatchVersion=1
```

产物在 `patch/build/patch/patch-1.apk`,几 KB。

**核对它没把宿主类打进去**(打进去会 `ClassCastException`):补丁包里应该只有 `PatchEntryImpl` 及其内部类,加上对 `PatchDispatcher` / `PatchEntry` 两个接口的引用。

### 3. 登记与放量

`targetVersionCode` 必须是**补丁基于的那个已发布版本**,服务端会校验它真的发布过:

```powershell
$patch = "patch\build\patch\patch-1.apk"
$sha = (Get-FileHash $patch -Algorithm SHA256).Hash.ToLower()

curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/patches?targetVersionCode=71&patchVersion=1&note=修复内容&rollout=0&sha256=$sha" `
  -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" --data-binary "@$patch"

# 自测通过后放量
curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/patch-rollout" `
  -H "content-type: application/json" -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  -d '{"targetVersionCode":71,"patchVersion":1,"percent":10}'
```

出问题紧急下架(客户端下次检查就不再拿到,已装上的会在下次启动时因为宿主版本或失败记录而失效):

```powershell
curl.exe -X POST "https://music.xydaigua.cn/api/v1/app/admin/patch-rollout" `
  -H "content-type: application/json" -H "Authorization: Bearer $env:ADMIN_SESSION_TOKEN" `
  -d '{"targetVersionCode":71,"patchVersion":1,"percent":0,"enabled":false}'
```

### 4. 自愈机制(为什么敢上线)

唯一的修复通道本身就是更新通道,所以补丁必须能自己退回去:

- 加载补丁**之前**把尝试计数 +1 并**同步写盘**(写在加载之前,否则加载即崩就丢了)
- 应用平稳跑起来(界面组合完成再等 5 秒)后清零
- 启动时发现计数不为 0,说明上次加载后没能平稳运行 → **直接弃用该补丁并删文件**
- 失败过的补丁版本会被记住,不再重试,避免「下载 → 崩 → 回滚 → 再下载」的循环
- 宿主 versionCode 与补丁登记的目标版本必须严格相等,升级后旧补丁自动失效

所以最坏情况是崩一次就自动回滚,而不是反复崩。

**这套机制有两处踩过的坑,改动时别再踩回去:**

**① 「确认」不能是一次性的启动定时器。** 那个 5 秒延迟的效果 key 必须挂在 `activePatchVersion` 上(而且它必须是 Compose 状态),不能用 `Unit`。用 `Unit` 的话它只在启动时烧一次,而补丁往往是**会话中途**装上的 —— 那时 `install(pendingAttempt = true)` 把计数置成 1,却再也没有东西会清零,于是下次启动必然被判成"加载后启动失败"而回滚。症状是「点了能生效,一重启就没了」。

**② 失败记录必须连宿主版本一起存。** 补丁号是按宿主版本**各自从 1 开始**编的,vc71 的 v1 和 vc72 的 v1 是两个完全不同的补丁。而 SharedPreferences 跨升级保留,所以只记版本号会让旧宿主上失败过的 v1 把新宿主上同名的 v1 一并拦掉 —— **每次升级都会撞**。症状是「新版本装上了,但补丁必须先手动清一次失败记录才装得上」。

### 5. 补丁 dex 必须是只读的(Android 14+)

**这条踩过,而且症状极其隐蔽。**

Android 14(API 34)的「Safer dynamic code loading」规定:`targetSdk ≥ 34` 时,所有动态加载的 dex/jar/apk **必须先标记只读**,否则构造类加载器时直接抛

```
SecurityException: Writable dex file '...' is not allowed
```

本项目 `targetSdk = 35`,所以这是硬性要求,不是可选的加固。`HotfixInstaller` 落盘后调 `setReadOnly()`,`HotfixLoader.load` 再兜一次底(旧版本装下的补丁文件可能还是可写的)。

**为什么难查:** 下载、sha256 校验、`renameTo` 落盘全部成功,只在加载那一刻抛异常。异常被 `applyNow` 捕获后走 `store.disable()`,补丁被记为「失败过」而**永不重试**。表现是:不闪退、界面无变化、重启也没用、重新放量也没用。

### 6. 补丁没生效时去哪里看

**设置 → 热修复**。这张卡片显示本机版本号、补丁状态、补丁目标版本,以及链路最近一次的结论(人能直接读的一句话)。

加它的原因:整条链路原本只往 logcat 写日志,而测试机连不上 adb。四种失败原因(版本号不匹配、下载失败、校验不符、加载抛异常)在界面上长得一模一样,只能靠猜。**每一个 `return false` 分支都必须 `store.note()` 一句话** —— 静默返回是这套机制最难排查的地方。

卡在「失败过所以不再重试」时,这张卡片上会出现**清除失败记录并重试**按钮 —— `failedPatchVersion` 原本没有任何清除入口,一次偶发失败会让补丁永久装不上。

---

## 六、服务端发布

产物是 `dist/` 目录树,不是单文件。**`dist/public/` 是管理后台的构建产物,必须一起上传**,否则 `/admin/` 打开是 404(接口不受影响)。

```powershell
cd server
npm run build          # 先 tsc 再 vite build，两个产物都进 dist/
# 上传 dist/ 与 package-lock.json，在 dist 同级执行：
npm install --omit=dev
node dist/main.js
```

几条容易忘的:

- **PostgreSQL 必须先于本服务启动。** 启动时有 10 次 × 1 秒的连接重试兜底,超过就退出交给进程管理器;systemd 建议加 `After=postgresql.service`。
- **`DATABASE_URL` 没有默认值**,缺失或不是 `postgres://` 开头会启动即失败。
- **不能用 esbuild / tsx 构建或跑开发。** 它们不支持 `emitDecoratorMetadata`,NestJS 的构造器注入拿不到 `design:paramtypes`,启动时报 `Cannot read properties of undefined`。构建用 `tsc`,开发用 `ts-node`。
- **`vue` / `element-plus` 在 `devDependencies` 里,这是刻意的。** 管理后台编译成自包含的静态文件,运行时不需要它们;放进 `dependencies` 会让服务器白装一套前端库。
- **改动后必须跑契约脚本**，以脚本实际输出为准，全部要全绿：

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

## 七、绝对不能破的客户端契约

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

## 八、客户端侧的易忘规则

- **新增整页时要同步三处**:`switchTab`(底部标签切换时收起)、`AnimatedContent` 的 `targetState`、`BackHandler`。漏掉 `switchTab` 的表现是"点了底部标签却还停在原页面"—— 这个坑踩过两次。
- **队列里只存永不过期的占位地址** `/api/v1/songs/{id}/play?quality=N`,上游直链在 `ResolvingDataSource` 里取流那一刻才换上。直链是限时的,存进 `MediaItem` 或持久化队列后冷启动恢复时就失效了;而占位地址还顺带保证了 `AudioPlayer` 的 `queueHasAllAudio` 判断不会把整条队列塌陷成单首。

---

## 九、Windows 模块化桌面版发布

Windows 客户端按 `current/` 模块目录发布。稳定启动器负责启动和失败回滚,更新器只在应用退出后替换文件；应用本身只检查清单、续传文件并生成更新计划。发布清单按模块的 SHA-256 寻址,服务端会自动为相邻版本生成 bsdiff,配置 `COURGETTE_PATH` 后对 DLL/EXE 优先使用 Courgette。

### 1. 生成便携更新包

```powershell
.\gradlew.bat :desktopApp:packageDesktopUpdateBundle
```

产物在 `desktopApp/build/desktop-update-bundle/`,包含 `current/`、精简 JRE、`taotao-launcher.jar`、`taotao-updater.jar`、JDK `jpackage` 生成的 `launcher.exe`/`updater.exe` 和 `manifest.json`。若已安装 GraalVM Native Image,也可执行 `:desktopLauncher:nativeLauncher` 与 `:desktopUpdater:nativeUpdater` 生成更小的原生入口；没有 GraalVM 时不影响包的使用。

### 2. 上传和登记

管理台选择该目录即可完成逐模块校验、内容寻址上传和清单登记；命令行可使用：

```powershell
$env:DESKTOP_RELEASE_BASE_URL = "https://music.xydaigua.cn"
$env:ADMIN_SESSION_TOKEN = "<管理员会话令牌，取法见第一节>"
.\gradlew.bat :desktopApp:publishDesktopRelease
```

默认渠道是 `release`，可用 `-PdesktopChannel=beta` 生成其他渠道。登记默认放量 0，先用 `/api/v1/desktop/bootstrap` 自测，再通过管理台或 `POST /api/v1/desktop/admin/rollout` 灰度放量。`POST /api/v1/desktop/admin/min-version` 与 Android 使用同一条规则：抬高下限前必须已有版本号不低于该值、放量 100% 且包含完整模块清单的救援版本。

### 3. 客户端回滚

更新器替换前把 `current/` 移到 `backup/` 并在注册表 `HKCU\\Software\\TaotaoMusic\UpdateAttempts` 写入 1；启动器发现启动未稳定完成时自动恢复 `backup/`。应用稳定运行后会清零计数。不要手工删除 `backup/` 或更新计划文件，排障时先停用对应发布再保留现场文件。
