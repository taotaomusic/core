# 官网与服务状态页

[返回文档中心](README.md)

最后更新:2026-10-02

本篇讲官网(React 单页,含 2026-10 新增的 `/status` 服务状态页)的源码结构、`main.ts` 根路径托管与 CSP、构建链路与 vendor 分包。管理后台(`src/frontend/`)在 [83-admin-frontend.md](83-admin-frontend.md),分享播放器在 [92-client-web.md](92-client-web.md),CSP 为什么必须挂在 Express 中间件层见 [12-request-pipeline.md](12-request-pipeline.md)。

## 1. 定位与目录结构

官网与后端**同进程**:构建产物 `dist/website` 由 `main.ts` 挂在根路径 `/`,与 `/api/v1`、`/admin`、`/share` 并列。技术栈 React 19 + antd v6 + Vite 6 + TypeScript;无路由、无后端数据请求(唯一 fetch 是状态页探活 `/health`),页面由锚点跳转组织。

源码在 `server/src/website/`:

| 位置 | 职责 |
| --- | --- |
| `index.html` | Vite 模板:meta/OG、`<script src="./theme-bootstrap.js">`(外置,见第 3 节) |
| `public/` | 原样拷贝的静态文件:`theme-bootstrap.js`(主题预置)、`favicon.svg`、`robots.txt` |
| `src/main.tsx` | 入口,StrictMode 挂载 `App` |
| `src/App.tsx` | 站点骨架:Nav + Hero/Features/Download/SelfHost(或 StatusPage)+ Footer;**主题状态的唯一持有点** |
| `src/theme.ts` | antd token 的明暗两套取值,色值必须与 `global.css` 的 `:root` 变量一致 |
| `src/hooks/useTheme.ts` | 明暗主题 hook(读 `<html data-theme>`、写 localStorage、跟系统) |
| `src/i18n/` | 中英双语的极简实现(第 7 节) |
| `src/styles/global.css` | 品牌视觉与页面骨架;antd 只管组件,这里管光晕/卡片/导航 |
| `src/components/` | `Nav`(吸顶导航+抽屉菜单)、`Hero`(首屏+`PlayerMock` 纯 CSS 播放器装饰)、`Features`、`Download`、`SelfHost`(docker 命令示例)、`Footer`、`StatusPage`、`Reveal`(进场动画)、`Icon`/`Logo`(纯 SVG,不依赖图片资源) |

## 2. 托管与缓存(main.ts)

- `resolveWebsiteDir()` 依次尝试 `dist/website` 的两个候选位置(`npm start` 与 `npm run dev`),**必须同时存在 `index.html` 和 `assets/` 才算构建产物**。坑:`src/website` 里也有 `index.html`,但那是 Vite 模板,引用的是未打包 TSX;误当静态目录会把 TSX 原文吐给浏览器,页面白屏且不报错。产物缺失只 `warn` 并跳过,后端照常启动。
- `express.static` 挂在 `/`,注册在 Nest 路由之前:只响应真实存在的文件,`/api/v1/...` 直接落到下一个中间件,因此既不遮接口、也不需要动 `setGlobalPrefix`;与 `/admin`、`/share` 目录互不重叠。**没有通配 SPA 回退**——未匹配路径保持 Nest 404(契约脚本断言 404 且 code 4040)。
- 缓存分档经 `setHeaders` 下发:`assets/` 下的内容哈希资源吃 `public, max-age=31536000, immutable`;入口 HTML 与其余文件 `no-cache` 协商(HTML 决定哈希文件名,缓存住就锁死旧版)。
- `/status` 用**精确正则** `^/status/?$` 回退到入口 HTML(CSP + no-cache 与入口同档)。它是官网内的一个页面而非真实文件,React 按 `pathname` 渲染;只精确匹配这一条,不要扩成通配回退。

## 3. CSP 与安全头

官网吃 `main.ts` 里的 `WEBSITE_CSP`,经 `expressStatic` 的 `setHeaders` 随每个静态响应下发(`/status` 回退路由手工补同一组头)。和 `/admin` 的 CSP 一样必须在静态层解决:Nest 拦截器盖不住 `express.static` 直接吐出的文件。逐条:

| 指令 | 取值 | 原因 |
| --- | --- | --- |
| `script-src` | `'self'` | 无内联脚本、无 eval。**`index.html` 不得添加内联 `<script>`** |
| `style-src` | `'self' 'unsafe-inline'` | antd v6 的 cssinjs 运行时注入 `<style>`,卡死整站变无样式页面;这是唯一放宽项,自写样式仍禁止任何行内 style |
| `img-src` | `'self' data:` | 比管理后台(`data: blob: https:`)更紧,官网无外链图、无上传预览 |
| `connect-src` | `'self'` | 只调同源接口 |
| `frame-ancestors` | `'none'` | 防点击劫持 |

