import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { ApiErrors } from "../common/api.exception";
import { AdminAuthService } from "./admin-auth.service";
import { ALLOW_PENDING_PASSWORD_KEY } from "./allow-pending-password.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";

/**
 * 管理端认证守卫。
 *
 * 只接受登录后签发的会话令牌（`Authorization: Bearer`）。历史上还接受
 * `.env` 里的静态 `X-Admin-Token`，但那把钥匙会同时绕过 2FA、IP 白名单、
 * 会话撤销与审计归属，且合成的是固定的 `super_admin` 身份，已整体移除。
 *
 * 另外承担「首次登录强制改密」的拦截：账号 `must_change_password = 1` 时，
 * 除标了 [AllowPendingPasswordChange] 的路由外一律 403/4031。
 *
 * 拦截放在守卫里而不是控制器辅助函数里，是因为业务控制器只通过
 * `audit.record(request, ...)` 使用 `request.adminUser`，不调用任何控制器内的
 * 取身份方法 —— 放守卫里才能覆盖全部当前与未来的管理路由。
 */
@Injectable()
export class AdminAuthGuard implements CanActivate {
  constructor(
    private readonly auth: AdminAuthService,
    private readonly reflector: Reflector,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    const request = context.switchToHttp().getRequest<AdminAuthenticatedRequest>();
    const authHeader = String(request.headers["authorization"] ?? "");
    if (!authHeader.startsWith("Bearer ")) {
      throw ApiErrors.unauthorized(4013, "管理员认证失败");
    }

    const session = await this.auth.validateSession(authHeader.slice(7));
    if (!session) throw ApiErrors.unauthorized(4013, "管理员认证失败");

    request.adminUser = session;

    const allowPending = this.reflector.getAllAndOverride<boolean>(ALLOW_PENDING_PASSWORD_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (session.must_change_password === 1 && !allowPending) {
      throw ApiErrors.forbidden(4031, "首次登录必须先修改初始密码");
    }
    return true;
  }
}
