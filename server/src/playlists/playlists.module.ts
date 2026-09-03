import { Module } from "@nestjs/common";
import { PlaylistsController } from "./playlists.controller";
import { PlaylistsRepository } from "./playlists.repository";

/** 云端歌单模块：歌单与歌曲顺序都按用户隔离并持久化在 PostgreSQL。 */
@Module({
  controllers: [PlaylistsController],
  providers: [PlaylistsRepository],
  exports: [PlaylistsRepository],
})
export class PlaylistsModule {}