首帧前的主题预置因此必须外置:`public/theme-bootstrap.js` 以同步 `<script src>` 引入(不加 defer/async),否则暗色用户先闪白。仅作用于官网路径,分享页需要 `wasm-unsafe-eval`,不共用这份策略。`ADMIN_CSP` 与 `WEBSITE_CSP` 在 `main.ts` 各自独立成常量,改官网策略不要顺手动后台。

## 4. 构建链路与 vendor 分包

```powershell
npm run build:website   # check:website + vite build --config vite.website.config.ts → dist/website
npm run dev:website     # 独立 Vite 开发服务,端口 5184,无接口代理
```

- `npm run build`(后端总构建)= 清空 `dist/` → `tsc` 编后端 → `minify:server` → `copy-manifest` → `build:frontend`(管理后台)→ `build:website`(官网)→ `build:web-player`(CI 里被 `SKIP_WEB_PLAYER=1` 跳过,分享播放器由 client-web job 出)。产物统一进 `dist/`,随 `server-dist-latest` Release 与 Docker 镜像一起发布,Dockerfile 直接 `COPY dist`。
- `check:website` 先跑 `tsc --noEmit -p src/website/tsconfig.json`(strict 全开),类型不过不出包。
- `vite.website.config.ts` 要点:`base: "./"`(相对引用,`/` 与 `/index.html` 两种入口都能加载,不与挂载路径绑定);JSX 交给 esbuild automatic runtime,**不引入 plugin-react**(无 HMR 诉求,省一批 babel 依赖)。

**NODE_ENV 坑**:Vite 会把环境里的 ambient `NODE_ENV` 静态替换进浏览器产物。CI server job 的 job 级 `NODE_ENV=test` 若漏覆盖,React 会被打进 **development 构建**(体积翻倍、带运行时警告)。因此 `.github/workflows/ci.yml` 的构建 step 单独设 `NODE_ENV: production`(契约验证是独立 step,继续用 job 级的 test,互不影响)。本地构建注意 shell 里已导出的 NODE_ENV。

**manualChunks vendor 分包**(`vite.website.config.ts` 的 `rollupOptions.output.manualChunks`):命中 `node_modules` 的模块按包名分流——`react` chunk(react/react-dom/react-is/scheduler)、`antd` chunk(antd/@ant-design/rc-*/dayjs),其余保持默认进 index chunk。官网业务代码小且常改,分包后发版时两个 vendor chunk 的内容哈希不变、浏览器继续吃 immutable 长缓存,只需重拉几十 KB 业务代码;全打一个 index 会每次全量拉 600+KB。注意:新增第三方依赖若不匹配上述包名,会落进业务 index chunk,大库应补进正则。

## 5. 服务状态页 /status

2026-10 新增,入口两处:页脚「服务状态」链接(`<a href="/status">`)与直接访问。`App.tsx` 按 `window.location.pathname`(去掉尾部斜杠)是否为 `/status` 决定渲染 `StatusPage` 还是首页四区块——站点没有客户端路由,后端只把这一条路径回退到入口 HTML。

分项探活(`StatusPage.tsx` 的 `probeHealth()`),对同源 `/health` 发一次 `fetch`(带 `cache: "no-store"`),`performance.now()` 测耗时:

| 分项 | 判定 | 展示 |
| --- | --- | --- |
| HTTP 服务 | 拿到**任何** HTTP 响应即算活 | 正常/异常 + 响应耗时(ms) |
| 数据库 | `res.ok` 且信封 `code === 0` 即通过;失败显示后端 message 或 `HTTP <状态码>` | 正常/异常 + 失败详情 |

另有总览横幅(两项全 ok 才「全部正常」)、检查时间、手动刷新按钮(请求期间图标转圈)与「返回首页」。`useEffect` 里先跑一次再挂 `setInterval` **每 30 秒自动刷新**,卸载时清理;并按语言把 `document.title` 设为「服务状态 · 桃桃音乐」。文案全部来自 `i18n` 字典的 `status` 段(中英同构)。

**`/health` 契约保持原样**:仍是 `HealthController` 的 `@Public` 接口(挂在 `api/v1` 前缀之外),实际响应形态:

```text
数据库正常 → 200 { code: 0, message: "success", data: { status: "up" } }
数据库挂   → 502 { code: 5020, message: "数据库不可用：..." }
```

状态页是它的**纯消费者**——没有新增任何后端端点,`verify-contract.mjs` 也没有官网相关断言;CI 就绪探测(`curl /health` 匹配 `"up"`)与运维探针不受影响。

