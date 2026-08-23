import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { createHash, timingSafeEqual } from "node:crypto";
import { ApiErrors } from "../api.exception";
import { AppConfigService } from "../../config/app-config.service";
import type { AuthenticatedRequest } from "../request.types";

/**
 * 发布管理接口的静态令牌校验。
 *
 * 先做 sha256 再定长比较，避免用变长字符串比较泄漏时序信息。
 * **未配置 ADMIN_TOKEN 时一律拒绝** —— 忘了配就把发布能力暴露出去比拒绝服务危险得多。
 */
@Injectable()
export class AdminTokenGuard implements CanActivate {
  constructor(private readonly config: AppConfigService) {}

  canActivate(context: ExecutionContext): boolean {
    const expected = this.config.adminToken;
    if (!expected) throw ApiErrors.unauthorized(4013, "管理令牌无效");
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const provided = String(request.headers["x-admin-token"] ?? "");
    const a = createHash("sha256").update(provided).digest();
    const b = createHash("sha256").update(expected).digest();
    if (!timingSafeEqual(a, b)) throw ApiErrors.unauthorized(4013, "管理令牌无效");
    return true;
  }
}
