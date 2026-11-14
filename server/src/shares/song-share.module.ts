import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { MusicModule } from "../music/music.module";
import { ReleaseModule } from "../release/release.module";
import { UpstreamModule } from "../upstream/upstream.module";
import { ShareAdminController } from "./share-admin.controller";
import { ShareAdminRepository } from "./share-admin.repository";
import { SongShareController } from "./song-share.controller";
import { SongShareRepository } from "./song-share.repository";
import { SongShareService } from "./song-share.service";

/** 短链、分享页元数据、受限试听与管理端维护的独立业务模块。 */
@Module({
  // MusicModule 提供 StreamService（Range 转发），试听与播放共用同一条链路。
  // AdminAuthModule 提供管理守卫与审计服务 —— 依赖在声明 Controller 的模块里解析，必须显式导入。
  imports: [AdminAuthModule, ReleaseModule, UpstreamModule, MusicModule],
  controllers: [SongShareController, ShareAdminController],
  providers: [SongShareRepository, SongShareService, ShareAdminRepository],
})
export class SongShareModule {}
