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
  // 分享页的试听也走这套 Range 转发，模块间复用同一个实例，不要在别处再 new 一个。
  // 开放接口（open-api）复用搜索流与映射器，同样从这里注入。
  exports: [StreamService, SearchService, SongMapper],
})
export class MusicModule {}
