import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { AdminAuthService } from "./admin-auth.service";
import type { AdminActor, AdminAuthenticatedRequest } from "../common/request.types";

/**
 * 管理端认证守卫。
 *
 * 两种凭据：新会话走 `Authorization: Bearer`，旧的静态 `X-Admin-Token` 仍然
 * 放行（契约验证脚本和存量运维脚本都还在用它）。
 *
 * 兼容路径合成的身份 `id = 0` 在 `admin_users` 里没有对应行，因此它**不能**
 * 用于任何会写外键的操作 —— 落库前必须先过 `auditActorId()`。
 */
@Injectable()
export class AdminAuthGuard implements CanActivate {
  constructor(private readonly auth: AdminAuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AdminAuthenticatedRequest>();
    const authHeader = String(request.headers["authorization"] ?? "");
    const legacyToken = String(request.headers["x-admin-token"] ?? "");

    // 优先尝试 Bearer token（新会话）
    if (authHeader.startsWith("Bearer ")) {
      const token = authHeader.slice(7);
      const session = await this.auth.validateSession(token);
      if (session) {
        request.adminUser = session;
        return true;
      }
    }

    // 向后兼容：旧的 X-Admin-Token。仅在配置了 ADMIN_TOKEN 时生效。
    if (legacyToken && this.auth.verifyLegacyToken(legacyToken)) {
      const legacyAdmin: AdminActor = {
        id: 0,
        username: "legacy_admin",
        role: "super_admin",
        display_name: "传统管理员",
      };
      request.adminUser = legacyAdmin;
      return true;
    }

    throw ApiErrors.unauthorized(4013, "管理员认证失败");
  }
}
