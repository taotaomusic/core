import "reflect-metadata";

import { Logger, ValidationPipe } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import type { NestExpressApplication } from "@nestjs/platform-express";
import { json, static as expressStatic } from "express";
import type { NextFunction, Request, Response } from "express";
import { existsSync } from "fs";
import { join } from "path";
import { AppModule } from "./app.module";
import { AppConfigService } from "./config/app-config.service";
import { AdminUsersRepository } from "./admin-auth/admin-users.repository";
import { AdminAuthService } from "./admin-auth/admin-auth.service";

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
  const sharePlayerDir = resolveSharePlayerDir();
  if (sharePlayerDir) {
    app.use("/share", expressStatic(sharePlayerDir, { immutable: true, maxAge: "1d" }));
    app.getHttpAdapter().getInstance().get(
      /^\/s\/[A-Za-z0-9_-]{8,24}\/?$/,
      (_request: Request, response: Response) => response.sendFile(join(sharePlayerDir, "index.html")),
    );
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

  // 创建默认超级管理员（如果不存在）
  try {
    const adminUsers = app.get(AdminUsersRepository);
    const adminAuth = app.get(AdminAuthService);
    const existing = await adminUsers.findByUsername("admin");
    if (!existing) {
      const { hash, salt } = adminAuth.hashPassword("admin123");
      await adminUsers.create("admin", hash, salt, "超级管理员", "super_admin", null);
      new Logger("Bootstrap").log("已创建默认管理员账号：admin / admin123（请尽快修改密码）");
    }
  } catch (error) {
    new Logger("Bootstrap").warn(`创建默认管理员失败：${(error as Error).message}`);
  }

  await app.listen(config.port);
  new Logger("Bootstrap").log(`桃桃音乐代理服务已启动：http://localhost:${config.port}`);
}

void bootstrap();
