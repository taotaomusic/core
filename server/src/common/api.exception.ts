import { HttpException, HttpStatus } from "@nestjs/common";

/**
 * 带业务码的异常。
 *
 * 客户端判断成功的唯一依据是响应体里的 `code`，而这些码是历史契约
 * （4001 空关键词、4010 未登录、4041 版本不存在……），装机客户端按它们分支。
 * 所以每个抛出点必须自带原始业务码，[AllExceptionsFilter] 只负责搬运，不重新编号。
 */
export class ApiException extends HttpException {
  constructor(
    status: HttpStatus,
    readonly code: number,
    message: string,
  ) {
    super({ code, message }, status);
  }
}

/** 常用业务异常的快捷构造，避免各处重复写状态码与业务码的对应关系。 */
export const ApiErrors = {
  badRequest: (code: number, message: string) => new ApiException(HttpStatus.BAD_REQUEST, code, message),
  unauthorized: (code: number, message: string) => new ApiException(HttpStatus.UNAUTHORIZED, code, message),
  notFound: (code: number, message: string) => new ApiException(HttpStatus.NOT_FOUND, code, message),
  conflict: (code: number, message: string) => new ApiException(HttpStatus.CONFLICT, code, message),
  tooManyRequests: () =>
    new ApiException(HttpStatus.TOO_MANY_REQUESTS, 4290, "请求过于频繁，请稍后再试"),
  /**
   * 账号维度的登录退避。
   *
   * 与 [tooManyRequests] 同为 429，但业务码刻意区分（4291 vs 4290）：两者是
   * **互相独立的两道控制** —— 4290 是来源地址限流，换个 IP 或等窗口过去就恢复；
   * 4291 是账号被锁，换 IP 无用、必须等退避时间走完或由超管重置。运维和客户端
   * 都需要能分辨「网络太频繁」和「这个账号被锁了」，共用一个码就永远分不清。
   */
  accountLocked: () =>
    new ApiException(HttpStatus.TOO_MANY_REQUESTS, 4291, "该账号连续登录失败次数过多，请稍后再试"),
  /**
   * 上游或内部故障。
   *
   * 刻意用 502 而不是 4xx：客户端把 4xx 当作「凭据被拒绝」，
   * 在刷新令牌接口上会因此清空本地会话把用户踢回登录页。
   */
  upstream: (message: string) => new ApiException(HttpStatus.BAD_GATEWAY, 5020, message),
  /**
   * 某项可选基础设施未启用或暂时不可用。
   *
   * 与 upstream 的 502 区分：前者表示本服务尚未完成配置，客户端不应把它误判成登录失效。
   */
  forbidden: (code: number, message: string) => new ApiException(HttpStatus.FORBIDDEN, code, message),
  serviceUnavailable: (code: number, message: string) => new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message),
};
