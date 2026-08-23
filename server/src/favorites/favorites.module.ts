import { Module } from "@nestjs/common";
import { FavoritesController } from "./favorites.controller";
import { FavoritesRepository } from "./favorites.repository";

@Module({
  controllers: [FavoritesController],
  providers: [FavoritesRepository],
})
export class FavoritesModule {}
