# 桃桃音乐

Kotlin Multiplatform 音乐播放器 + 自建后端。当前只有 Android 客户端,`shared` 模块为后续扩展 iOS 预留。

- 开发约定(代码风格、模块划分、测试、签名)见 [AGENTS.md](AGENTS.md)
- **发布与热更新流程见 [RELEASE.md](RELEASE.md)** —— 推版本前务必先看
- 后端接口与数据层细节见 [server/README.md](server/README.md)
- 热更新的设计动机见 [HOT_UPDATE.md](HOT_UPDATE.md)

## 这个项目由三块组成

```
androidApp/   Android 应用：Compose 界面、Media3 播放、热更新、离线下载
shared/       跨平台层：歌曲模型、歌词解析、音质档位定义（有单元测试）
server/       NestJS 后端：账号、收藏、上游接口适配、热更新分发、管理后台
patch/        热修复补丁：不打进 APK，需要发补丁时单独编译成几 KB 的 DEX
build-logic/  热修复插桩插件：编译期给逻辑层方法插入补丁分发入口
```

客户端**不直接请求第三方音乐接口**,全部经过自己的后端。后端是接口适配层:媒体、图片、歌词只做实时转发或解析,PostgreSQL 只保存账号、刷新令牌哈希、收藏和发布记录,不存任何媒体内容。

## 一次播放会发生什么

理解这条链路基本就理解了整个项目:

1. **搜索** —— 客户端请求 `/api/v1/search?num=60`,后端打一次上游拿列表,把 60 首元信息逐行以 NDJSON 写出(含 `favorited` / `vip` / `mid`)。客户端收到一首渲染一首。本机实测 130–900ms(取决于上游缓存);线上从发起到首行约 1.2 秒(含建连与上游),首行之后 60 行在 18ms 内到齐。
2. **入队** —— 点击某首时,客户端把**占位地址** `/api/v1/songs/{id}/play?quality=N` 放进播放队列。这个地址永不过期,可以安全地持久化。
3. **取流** —— ExoPlayer 打开流的那一刻,`PlaybackService` 的 `ResolvingDataSource` 拿占位地址去问 `/api/v1/songs/{id}/link`,后端解析出**上游直链**并返回实际拿到的音质档位。
4. **播放** —— 客户端直接拉 QQ 的 CDN,音频字节**不经过我们的服务器**。解析失败就明确报错,不回退代理(回退会把流量悄悄绕回自己服务器,还会藏住"解析坏了"这件事)。
5. **歌词** —— `/api/v1/songs/{id}/lyrics?format=json` 同时给出行级 `lrc` 与逐字 `yrc`,客户端据此做逐字高亮。

搜索**刻意不解析播放地址**。早先每首都要额外要一次播放链接并探测首字节,20 首约 1.8 秒、60 首最坏 240 次上游请求,而客户端拿到后又会丢掉它 —— 那些请求换来的只是"过滤掉拿不到地址的歌"。现在改成点播时才解析。代价是付费/下架的歌会出现在结果里,所以搜索结果带 `vip` 标记,真正放不出来的在点播时提示。

## 主要功能

**播放** —— Media3 `MediaSessionService` 后台播放、锁屏与通知控制、拔耳机自动暂停、按曲目切换唤醒锁档位。播放指令统一由 `AudioPlayer` 通过 `MediaController` 下发。

**账号** —— 访问令牌 15 分钟、刷新令牌 30 天且刷新时轮换。令牌格式是 `base64url(payload).HMAC-SHA256`,刻意没有用 JWT 库 —— 换格式会让所有装机客户端的令牌立刻失效。密码用随机盐 + 高成本 scrypt。

**收藏** —— 搜索结果内联 `favorited`(一次批量查询),客户端本地缓存 + 乐观更新,服务端的值是权威值。

**音质** —— 播放与下载的默认音质分开设置(下载只花一次流量,可以选得更高)。播放页可临时切档。档位表来自上游 `/song/info`,只列出这首歌**真实存在**的档位并显示体积。

**离线下载** —— 音频、封面、行级歌词与逐字歌词一并落到应用私有目录。下载前可选音质并看到体积,带系统通知显示进度。

