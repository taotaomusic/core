import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { AdminAuthService } from "./admin-auth.service";
import type { AuthenticatedRequest } from "../common/request.types";

@Injectable()
export class AdminAuthGuard implements CanActivate {
  constructor(private readonly auth: AdminAuthService) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const authHeader = String(request.headers["authorization"] ?? "");
    const legacyToken = String(request.headers["x-admin-token"] ?? "");

    // 优先尝试 Bearer token（新会话）
    if (authHeader.startsWith("Bearer ")) {
      const token = authHeader.slice(7);
      const session = await this.auth.validateSession(token);
      if (session) {
        (request as any).adminUser = session;
        return true;
      }
    }

    // 向后兼容：旧的 X-Admin-Token
    if (legacyToken && this.auth.verifyLegacyToken(legacyToken)) {
      (request as any).adminUser = { id: 0, username: "legacy_admin", role: "super_admin", display_name: "传统管理员" };
      return true;
    }

    throw ApiErrors.unauthorized(4013, "管理员认证失败");
  }
}