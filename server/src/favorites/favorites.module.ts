import { Module } from "@nestjs/common";
import { FavoritesController } from "./favorites.controller";
import { FavoritesRepository } from "./favorites.repository";

/** 导出 [FavoritesRepository]：搜索结果要内联「是否已收藏」，需要跨模块注入。 */
@Module({
  controllers: [FavoritesController],
  providers: [FavoritesRepository],
  exports: [FavoritesRepository],
})
export class FavoritesModule {}