**热更新** —— 两条通道:整包 APK(支持灰度放量与强制更新下限)和**热修复补丁**(只含改动方法的 DEX,几 KB,立即生效不用重启,但只能改 `data`/`player`/`update` 包 —— Compose 界面不能插桩)。两者都能在**管理后台**里操作（浏览器打开服务的 `/admin/`），也都有对应的 curl 命令。详见 [RELEASE.md](RELEASE.md)。

**崩溃日志** —— 测试机无法连 adb,崩溃堆栈写到本地并可在「我的」页内查看、复制,或导出成 `.log` 交给系统分享(多条堆栈叠起来轻易上万字符,剪贴板装不下)。

## 开发

### 客户端

```powershell
.\gradlew.bat :androidApp:assembleDebug        # 调试包
.\gradlew.bat :androidApp:assembleRelease      # 发布包（验收标准）
.\gradlew.bat :shared:testDebugUnitTest        # 共享层单元测试
```

产物在 `androidApp/build/outputs/apk/release/`。**注意:两种 assemble 都会递增 `version.properties`**,细节见 [RELEASE.md](RELEASE.md) 的版本号铁律。

### 后端

需要本机有 PostgreSQL,并建好库:

```powershell
psql -U postgres -c "CREATE DATABASE music"
cd server
npm install
copy .env.example .env    # 然后填 DATABASE_URL、AUTH_SECRET、ADMIN_TOKEN
npm run dev
```

建表由服务启动时自动完成(幂等 DDL + 顾问锁),不需要手工跑迁移。

**管理后台**在同一个端口的 `/admin/` 路径上（`http://localhost:4500/admin/`），用来发版、放量、发补丁、调强制更新下限、查看用户听歌统计。服务根路径保留给未来网页版。改后台界面时用 `npm run dev:frontend` 起独立的 vite 服务器(5173)，它会把 `/api` 代理到本机后端；`npm run build` 会把后台一起编译进 `dist/public/`。

改完后端**必须跑契约验证**(当前 91 项):做法见 [RELEASE.md](RELEASE.md#四服务端发布)。线上有装机客户端,而 `/api/v1/app/bootstrap` 本身就是推修复的通道,坏掉就没有补救手段。

## 几个反复踩过的坑

留在这里避免重复付学费,完整清单见 [RELEASE.md](RELEASE.md)。

- **前台服务契约** —— `startForegroundService` 之后必须在几秒内调 `startForeground`,否则系统直接杀进程。media3 的 `onStartCommand` 会静默忽略自定义 action,当年那个"播放一段时间就闪退"就是这么来的。所以播放指令走 `MediaController`,而下载通知刻意**不用**前台服务。
- **`remember(key)` 与 `LaunchedEffect(key)` 的 key 必须一致** —— 不一致会出现"状态被清空、拉取逻辑却不重跑",表现成进度停在 0:00 或歌词空白。
- **新增整页要同步三处** —— `switchTab`、`AnimatedContent` 的 `targetState`、`BackHandler`。漏掉第一处的表现是"点了底部标签却还停在原页面"。
- **`LazyColumn` 会销毁滚出屏幕的项** —— item 内部的 `remember` 随之重置,入场动画于是每次滚回来都重播。需要"只播一次"的状态要提到列表外面。
- **Node 未处理的 promise rejection 会终止进程** —— 并发任务必须在**创建时**就挂上失败处理,留到循环里再 await 就晚了。同理 `pool.on("error")` 必须挂监听,否则数据库重启会直接带走 Node 进程。
- **`setGlobalPrefix` 的 `exclude` 不能写通配符** —— 为了让管理后台落在根路径而写成 `exclude: ["health", "/*"]`,会把**所有**路由都从 `/api/v1` 前缀里豁免掉,接口全线 404。静态资源要用 express 中间件在路由之前拦截,不要动全局前缀。
- **动态加载的 dex 必须先设只读** —— Android 14 起 `targetSdk ≥ 34` 的应用加载可写 dex 会抛 `SecurityException`。漏掉的表现是:下载、校验、落盘全部成功,只在加载那一刻失败,而异常被捕获后补丁被永久标记为失败 —— 不闪退、界面无变化、重启和重新放量都没用。
