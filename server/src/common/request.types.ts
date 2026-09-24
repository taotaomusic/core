import type { Request } from "express";

/** 访问令牌校验通过后挂在请求上的用户信息。 */
export type SessionUser = { id: number; username: string; created_at: string };

/** 经过全局守卫的请求。`user` 仅在非公开路由上保证存在。 */
export type AuthenticatedRequest = Request & { user?: SessionUser };

/**
 * 管理员身份。
 *
 * `must_change_password` 为 1 表示该账号仍在「首次登录必须改密」状态：
 * [AdminAuthGuard] 会据此拦掉除 `me` / `change-password` / `logout`
 * 之外的全部管理接口。
 */
export type AdminActor = {
  id: number;
  username: string;
  role: string;
  display_name: string;
  must_change_password: number;
};

/** 经过 [AdminAuthGuard] 的请求。 */
export type AdminAuthenticatedRequest = Request & { adminUser?: AdminActor };

/**
 * 开放 API Key 身份。
 *
 * 由开放接口的方法级守卫校验后挂到请求上；与用户访问令牌、管理员会话
 * 是三套互相独立的凭据，任何一处都不得互相借用。
 */
export type OpenApiKeyIdentity = { id: number; name: string };

/** 经过 [ApiKeyGuard] 的开放接口请求。 */
export type OpenApiAuthenticatedRequest = Request & { openApiKey?: OpenApiKeyIdentity };

/**
 * 把请求上的管理员身份转成可以写进外键列的 ID。
 *
 * 防御性收敛：身份缺失或 id 不是正整数时折成 `null`，而不是把 0 或
 * `undefined` 写进外键列（`admin_audit_log.admin_id` 是 `ON DELETE SET NULL`
 * 的可空列，`null` 表示「无归属」是合法且有意义的取值）。
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
