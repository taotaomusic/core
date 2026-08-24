import { Module } from "@nestjs/common";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { AnnouncementController } from "./announcement.controller";
import { AnnouncementRepository } from "./announcement.repository";

/** 公告公开读取与后台发布模块。 */
@Module({ controllers: [AnnouncementController], providers: [AnnouncementRepository, AdminTokenGuard] })
export class AnnouncementModule {}
