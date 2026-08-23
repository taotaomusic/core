import { type CallHandler, type ExecutionContext, Injectable, type NestInterceptor } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { map, type Observable } from "rxjs";
import { RAW_RESPONSE_METADATA_KEY } from "../decorators/raw-response.decorator";

/**
 * 成功响应统一包成 `{ code: 0, message: "success", data }`。
 *
 * 客户端判断成功的唯一依据是 `code == 0`，且 `data` 里的字段必须平铺
 * （例如 `accessToken` 是用 getString 硬取的，缺失直接抛异常）。
 *
 * 两种情况必须跳过包装：
 * - 标了 [RawResponse] 的流式端点，自己写响应体
 * - 处理函数没有返回值（注销返回 204 空体，包一层会让 204 带上响应体）
 */
@Injectable()
export class EnvelopeInterceptor implements NestInterceptor {
  constructor(private readonly reflector: Reflector) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    const raw = this.reflector.getAllAndOverride<boolean>(RAW_RESPONSE_METADATA_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (raw) return next.handle();
    return next.handle().pipe(
      map((data) => (data === undefined ? undefined : { code: 0, message: "success", data })),
    );
  }
}
