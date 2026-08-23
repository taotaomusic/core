import { type ArgumentsHost, Catch, type ExceptionFilter, HttpException, HttpStatus, Logger } from "@nestjs/common";
import type { Response } from "express";
import { ApiException } from "../api.exception";

type Described = { status: number; code: number; message: string };

/**
 * 统一错误响应。
 *
 * 客户端只从错误体的顶层 `message` 取文案（`TencentMusicApi.messageOf`），
 * 而 NestJS 默认的错误体在校验失败时 `message` 是**数组**，
 * 客户端 `optString` 会拿到 `["..."]` 直接显示给用户 —— 所以这里必须压平成字符串。
 */
@Catch()
export class AllExceptionsFilter implements ExceptionFilter {
  private readonly logger = new Logger(AllExceptionsFilter.name);

  catch(exception: unknown, host: ArgumentsHost): void {
    const response = host.switchToHttp().getResponse<Response>();
    const described = this.describe(exception);

    // 流式端点（搜索的 NDJSON、播放转发、安装包下载）可能已经把响应头发出去了，
    // 这时改不了状态码也不能再追加 JSON，只能截断连接，让客户端按读取失败处理。
    if (response.headersSent) {
      this.logger.warn(`响应头已发出，无法返回错误体：${described.message}`);
      response.end();
      return;
    }
    if (described.status >= 500) this.logger.error(described.message);
    response.status(described.status).json({ code: described.code, message: described.message });
  }

  private describe(exception: unknown): Described {
    if (exception instanceof ApiException) {
      return { status: exception.getStatus(), code: exception.code, message: this.messageOf(exception) };
    }
    if (exception instanceof HttpException) {
      const status = exception.getStatus();
      return { status, code: this.fallbackCodeOf(status), message: this.messageOf(exception) };
    }
    // 迁移前顶层 try/catch 把所有未知异常归成 502 + 5020，保持一致。
    return {
      status: HttpStatus.BAD_GATEWAY,
      code: 5020,
      message: exception instanceof Error ? exception.message : "代理请求失败",
    };
  }

  /** 把 NestJS 的错误体压成一个字符串；数组用中文分号连接。 */
  private messageOf(exception: HttpException): string {
    const body = exception.getResponse();
    if (typeof body === "string") return body;
    const message = (body as { message?: unknown }).message;
    if (Array.isArray(message)) return message.map(String).join("；");
    if (typeof message === "string" && message.length > 0) return message;
    return exception.message;
  }

  /**
   * 框架自身产生的异常（未匹配路由、参数校验失败）没有业务码，按状态码推导。
   * 沿用历史命名规律：状态码乘十，于是 404 → 4040、401 → 4010、429 → 4290。
   */
  private fallbackCodeOf(status: number): number {
    if (status === HttpStatus.NOT_FOUND) return 4040;
    if (status === HttpStatus.UNAUTHORIZED) return 4010;
    if (status === HttpStatus.BAD_REQUEST) return 4005;
    return status * 10;
  }
}
