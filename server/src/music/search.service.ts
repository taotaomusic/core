import { Injectable } from "@nestjs/common";
import type { Response } from "express";
import { FavoritesRepository } from "../favorites/favorites.repository";
import { TencentClient } from "../upstream/tencent.client";
import type { UpstreamSong } from "../upstream/tencent.client";
import { NeteaseClient } from "../upstream/netease.client";
import { SongMapper } from "./song.mapper";

export type MusicSource = "tencent" | "netease";
export type SearchSource = MusicSource | "all";

@Injectable()
export class SearchService {
  constructor(
    private readonly upstream: TencentClient,
    private readonly netease: NeteaseClient,
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
    source: SearchSource = "all",
  ): Promise<void> {
    const sources: MusicSource[] = source === "all" ? ["tencent", "netease"] : [source];
    const attempts = await Promise.allSettled(
      sources.map(async (itemSource) => ({
        source: itemSource,
        result: await (itemSource === "netease" ? this.netease : this.upstream).searchSongs(keyword, page, limit),
      })),
    );
    // 聚合搜索允许单个上游短暂故障：另一个音源仍然可用。两个都失败时才沿用原来的上游错误。
    const results = attempts.flatMap((attempt) => (attempt.status === "fulfilled" ? [attempt.value] : []));
    if (results.length === 0) {
      const failed = attempts.find((attempt) => attempt.status === "rejected");
      throw (failed as PromiseRejectedResult | undefined)?.reason;
    }

    // 收藏状态一次查完，且必须在 writeHead **之前** ——
    // 响应头一旦发出，异常就只能截断连接，没法再返回干净的 JSON 错误。
    const favoritedBySource = new Map(
      await Promise.all(
        results.map(async ({ source: itemSource, result }) => [
          itemSource,
          await this.favorites.favoritedIds(
            userId,
            itemSource,
            result.list.flatMap((item) => {
              const identity = this.identityOf(item);
              return identity ? [identity] : [];
            }),
          ),
        ] as const),
      ),
    );

    response.writeHead(200, {
      "content-type": "application/x-ndjson; charset=utf-8",
      "cache-control": "no-cache",
      "access-control-allow-origin": "*",
      // 反向代理默认会把整个响应缓完再转发，那样逐行下发在客户端看来仍是一次性到达。
      // nginx 认这个头，且代理配置不在版本库里，只能由服务端主动声明。
      "x-accel-buffering": "no",
    });

    let count = 0;
    for (const { source: itemSource, result } of results) {
      const favorited = favoritedBySource.get(itemSource) ?? new Set<string>();
      for (const item of result.list) {
        const identity = this.identityOf(item);
        const song = this.mapper.toSong(
          item,
          playBase,
          quality,
          identity !== undefined && favorited.has(identity),
          itemSource,
        );
        response.write(`${JSON.stringify({ type: "song", data: song })}\n`);
        count++;
      }
    }
    // dropped 恒为 0：不再逐首探测，也就不再丢歌。装机的旧客户端会读这个字段，不能删。
    const meta = {
      page,
      limit,
      quality,
      count,
      dropped: 0,
      total: results.reduce((sum, { result }) => sum + result.total, 0),
      hasMore: results.some(({ result }) => result.nextPage !== null),
      source,
    };
    response.end(`${JSON.stringify({ type: "end", meta })}\n`);
  }

  /** 收藏与队列使用同一稳定身份：优先正数字 ID，否则退回上游 mid。 */
  private identityOf(item: UpstreamSong): string | undefined {
    const id = Number(item.songID);
    if (Number.isInteger(id) && id > 0) return String(id);
    const mid = String(item.songMID ?? "").trim();
    return mid || undefined;
  }
}
