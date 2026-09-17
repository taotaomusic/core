import type { Request } from "express";

/** 访问令牌校验通过后挂在请求上的用户信息。 */
export type SessionUser = { id: number; username: string; created_at: string };

/** 经过全局守卫的请求。`user` 仅在非公开路由上保证存在。 */
export type AuthenticatedRequest = Request & { user?: SessionUser };

/**
 * 管理员身份。
 *
 * `id` 为 0 是 `X-Admin-Token` 兼容路径的合成身份，在 `admin_users` 里
 * **没有对应行** —— 任何要落库引用它的地方都必须先过 [auditActorId]。
 */
export type AdminActor = { id: number; username: string; role: string; display_name: string };

/** 经过 [AdminAuthGuard] 的请求。 */
export type AdminAuthenticatedRequest = Request & { adminUser?: AdminActor };

/**
 * 把请求上的管理员身份转成可以写进外键列的 ID。
 *
 * 兼容身份的 0 在 `admin_users` 里不存在，直接写会撞外键；审计表虽然已改成
 * 可空，但 `created_by` 之类的地方仍需显式区分「无归属」和「某个 ID」。
 */
export function auditActorId(admin: AdminActor | undefined): number | null {
  if (!admin || !admin.id) return null;
  return admin.id;
}

/** 取来源地址，用于限流分桶。 */
export function clientAddressOf(request: Request): string {
  return request.socket.remoteAddress ?? "unknown";
}

/**
 * 取客户端真实地址，用于 IP 白名单与审计留痕。
 *
 * `X-Forwarded-For` 是客户端可以随手伪造的头，只有在**明确知道**自己
 * 部署在可信反向代理之后（`TRUST_PROXY`）时才允许采信；否则一律用
 * TCP 对端地址。这与 [clientAddressOf] 的口径保持一致 —— 两套取址
 * 不一致时，攻击者能靠一个请求头绕过 IP 白名单，而限流却按真实地址算。
 */
export function forwardedClientAddress(request: Request): string {
  const trustProxy = process.env.TRUST_PROXY === "1" || process.env.TRUST_PROXY === "true";
  if (trustProxy) {
    const forwarded = String(request.headers["x-forwarded-for"] ?? "").split(",")[0].trim();
    if (forwarded) return forwarded;
  }
  return clientAddressOf(request);
}
