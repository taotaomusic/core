/**
 * 管理员角色与角色分组。
 *
 * 三档角色：
 * - `super_admin` 全权，含管理员账号的增删改；
 * - `admin` 能操作业务管理接口（发布、公告、用户、图片 Key），但不能动管理员账号；
 * - `viewer`（观察者）**只读** —— 能登录、能看列表和统计，不能做任何写操作。
 *
 * 分组集中在这里只有一个定义处。散落到各个控制器里迟早会出事：要么某个新接口
 * 漏标 `@RequireRole` 默认放行，要么某处手写角色数组时把 `viewer` 写进写权限。
 */
export const ADMIN_ROLES: string[] = ["super_admin", "admin", "viewer"];

/** 业务管理接口的读权限：三种角色都能看（发布、公告、用户、图片 Key 的列表）。 */
export const READ_ROLES: string[] = [...ADMIN_ROLES];

/**
 * 写操作：观察者被排除在外。
 *
 * 业务管理接口（发布、补丁、公告、用户、图片 Key）一律用它。这些接口此前只挂了
 * [AdminAuthGuard] 而没有角色校验，等于任何能登录后台的账号（包括 viewer）都能
 * 放量、删用户、导入密钥。
 */
export const WRITE_ROLES: string[] = ["super_admin", "admin"];

/**
 * 后台**自身**的读权限：管理员账号列表、审计日志。
 *
 * 与 [READ_ROLES] 的区别是这里不含观察者。审计日志会暴露「谁在什么时候改了什么」，
 * 管理员列表还会带出 IP 白名单，这两样不该给只读账号看 —— 观察者能进后台是为了
 * 看业务数据，不是来看别的管理员的操作记录的。
 */
export const PRIVILEGED_READ_ROLES: string[] = ["super_admin", "admin"];
