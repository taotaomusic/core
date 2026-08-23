import type { Request } from "express";

/** 访问令牌校验通过后挂在请求上的用户信息。 */
export type SessionUser = { id: number; username: string; created_at: string };

/** 经过全局守卫的请求。`user` 仅在非公开路由上保证存在。 */
export type AuthenticatedRequest = Request & { user?: SessionUser };

/** 取来源地址，用于限流分桶。 */
export function clientAddressOf(request: Request): string {
  return request.socket.remoteAddress ?? "unknown";
}
