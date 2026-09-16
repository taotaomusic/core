import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { AnnouncementController } from "./announcement.controller";
import { AnnouncementRepository } from "./announcement.repository";

/** 公告公开读取与后台发布模块。 */
@Module({
  imports: [AdminAuthModule],
  controllers: [AnnouncementController],
  providers: [AnnouncementRepository],
})
export class AnnouncementModule {}
