# 契约验证指南

[返回文档中心](README.md)

最后更新:2026-09-30

后端改动的验收标准是 `tools/verify-contract.mjs` 全绿(检查项数量随脚本版本变化,以实际输出为准,当前约 300 项)。本文讲验证库准备、验证实例启动、脚本运行和失败定位。数据层改动**必须**走完这里的流程;仅改文档或注释可以跳过。

## 1. 验证脚本是什么

`tools/verify-contract.mjs` 对一个运行中的服务实例执行全部客户端契约断言:响应信封、错误码、NDJSON 流、Range 下载、管理端角色与审计、CSP/CORS、头像魔数等。脚本只读取第二个参数(base URL),不需要也不接受其它参数。

脚本**有状态**:必须「重置验证库 → 重启服务 → 单次运行」,中间重跑或共享实例会出现因果污染。

## 2. 准备验证库

创建独立数据库(只需一次):

```powershell
psql -U postgres -c "CREATE DATABASE music_verify"
```

每次验证前重置:

```powershell
node tools/reset-db.mjs postgres://postgres:密码@localhost:5432/music_verify
```

脚本会删除整个 `public` schema,只允许数据库名包含 `verify` 或 `test`。**不要把正式连接串复制到该命令。**

## 3. 启动验证实例

```powershell
$env:DATABASE_URL="postgres://postgres:密码@localhost:5432/music_verify"
$env:PORT="4720"
$env:APK_DIR="./tmp/apk"
$env:AUTH_SECRET="0123456789012345678901234567890123456789"
$env:ADMIN_INITIAL_PASSWORD="verify-initial-123456"
$env:NODE_ENV="test"; $env:EMAIL_VERIFICATION_TEST_CODE="123456"
$env:IM_ENABLED="false"; $env:CORS_ALLOWED_ORIGINS="https://verify.example"
$env:ADMIN_RATE_LIMIT="1000"   # 不调大会在验证中途吃 4290 假失败
npm run build:frontend     # /admin 下的 CSP 与主题脚本断言需要 dist/public
npm run build:web-player   # 分享页缓存头断言需要 dist/share-player
npm run dev
```

每个环境变量都有理由,漏一个就会假失败:

- `ADMIN_RATE_LIMIT=1000`:脚本一次运行要打上百次管理接口,默认 60 次/15 分钟会在中途命中 4290,失败位置随请求顺序漂移,看起来像业务坏了。
- `IM_ENABLED=false` 必须显式设置(`.env` 里通常是 `true`),否则「未启用 IM 时会话入口返回 503/5031」会变成 502/5020。
- `CORS_ALLOWED_ORIGINS` 要包含 `https://verify.example`,否则跨域白名单那条断言会失败。
- `ADMIN_INITIAL_PASSWORD`:契约脚本第一段用它完成首登、改密、再重登,拿后续断言要用的管理会话。**脚本读的是脚本自己环境里的这个变量,必须与服务启动时给的值一致。**

## 4. 运行脚本

另一个终端执行:

```powershell
node tools/verify-contract.mjs http://127.0.0.1:4720
```

以验证脚本的实际汇总数量为准,必须全部通过。

**脚本自己也要能读到 `EMAIL_VERIFICATION_TEST_CODE`(与服务相同的 6 位数字值)和 `ADMIN_INITIAL_PASSWORD`**:两者在脚本启动时就会读取,缺失或与启动环境不一致会直接抛错、或导致后续管理端断言连锁失败。「另一个终端」如果确实没有继承这些变量,先手动 `$env:` 设置再跑。

验证实例首次启动时会自动创建默认管理员 `admin`(`super_admin`),口令取 `ADMIN_INITIAL_PASSWORD`(未设置则随机生成并只打印一次),并带「首次登录必须改密」标记。

**注意**:验证库每次被 `reset-db.mjs` 清空后都要**重启服务**,让 `AdminBootstrapService` 重新创建这个账号,否则相关断言会因为登不进去而失败。

## 5. 管理端断言的顺序敏感性

脚本中「管理端会话准备:强制改密与 Bearer 会话」一段必须在所有管理端断言之前跑;「管理路由逐条无凭据探测」枚举约 44 条受保护路由(`guardedAdminRoutes`,覆盖 `/admin/auth/**` 与 `/app/admin/**` 的 `@AdminGuarded()` 方法,open-api-keys 走后面的读写角色断言)断言 401/4013;业务域的读写角色断言与审计断言跟在其后。段落结构见 [83-admin-frontend.md](83-admin-frontend.md) 的验证一节。

修改守卫、角色或审计逻辑后,除了跑全量脚本,还应确认新增路由被收进了脚本的 `guardedAdminRoutes` 清单。

## 6. 失败定位

先区分:

- 代码真的破坏契约。
- 外部腾讯音乐接口临时波动。
- 验证库未正确重置或服务未重启。
- 4720 端口运行的是旧进程。
- 未显式调大 `ADMIN_RATE_LIMIT`(如 `1000`)导致中途 4290。

推荐顺序:

1. 停止旧验证实例。
2. 重置 `music_verify`。
3. 用最新源码启动 4720。
4. 再运行契约脚本。
5. 只针对失败分组定位,不修改正式库。

数据层专项验证(如 Key 并发扣额、失败退额、任务结果回写)与全量契约验证**都要**跑,专项覆盖并发路径,全量覆盖契约形状。

## 7. CI 上的契约验证

现行流程在单仓 core 的根目录 `.github/workflows/ci.yml` 的 `server` job(`server/**` 或 `crypto/**` 变更触发):Ubuntu runner + PostgreSQL 16 服务容器,`DATABASE_URL` 指向容器内的 `music_verify`,环境变量与本地验证一节相同(`ADMIN_RATE_LIMIT=1000`、`NODE_ENV=test` 等)。加密 `.node` 产物由同一次 run 的 `crypto` job 经 artifact 流入,`npm run build` 后从 Release 拉取分享播放器(拉不到退回占位文件),然后 `reset-db.mjs` → `node dist/main.js` 起验证实例 → `node tools/verify-contract.mjs http://127.0.0.1:4720`,**全绿才算通过**,之后才发布 dist 到 `server-dist-latest` Release,并在 push 时构建镜像推 `ghcr.io/taotaomusic/music-server`。

`server/.github/workflows/backend.yml` 是旧 music-server 独立仓库的工作流,该仓库已停用、暂保留备查,不要再按它理解现行发布链路。见 [62-ci-cloud-build.md](62-ci-cloud-build.md)。
