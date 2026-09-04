import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { ApiErrors } from "../../common/api.exception";
import { PUBLIC_METADATA_KEY } from "../../common/decorators/public.decorator";
import type { AuthenticatedRequest } from "../../common/request.types";
import { AuthService } from "../auth.service";

/**
 * 全局访问令牌守卫。
 *
 * 默认所有路由都需要令牌，公开路由用 [Public] 显式标注 —— 这替掉了迁移前
 * 「靠门禁那行代码的位置来保证鉴权」的隐式约定，也是这次迁移的主要动机。
 *
 * 令牌无效一律 **401**，绝不能 403：客户端只对 401 触发续期重放
 * （`TencentMusicApi.authorized` 精确匹配 HTTP_UNAUTHORIZED），
 * 403 会让所有业务接口失去自动续期，用户随机看到「请求失败：HTTP 403」。
 */
@Injectable()
export class AccessTokenGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly auth: AuthService,
  ) {}

  async canActivate(context: ExecutionContext): Promise<boolean> {
    if (context.getType() !== "http") return true;
    const isPublic = this.reflector.getAllAndOverride<boolean>(PUBLIC_METADATA_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const user = await this.auth.authenticate(request.headers.authorization);

    // 公开路由也尝试解析令牌：客户端引导接口需要「带了有效令牌就按用户分桶，
    // 否则退回设备号」，所以这里解析成功就挂上，失败也照样放行。
    if (isPublic) {
      if (user) request.user = user;
      return true;
    }
    if (!user) throw ApiErrors.unauthorized(4010, "请先登录");
    request.user = user;
    return true;
  }
}
