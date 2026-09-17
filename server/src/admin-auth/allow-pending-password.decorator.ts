import { SetMetadata } from "@nestjs/common";

/** 标记「账号仍在强制改密状态时也放行」的路由。 */
export const ALLOW_PENDING_PASSWORD_KEY = "allowPendingPassword";

/**
 * 允许在「首次登录必须改密」状态下访问的路由。
 *
 * 只应挂在三处：`GET /admin/auth/me`、`POST /admin/auth/change-password`，
 * 以及不需要守卫的 `logout`。其余管理接口一律由 [AdminAuthGuard] 拦成
 * 403/4031 —— 漏挂这个装饰器会把管理员锁在后台外面，多挂一个则等于
 * 给未改密的初始口令留一条可用的操作通道，两边都要克制。
 */
export const AllowPendingPasswordChange = () => SetMetadata(ALLOW_PENDING_PASSWORD_KEY, true);
