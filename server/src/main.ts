import "reflect-metadata";

import { Logger, ValidationPipe } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import type { NestExpressApplication } from "@nestjs/platform-express";
import { json, static as expressStatic } from "express";
import type { NextFunction, Request, Response } from "express";
import { existsSync, readFileSync } from "fs";
import { join } from "path";
import { AppModule } from "./app.module";
import { rewriteShareIndexHtml, sharePlayerVersion } from "./common/share-player-assets";
import { sharePlayerCacheHeaders } from "./common/static-cache-policy";
import { AppConfigService } from "./config/app-config.service";

/**
 * 请求体是原始字节、必须绕开 JSON 解析的路由。
 *
 * 漏一条的表现很隐蔽：上传的字节被 `express.json()` 吃掉，
 * 落盘时得到空文件或者报 413，而路由本身看起来是通的。
 */
const RAW_BODY_PATHS = new Set([
  "/api/v1/app/admin/releases",
  "/api/v1/app/admin/patches",
  "/api/v1/desktop/admin/jars",
  "/api/v1/desktop/admin/patches",
  "/api/v1/desktop/admin/artifacts",
]);

/**
 * 桌面发布清单是 JSON，但最多包含 512 个模块，不能沿用普通业务的 16KB 上限。
 * 这里给清单单独留出空间，仍然不影响其它 JSON 路由的请求体边界。
 */
const DESKTOP_MANIFEST_PATH = "/api/v1/desktop/admin/releases";

/**
 * 管理后台的构建产物目录。
 *
 * 固定产在 `dist/public`（见 vite.config.ts 的 outDir），但 `__dirname` 随运行方式变：
 * `npm start` 时是 `dist/`，`npm run dev`（ts-node 直跑 src）时是 `src/`。
 * 两个候选都试，找不到就返回 null —— 后端不该因为没构建前端而起不来。
 */
function resolvePublicDir(): string | null {
  const candidates = [
    join(__dirname, "public"), // npm start：dist/ → dist/public
    join(__dirname, "..", "dist", "public"), // npm run dev：src/ → dist/public
  ];
  return candidates.find((dir) => existsSync(join(dir, "index.html"))) ?? null;
}

/** Kotlin/Wasm 分享播放器构建产物；开发态与编译后运行各尝试一个固定位置。 */
function resolveSharePlayerDir(): string | null {
  const candidates = [
    join(__dirname, "share-player"),
    join(__dirname, "..", "dist", "share-player"),
  ];
  return candidates.find((dir) => existsSync(join(dir, "index.html"))) ?? null;
}

/**
 * 管理后台的 Content-Security-Policy。
 *
 * 用 Express 中间件而不是 Nest 拦截器：`express.static` 在路由之前直接吐出
 * HTML/JS，拦截器根本没机会运行，而 CSP 恰恰必须跟着 `index.html` 一起下发 ——
 * 漏了它，攻击者只要能在页面里注入一个内联脚本，就能把 localStorage 里的
 * 管理会话令牌读走。
 *
 * 各指令的取值都是被实际依赖倒推出来的，不要照抄模板：
 * - `script-src 'self'`：没有内联脚本（主题预置脚本已外置），也没有 eval。
 * - `style-src 'self' 'unsafe-inline'`：Element Plus 以行内样式注入主题变量与
 *   组件样式，去掉这条整个后台会变成无样式页面。这是本策略唯一放宽的一项。
 * - `img-src 'self' data: blob: https:`：头像来自外部图床（https），上传预览用
 *   blob:/data:。
 * - `connect-src 'self'`：前端只调用同源接口，没有第三方上报。
 * - `frame-ancestors 'none'`：与已有的 `x-frame-options: DENY` 一致，防点击劫持。
 *
 * 只作用于 `/admin`：分享页是 Kotlin/Wasm，编译 WebAssembly 需要
 * `wasm-unsafe-eval`，套用这份策略会直接把播放器打瘫。
 */
const ADMIN_CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https:",
  "font-src 'self' data:",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "frame-ancestors 'none'",
].join("; ");

