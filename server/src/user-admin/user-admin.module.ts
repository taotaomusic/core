import { Module } from "@nestjs/common";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { UserAdminController } from "./user-admin.controller";
import { UserAdminRepository } from "./user-admin.repository";

/** 管理端用户资料与听歌统计查询。 */
@Module({
  controllers: [UserAdminController],
  providers: [UserAdminRepository, AdminTokenGuard],
})
export class UserAdminModule {}
