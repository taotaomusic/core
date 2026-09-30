import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { AuthModule } from "../auth/auth.module";
import { UserAdminController } from "./user-admin.controller";
import { UserAdminRepository } from "./user-admin.repository";

/**
 * 管理端用户资料与听歌统计查询，以及后台创建账号。
 *
 * 创建用户要用 [AuthService] 哈希密码（scrypt 参数必须与注册一致）并写入
 * [UsersRepository]；这两个提供者由 [AuthModule] 导出，依赖在声明 Controller
 * 的模块里解析，所以这里必须 imports AuthModule。
 */
@Module({
  imports: [AdminAuthModule, AuthModule],
  controllers: [UserAdminController],
  providers: [UserAdminRepository],
})
export class UserAdminModule {}
