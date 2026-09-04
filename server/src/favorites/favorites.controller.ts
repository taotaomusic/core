import { Controller, Delete, Get, Param, Post } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import type { SessionUser } from "../common/request.types";
import { FavoritesRepository } from "./favorites.repository";

const SOURCE_PATTERN = /^[a-z0-9_-]{2,32}$/i;
const SONG_ID_PATTERN = /^[\w-]{1,128}$/;

/**
 * 收藏接口。
 *
 * 这两条写接口客户端**不发请求体、不设 Content-Type**，
 * 所以不能挂 `@Body()` —— 空体会被 ValidationPipe 打成 400。
 * 响应体客户端也不读，只要求 2xx。
 */
@Controller("favorites")
export class FavoritesController {
  constructor(private readonly favorites: FavoritesRepository) {}

  @Get()
  list(@CurrentUser() user: SessionUser) {
    // data 必须是裸数组：客户端用 optJSONArray 读，包成 { items: [...] } 会被当成空列表。
    return this.favorites.list(user.id);
  }

  @Post(":source/:songId")
  add(@CurrentUser() user: SessionUser, @Param("source") source: string, @Param("songId") songId: string) {
    this.assertIdentifiers(source, songId);
    return this.favorites.add(user.id, source.toLowerCase(), songId);
  }

  @Delete(":source/:songId")
  async remove(@CurrentUser() user: SessionUser, @Param("source") source: string, @Param("songId") songId: string) {
    this.assertIdentifiers(source, songId);
    return { removed: await this.favorites.remove(user.id, source.toLowerCase(), songId) };
  }

  private assertIdentifiers(source: string, songId: string): void {
    if (!SOURCE_PATTERN.test(source) || !SONG_ID_PATTERN.test(songId)) {
      throw ApiErrors.badRequest(4004, "渠道或歌曲 ID 不合法");
    }
  }
}
