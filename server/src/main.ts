import "reflect-metadata";

import { Logger, ValidationPipe } from "@nestjs/common";
import { NestFactory } from "@nestjs/core";
import type { NestExpressApplication } from "@nestjs/platform-express";
import { json } from "express";
import type { NextFunction, Request, Response } from "express";
import { AppModule } from "./app.module";
import { AppConfigService } from "./config/app-config.service";

/** 安装包上传的路径。这条路由的请求体是原始字节，必须绕开 JSON 解析。 */
const APK_UPLOAD_PATH = "/api/v1/app/admin/releases";

async function bootstrap(): Promise<void> {
  // 关掉内置 body parser，改为按路由挂载：安装包上传是 14MB+ 的原始字节流，
  // 一旦被 JSON 解析器接手，要么报 413，要么把整个包缓进内存。
  const app = await NestFactory.create<NestExpressApplication>(AppModule, { bodyParser: false });
  const config = app.get(AppConfigService);

  const parseJson = json({ limit: "16kb" });
  app.use((request: Request, response: Response, next: NextFunction) => {
    if (request.method === "POST" && request.path === APK_UPLOAD_PATH) return next();
    return parseJson(request, response, next);
  });

  // health 保持在 /health，其余接口统一挂在 /api/v1 下。
  app.setGlobalPrefix("api/v1", { exclude: ["health"] });
  app.useGlobalPipes(new ValidationPipe({ transform: true, forbidUnknownValues: false }));
  app.getHttpAdapter().getInstance().disable("x-powered-by");

  await app.listen(config.port);
  new Logger("Bootstrap").log(`桃桃音乐代理服务已启动：http://localhost:${config.port}`);
}

void bootstrap();
