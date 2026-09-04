import { type CallHandler, type ExecutionContext, Injectable, type NestInterceptor } from "@nestjs/common";
import type { Response } from "express";
import type { Observable } from "rxjs";

/**
 * 补齐迁移前 `utils/http.ts` 里手写的那组响应头。
 *
 * 客户端并不依赖它们，但去掉相当于悄悄降低了浏览器侧的防护，
 * 所以照原样保留。`cache-control` 只对 JSON 生效 —— 安装包下载需要可缓存，
 * 那条路由自己设置 `cache-control`，这里不覆盖已有值。
 */
@Injectable()
export class SecurityHeadersInterceptor implements NestInterceptor {
  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const response = context.switchToHttp().getResponse<Response>();
    response.setHeader("access-control-allow-origin", "*");
    response.setHeader("x-content-type-options", "nosniff");
    response.setHeader("x-frame-options", "DENY");
    response.setHeader("referrer-policy", "no-referrer");
    if (!response.getHeader("cache-control")) response.setHeader("cache-control", "no-store");
    return next.handle();
  }
}
