import { Injectable, type CanActivate, type ExecutionContext } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { ApiErrors } from "../common/api.exception";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { ROLES_KEY } from "./roles.decorator";

/**
 * 管理员角色守卫。
 *
 * 必须排在 [AdminAuthGuard] 之后 —— 用 `@AdminGuarded()` 时这个顺序已经内置：
 * 它只读 `request.adminUser` 做判断，自己不解析凭据。排反了会让所有角色校验
 * 退化成「无身份」而一律拒绝。
 *
 * 没标注 `@RequireRole` 的路由一律放行 —— 默认拒绝会让所有历史路由立刻 403，
 * 而本模块的写操作本来就逐个标了角色。
 */
@Injectable()
export class RolesGuard implements CanActivate {
  constructor(private readonly reflector: Reflector) {}

  canActivate(context: ExecutionContext): boolean {
    const requiredRoles = this.reflector.getAllAndOverride<string[]>(ROLES_KEY, [
      context.getHandler(), context.getClass(),
    ]);
    if (!requiredRoles || requiredRoles.length === 0) return true;

    const request = context.switchToHttp().getRequest<AdminAuthenticatedRequest>();
    const admin = request.adminUser;
    // 走到这里还没有身份，说明守卫链配错了，按未认证处理而不是放行。
    if (!admin) throw ApiErrors.unauthorized(4013, "管理员认证失败");

    // super_admin 拥有所有权限
    if (admin.role === "super_admin") return true;
    if (requiredRoles.includes(admin.role)) return true;

    throw ApiErrors.forbidden(4030, `权限不足，该操作需要 ${requiredRoles.join(" / ")} 角色`);
  }
}
