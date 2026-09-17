import { type CallHandler, type ExecutionContext, Injectable, type NestInterceptor } from "@nestjs/common";
import type { Request, Response } from "express";
import type { Observable } from "rxjs";
import { AppConfigService } from "../../config/app-config.service";

/**
 * 补齐迁移前 `utils/http.ts` 里手写的那组响应头。
 *
 * 客户端并不依赖它们，但去掉相当于悄悄降低了浏览器侧的防护，
 * 所以照原样保留。`cache-control` 只对 JSON 生效 —— 安装包下载需要可缓存，
 * 那条路由自己设置 `cache-control`，这里不覆盖已有值。
 *
 * **CORS 不再无条件下发 `*`**：通配符允许任意站点在用户浏览器里读取本服务的
 * 响应。管理后台与分享页都由本服务同源托管，不需要 CORS；只有 `CORS_ALLOWED_ORIGINS`
 * 里显式列出的来源才回显 `access-control-allow-origin`。音视频流与搜索接口在
 * 各自的路由里另行设置 `*`（原生播放器与分享页要跨域拉流），不受这里影响 ——
 * 拦截器只负责「先写默认值」，处理器随后可以覆盖。
 */
@Injectable()
export class SecurityHeadersInterceptor implements NestInterceptor {
  constructor(private readonly config: AppConfigService) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const http = context.switchToHttp();
    const response = http.getResponse<Response>();
    const request = http.getRequest<Request>();

    this.applyCors(request, response);

    response.setHeader("x-content-type-options", "nosniff");
    response.setHeader("x-frame-options", "DENY");
    response.setHeader("referrer-policy", "no-referrer");
    if (!response.getHeader("cache-control")) response.setHeader("cache-control", "no-store");
    return next.handle();
  }

  private applyCors(request: Request, response: Response): void {
    // 无论是否命中白名单都要声明 Vary: Origin：同一个 URL 对不同来源会给出
    // 不同的 ACAO，缓存不区分来源就会把 A 站看到的响应喂给 B 站。
    const vary = String(response.getHeader("vary") ?? "");
    if (!/\borigin\b/i.test(vary)) {
      response.setHeader("vary", vary ? `${vary}, Origin` : "Origin");
    }

    const origin = String(request.headers.origin ?? "").trim().toLowerCase();
    if (!origin || !this.config.corsAllowedOrigins.includes(origin)) return;

    response.setHeader("access-control-allow-origin", origin);
    response.setHeader("access-control-allow-headers", "authorization, content-type, range");
    response.setHeader("access-control-allow-methods", "GET, POST, PATCH, DELETE, OPTIONS");
    // 这里不用 `access-control-allow-credentials`：身份靠 Authorization 头携带，
    // 不依赖浏览器自动附带的 Cookie，打开凭据只会扩大攻击面。
  }
}