## 6. 主题系统

三层分工:

1. **`theme-bootstrap.js`(构建前)**:首帧绘制前按「URL `?theme=`(调试) > localStorage(`taotao-website-theme`) > 系统偏好」定明暗,写在 `<html data-theme>` 上;同步外置脚本,CSP 不允许内联。localStorage 读写都包了 try/catch,隐私模式下静默降级到系统偏好,不挡页面。
2. **`useTheme.ts`(React 侧)**:初始值读 `data-theme`;`toggle` 写 DOM + localStorage 并挂 480ms 的 `data-switching` 过渡(见 `global.css`,约半秒后摘掉,避免常驻 transition 影响交互);系统偏好变化时跟随——但手动选过或 URL 强制主题时不跟。
3. **`theme.ts` + `App.tsx`**:`antdTokens(light)` 给 `ConfigProvider` 的 token 与 default/dark 算法;色值必须与 `global.css` 的 CSS 变量一一对应,改色两边同步。

**useTheme 多实例坑(已修,`App.tsx` 注释留档)**:hook 内部是独立 `useState`,多处调用各持一份、互不同步。曾经 Nav 里再调了一次,结果 ConfigProvider 的算法冻结在初始主题,切换后出现「半暗半亮」的脏状态。现在的写法:**主题状态只在 `ThemedSite` 调用一次**,Nav 通过 `themeLight` / `onToggleTheme` props 参与切换。给官网加组件时不要再调 `useTheme`。

另:首屏入场动画只播一次——`App` 挂载 1.8 秒后给 `<html>` 挂 `data-entered`,之后切主题/语言不会重播动画。

## 7. i18n

`src/i18n/index.tsx` 用 Context + 字典直查,**无第三方 i18n 依赖**:`zh.ts` 定义结构(`Dict` 类型),`en.ts` 对齐同构;语言存 localStorage(`taotao-website-lang`),初始取「存储值 > 浏览器语言」,读写 localStorage 同样容错(隐私模式落到浏览器语言)。切换时同步 `<html lang>` 与 `document.title`,antd 内置文案由 App 按语言传 `zhCN`/`enUS` locale(`antdLocale()`)。

## 8. 布局与响应式

- 桌面端**近乎全宽**:容器 `max-width: 1760px`,只在超宽屏封顶,让内容充分铺开。
- 移动端窄屏敏感:断点 980/700/560;≤700px 时导航行内锚点收进 `Drawer`,GitHub 按钮收成圆形图标钮,首屏按钮纵向铺满、命令示例折行代替横向滚动条。
- 尊重 `prefers-reduced-motion`:`Reveal` 进场动画直接显示、不注册观察器。

## 9. antd v6 适配细节

- 按钮图标被包在 `span.ant-btn-icon` 里,按钮文字是**无类名的裸 span**。窄屏把 GitHub 按钮藏文字用的选择器是 `.nav-github-btn > span:not([class])`(见 `global.css`)——按「无 class 才隐藏」精确命中文字,不误伤图标。
- antd 的 Typography 类优先级压过自定义样式,覆写时要加 `.ant-typography` 提高特异性;Card/Tag 只做颜色/圆角等品牌覆写,结构交给组件库。
- CSP 禁行内样式,所以自写组件不能用 `style` 属性传样式——`Logo` 的尺寸走 SVG 的 width/height 属性。

## 10. 常见坑

| 坑 | 后果 | 规避 |
| --- | --- | --- |
| 构建时 `NODE_ENV` 不是 production | vite 静态替换把 React dev 构建打进产物,体积翻倍带警告 | CI 构建 step 已单独设;本地注意环境变量 |
| `index.html` 加内联脚本 / 自写组件用行内 style | 被 CSP 拦,脚本不执行、样式失效 | 脚本外置进 `public/`,样式进 `global.css`(antd cssinjs 注入除外) |
| `useTheme` 在多个组件调用 | 各持一份 state,antd 算法冻结在初始主题,半暗半亮 | 只在 `ThemedSite` 调用一次,其余组件走 props |
| 改了业务代码重发版,vendor 跟着失效 | 用户全量重拉 600+KB | 新依赖补进 `manualChunks` 的包名正则 |
| 把 `src/website` 当静态目录 / 产物没构建 | TSX 原文直出,白屏不报错 | `resolveWebsiteDir` 要求 `index.html` + `assets/` 双条件;先 `npm run build:website` |
| 给 `/status` 之外加通配 SPA 回退 | 未匹配路径不再 404/4040,破坏契约 | 只保留精确的 `/status` 回退 |
| 改色只改 `global.css` 或只改 `theme.ts` | CSS 变量与 antd token 脱节 | 两边色值一一对应,必须同步改 |
