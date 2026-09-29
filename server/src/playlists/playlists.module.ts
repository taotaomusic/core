import { Module } from "@nestjs/common";
import { UpstreamModule } from "../upstream/upstream.module";
import { PlaylistsController } from "./playlists.controller";
import { PlaylistCoverService } from "./playlists-cover.service";
import { PlaylistsRepository } from "./playlists.repository";

/** 云端歌单模块：歌单与歌曲顺序都按用户隔离并持久化在 PostgreSQL。 */
@Module({
  // UpstreamModule 提供封面回源用的 MusicSourceRegistry（读取路径上的自愈兜底）
  imports: [UpstreamModule],
  controllers: [PlaylistsController],
  providers: [PlaylistsRepository, PlaylistCoverService],
  exports: [PlaylistsRepository],
})
export class PlaylistsModule {}
