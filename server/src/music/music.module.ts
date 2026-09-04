import { Module } from "@nestjs/common";
import { FavoritesModule } from "../favorites/favorites.module";
import { UpstreamModule } from "../upstream/upstream.module";
import { MusicController } from "./music.controller";
import { SearchService } from "./search.service";
import { SongMapper } from "./song.mapper";
import { StreamService } from "./stream.service";

@Module({
  imports: [UpstreamModule, FavoritesModule],
  controllers: [MusicController],
  providers: [SearchService, StreamService, SongMapper],
})
export class MusicModule {}
