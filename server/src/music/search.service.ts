import { Injectable } from "@nestjs/common";
import type { Response } from "express";
import { FavoritesRepository } from "../favorites/favorites.repository";
import { TencentClient } from "../upstream/tencent.client";
import { SongMapper } from "./song.mapper";

/** 收藏的渠道标识。客户端硬编码 `tencent`，两侧必须一致。 */
const FAVORITE_SOURCE = "tencent";

@Injectable()
export class SearchService {
  constructor(
    private readonly upstream: TencentClient,
    private readonly mapper: SongMapper,
    private readonly favorites: FavoritesRepository,
  ) {}

  /**
   * 搜索并以 NDJSON 流式返回。
   *
   * 响应必须是**裸 NDJSON**，不能被统一信封包住：客户端逐行取 `type` 字段，
   * 包了外壳后会取不到 → 0 首歌 + 无任何错误，表现为「搜不到东西」。
   *
   * 这里**不再解析播放地址**。早先每首歌都要向上游多要一次播放链接、还要探测首字节，
   * 20 首约 1.8 秒、60 首最坏能打 240 次上游请求；而客户端拿到后又会把这个地址丢掉，
   * 改用自己拼的播放地址。也就是说那些请求换来的只是「把拿不到地址的歌过滤掉」。
   * 现在改成客户端点播时再走 `/songs/{id}/link` 单独解析，搜索只剩一次上游请求。
   */
  async stream(
    response: Response,
    userId: number,
    keyword: string,
    page: number,
    limit: number,
    quality: number,
    playBase: string,
  ): Promise<void> {
    const result = await this.upstream.searchSongs(keyword, page, limit);

    // 收藏状态一次查完，且必须在 writeHead **之前** ——
    // 响应头一旦发出，异常就只能截断连接，没法再返回干净的 JSON 错误。
    const ids = result.list.map((item) => String(Number(item.songID))).filter((id) => id !== "0");
    const favorited = await this.favorites.favoritedIds(userId, FAVORITE_SOURCE, ids);

    response.writeHead(200, {
      "content-type": "application/x-ndjson; charset=utf-8",
      "cache-control": "no-cache",
      "access-control-allow-origin": "*",
      // 反向代理默认会把整个响应缓完再转发，那样逐行下发在客户端看来仍是一次性到达。
      // nginx 认这个头，且代理配置不在版本库里，只能由服务端主动声明。
      "x-accel-buffering": "no",
    });

    let count = 0;
    for (const item of result.list) {
      const song = this.mapper.toSong(item, playBase, quality, favorited.has(String(Number(item.songID))));
      response.write(`${JSON.stringify({ type: "song", data: song })}\n`);
      count++;
    }
    // dropped 恒为 0：不再逐首探测，也就不再丢歌。装机的旧客户端会读这个字段，不能删。
    const meta = { page, limit, quality, count, dropped: 0, total: result.total, hasMore: result.nextPage !== null };
    response.end(`${JSON.stringify({ type: "end", meta })}\n`);
  }
}
