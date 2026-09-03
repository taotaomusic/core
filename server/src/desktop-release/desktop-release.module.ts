import { Module } from "@nestjs/common";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { DesktopArtifactService } from "./desktop-artifact.service";
import { DesktopDiffService } from "./desktop-diff.service";
import { DesktopReleaseAdminController } from "./desktop-release-admin.controller";
import { DesktopReleaseController } from "./desktop-release.controller";
import { DesktopReleaseRepository } from "./desktop-release.repository";
import { DesktopReleaseService } from "./desktop-release.service";

@Module({
  controllers: [DesktopReleaseController, DesktopReleaseAdminController],
  providers: [
    DesktopReleaseRepository,
    DesktopReleaseService,
    DesktopArtifactService,
    DesktopDiffService,
    AdminTokenGuard,
  ],
})
export class DesktopReleaseModule {}
