import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import type { OpenApiAuthenticatedRequest } from "../common/request.types";
import { OpenApiKeyService } from "./open-api-key.service";

/**
 * 从请求头取开放 API Key 原文。
 *
 * 与限流守卫同一口径：`X-API-Key` 优先，否则 `Authorization: Bearer` 且值以 `tt_` 开头。
 * 用户访问令牌是 `payload.signature` 形状（含点、不以 tt_ 开头），不会被误收。
 */
function readRawApiKey(request: Request): string {
  const header = String(request.headers["x-api-key"] ?? "").trim();
  if (header) return header;
  const authorization = String(request.headers.authorization ?? "");
  const bearer = authorization.replace(/^Bearer\s+/i, "").trim();
  return bearer.startsWith("tt_") ? bearer : "";
}

/**
 * 开放 API Key 鉴权守卫。
 *
 * **这是方法级守卫，配合 `@Public()` 使用**：全局 AccessTokenGuard 对 @Public 路由
 * 直接放行，本守卫再校验开放 key —— 三套凭据（用户令牌 / 管理员会话 / 开放 key）
 * 互相独立，任何一处都不得互相借用。
 *
 * 失败一律 **401 + 业务码 4014**（4010–4013 已被占用），绝不能 403：
 * 与访问令牌同一约定，调用方按 401 判断「凭据被拒绝」。
 */
@Injectable()
export class ApiKeyGuard implements CanActivate {
  constructor(private readonly service: OpenApiKeyService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<OpenApiAuthenticatedRequest>();
    const identity = await this.service.verify(readRawApiKey(request));
    if (!identity) throw ApiErrors.unauthorized(4014, "API Key 无效或已吊销");
    request.openApiKey = identity;
    return true;
  }
}
