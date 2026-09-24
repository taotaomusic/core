import { Module } from "@nestjs/common";
import { MusicModule } from "../music/music.module";
import { ReleaseModule } from "../release/release.module";
import { UpstreamModule } from "../upstream/upstream.module";
import { SongShareController } from "./song-share.controller";
import { SongShareRepository } from "./song-share.repository";
import { SongShareService } from "./song-share.service";

/** 短链、分享页元数据与受限试听的独立业务模块。 */
@Module({
  // MusicModule 提供 StreamService（Range 转发），试听与播放共用同一条链路。
  imports: [ReleaseModule, UpstreamModule, MusicModule],
  controllers: [SongShareController],
  providers: [SongShareRepository, SongShareService],
})
export class SongShareModule {}
