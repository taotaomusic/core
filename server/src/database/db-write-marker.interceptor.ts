import { type CallHandler, type ExecutionContext, Injectable, type NestInterceptor } from "@nestjs/common";
import { tap, type Observable } from "rxjs";
import type { AuthenticatedRequest } from "../common/request.types";
import { DbWriteActivityTracker } from "./db-write-activity.tracker";

/** 与 createDbRoutingMiddleware 保持一致：这些方法成功后开启写后立读窗口。 */
const WRITE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * 写请求成功后开启「写后立读」窗口（配合 [DbWriteActivityTracker]）。
 *
 * 挂在全局拦截器链的末尾：`tap` 只在正常返回时执行，被异常、限流或守卫拒绝的
 * 写请求不会开启窗口（它们没产生新数据，开了只是白白多走主库）。
 *
 * 守卫与限流里的库读发生在拦截器之前、中间件上下文之内，由
 * `createDbRoutingMiddleware` 的令牌解析路径覆盖，与本拦截器无关。
 */
@Injectable()
export class DbWriteMarkerInterceptor implements NestInterceptor {
  constructor(private readonly tracker: DbWriteActivityTracker) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    if (context.getType() !== "http") return next.handle();
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    if (!WRITE_METHODS.has(request.method.toUpperCase())) return next.handle();
    return next.handle().pipe(
      tap(() => {
        const userId = request.user?.id;
        if (userId) this.tracker.mark(userId);
      }),
    );
  }
}
