import { Module } from "@nestjs/common";
import { ReleaseModule } from "../release/release.module";
import { UpstreamModule } from "../upstream/upstream.module";
import { SongShareController } from "./song-share.controller";
import { SongShareRepository } from "./song-share.repository";
import { SongShareService } from "./song-share.service";

/** 短链、分享页元数据与受限试听的独立业务模块。 */
@Module({
  imports: [ReleaseModule, UpstreamModule],
  controllers: [SongShareController],
  providers: [SongShareRepository, SongShareService],
})
export class SongShareModule {}
