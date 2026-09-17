import { UseGuards } from "@nestjs/common";
import { AdminAuthGuard } from "./admin-auth.guard";
import { RolesGuard } from "./roles.guard";

/**
 * 需要管理员会话（以及其后的角色校验）的路由。
 *
 * **只能挂在方法上，不能挂在控制器类上。** 类级 `@UseGuards` 对同一个控制器里
 * 的所有路由一视同仁，包括公开路由：一旦某个控制器将来加了 `login` 之类的
 * 免鉴权入口，类级守卫会先于处理器把它 401 掉，而登录时用户手里本来就没有凭据。
 * 这类故障的表现是「登录接口恒 401」，排查成本很高，因此在写法的源头就排除掉。
 *
 * 顺序不能颠倒：[RolesGuard] 依赖 [AdminAuthGuard] 写在请求上的 `adminUser`，
 * 排反了会让所有角色校验退化成「无身份」而一律拒绝。
 *
 * 使用方所在模块必须 `imports: [AdminAuthModule]` —— Nest 在**声明 Controller
 * 的模块**里解析守卫的构造器依赖，不是在提供守卫的模块里。
 */
export const AdminGuarded = () => UseGuards(AdminAuthGuard, RolesGuard);
