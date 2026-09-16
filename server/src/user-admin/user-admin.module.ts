import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { UserAdminController } from "./user-admin.controller";
import { UserAdminRepository } from "./user-admin.repository";

/** 管理端用户资料与听歌统计查询。 */
@Module({
  imports: [AdminAuthModule],
  controllers: [UserAdminController],
  providers: [UserAdminRepository],
})
export class UserAdminModule {}
