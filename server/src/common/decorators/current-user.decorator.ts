import { createParamDecorator, type ExecutionContext } from "@nestjs/common";
import type { AuthenticatedRequest, SessionUser } from "../request.types";

/**
 * 取当前登录用户。
 * 非公开路由由全局守卫保证已挂上，公开路由拿到的可能是 undefined。
 */
export const CurrentUser = createParamDecorator(
  (_data: unknown, context: ExecutionContext): SessionUser | undefined =>
    context.switchToHttp().getRequest<AuthenticatedRequest>().user,
);