async function bootstrap(): Promise<void> {
  // 关掉内置 body parser，改为按路由挂载：安装包上传是 14MB+ 的原始字节流，
  // 一旦被 JSON 解析器接手，要么报 413，要么把整个包缓进内存。
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bodyParser: false });
  const config = app.get(AppConfigService);

  const parseJson = json({ limit: "16kb" });
  const parseDesktopManifest = json({ limit: "1mb" });
  app.use((request: Request, response: Response, next: NextFunction) => {
    if (request.method === "POST" && request.path === DESKTOP_MANIFEST_PATH) {
      return parseDesktopManifest(request, response, next);
    }
    if (request.method === "POST" && RAW_BODY_PATHS.has(request.path)) return next();
    return parseJson(request, response, next);
  });

  // 管理后台固定放在 /admin，为后续独立 Web 站点或其它前端留出根路径。静态资源挂在
  // 路由之前：express.static 只响应真实文件，/api/v1/... 会直接落到下一个中间件，
  // 因此不会遮住接口，也完全不用碰 setGlobalPrefix。
  const publicDir = resolvePublicDir();
  if (publicDir) {
    // 必须挂在 expressStatic 之前：中间件按注册顺序执行，放在后面就只能覆盖
    // 到回退的 index.html，真正的 JS/CSS 响应反而没有 CSP。
    app.use("/admin", (_request: Request, response: Response, next: NextFunction) => {
      response.setHeader("content-security-policy", ADMIN_CSP);
      next();
    });
    app.use("/admin", expressStatic(publicDir));
    app.getHttpAdapter().getInstance().get(
      /^\/admin(?:\/.*)?$/,
      (request: Request, response: Response, next: NextFunction) => {
        // 不存在的静态资源仍然返回 404；只有未来的前端页面路由才回退到入口文件。
        if (request.path.split("/").at(-1)?.includes(".")) return next();
        return response.sendFile(join(publicDir, "index.html"));
      },
    );
  } else {
    new Logger("Bootstrap").warn("未找到管理后台构建产物，跳过静态资源。执行 npm run build:frontend 生成");
  }

  // 分享页只托管 Kotlin/Wasm 静态产物；歌曲身份和试听地址仍由公开 API 按短码读取。
  // 资源使用独立 /share 前缀，避免 /s/{token} 下的相对路径被浏览器解析成错误地址。
  //
  // 防混批是这里的**首要目标**，用了两层，缺一层都会漏：
  //
  // 1. **路径版本化**（`/share/v/<内容指纹>/…`）。入口的 JS 胶水与应用 wasm 都是**固定名**，
  //    而 JS 必须提供 wasm 要 import 的那批 `js_code` 实现，两者严格同批才行。把指纹写进
  //    路径后，内容一变 URL 就变，浏览器手上不可能存在该 URL 的旧缓存。`index.html` 的
  //    `<base>` 也指向版本化路径，于是 JS / wasm / skiko / 字体等**所有相对引用**自动跟版本走。
  // 2. **缓存头分档**（见 [sharePlayerCacheHeaders]）。版本化路径下内容不可变，整目录吃
  //    `immutable`；未版本化的老 URL 走 ETag 协商。
  //
  // 为什么光有第 2 层不够：`no-cache` 只能保证「以后」不再混批，浏览器里**已经按 immutable
  // 存下的那份不会自动失效** —— 用户不硬刷新就一直抛
  // `LinkError ... requires a callable`。第 1 层才是让用户「什么都不用做」就能恢复的原因。
  const sharePlayerDir = resolveSharePlayerDir();
  if (sharePlayerDir) {
    const shareVersion = sharePlayerVersion(sharePlayerDir);
    const shareIndexHtml = rewriteShareIndexHtml(
      readFileSync(join(sharePlayerDir, "index.html"), "utf8"),
      shareVersion,
    );
    const sendShareIndex = (_request: Request, response: Response): void => {
      // 入口 HTML 必须每次协商：它决定了 `<base>` 里的版本号，缓存住就等于把用户锁在旧版本上。
      response.type("html").setHeader("cache-control", "no-cache").send(shareIndexHtml);
    };

    // 版本化路径：路径自带内容指纹，可以放心整目录长缓存。
    app.use(
      `/share/v/${shareVersion}`,
      expressStatic(sharePlayerDir, {
        etag: true,
        lastModified: true,
        setHeaders: (response: Response) =>
          response.setHeader("cache-control", "public, max-age=31536000, immutable"),
      }),
    );

    // index.html 要改写 `<base>`，所以必须由我们自己吐，关掉 static 的目录默认页。
    app.getHttpAdapter().getInstance().get("/share/", sendShareIndex);
    app.getHttpAdapter().getInstance().get("/share/index.html", sendShareIndex);

    // 兼容未版本化的老 URL（历史页面、绝对路径引用），走 ETag 协商。
    app.use(
      "/share",
      expressStatic(sharePlayerDir, {
        index: false,
        etag: true,
        lastModified: true,
        setHeaders: sharePlayerCacheHeaders,
      }),
    );

    app.getHttpAdapter().getInstance().get(/^\/s\/[A-Za-z0-9_-]{8,24}\/?$/, sendShareIndex);
  } else {
    new Logger("Bootstrap").warn("未找到分享播放器构建产物。执行 npm run build:web-player 生成");
  }

  // health 保持在 /health，其余接口统一挂在 /api/v1 下。
  //
  // 这里的 exclude **只能**列具体路径，绝不能写通配符：写 "/*" 会把
  // 所有路由都从前缀里豁免掉，/api/v1/search 等接口全部 404 ——
  // 装机客户端会瞬间全线失联。管理后台的静态资源靠上面的 express.static
  // 在路由之前拦截，不需要动这里。
  app.setGlobalPrefix("api/v1", { exclude: ["health"] });
  app.useGlobalPipes(new ValidationPipe({ transform: true, forbidUnknownValues: false }));
  app.getHttpAdapter().getInstance().disable("x-powered-by");

  // 默认超级管理员的创建放在 [AdminBootstrapService.onApplicationBootstrap]，
  // 不在这里手动 app.get()：建表在 DatabaseService.onModuleInit，早于本文件
  // 这段代码执行，在这里查表只会得到「关系 admin_users 不存在」。

  await app.listen(config.port);
  new Logger("Bootstrap").log(`桃桃音乐代理服务已启动：http://localhost:${config.port}`);
}

void bootstrap();
