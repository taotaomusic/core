import { Module } from "@nestjs/common";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { ApkService } from "./apk.service";
import { ReleaseAdminController } from "./release-admin.controller";
import { ReleaseController } from "./release.controller";
import { ReleaseRepository } from "./release.repository";
import { ReleaseService } from "./release.service";

/** 热更新：客户端引导、安装包分发与发布管理。 */
@Module({
  controllers: [ReleaseController, ReleaseAdminController],
  providers: [ReleaseService, ReleaseRepository, ApkService, AdminTokenGuard],
})
export class ReleaseModule {}
