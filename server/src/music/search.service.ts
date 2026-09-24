import { Injectable } from "@nestjs/common";
import type { Response } from "express";
import { FavoritesRepository } from "../favorites/favorites.repository";
import type { MusicSource } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
import type { UpstreamSong } from "../upstream/tencent.client";
import { SongMapper } from "./song.mapper";

/** 音源类型定义在上游适配层，这里转发一次，让既有 import 路径继续可用。 */
export type { MusicSource };
export type SearchSource = MusicSource | "all";

@Injectable()
export class SearchService {
  constructor(
    private readonly registry: MusicSourceRegistry,
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
   *
   * [userId] 可为 undefined —— 开放接口的第三方调用方没有用户身份，此时跳过收藏
   * 查询，所有歌曲的 `favorited` 恒为 false（绝不能把 undefined 传进 SQL）。
   */
  async stream(
    response: Response,
    userId: number | undefined,
    keyword: string,
    page: number,
    limit: number,
    quality: number,
    playBase: string,
    source: SearchSource = "all",
  ): Promise<void> {
    const clients = source === "all" ? this.registry.aggregated() : [this.registry.of(source)];
    const attempts = await Promise.allSettled(
      clients.map(async (client) => ({
        client,
        source: client.source,
        result: await client.searchSongs(keyword, page, limit),
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
    // 无用户身份（开放接口）时整段跳过，favorited 全部走 false。
    const favoritedBySource =
      userId === undefined
        ? new Map<MusicSource, Set<string>>()
        : new Map(
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
    for (const { client, source: itemSource, result } of results) {
      const favorited = favoritedBySource.get(itemSource) ?? new Set<string>();
      for (const item of result.list) {
        const identity = this.identityOf(item);
        const song = this.mapper.toSong(
          item,
          playBase,
          quality,
          identity !== undefined && favorited.has(identity),
          itemSource,
          !client.numericIdOnly,
        );
        response.write(`${JSON.stringify({ type: "song", data: song })}\n`);
        count++;
      }
    }
    // 被上游返回、但没有下发给客户端的条数。**目前恒为 0。**
    //
    // 酷我原本会在这里丢掉 `listen_fragment === "1"` 的歌，于是搜「周杰伦」上游 30 条
    // 全部被滤、客户端拿到 0 条并显示「没有找到相关歌曲」，而上层完全看不到原因。
    // 2026-09-19 改成**不丢**：可播性逐首下发（见 `Song.playable`），客户端置灰。
    // 依据是波点 App 自己也不滤 —— 同一关键词下它综合页的 `musicpage` 与我们的原始列表
    // 逐条一致，它把放不了的歌照常列出来。
    //
    // 字段与 `droppedBySource` 都**不能删**：装机的旧客户端会读 `dropped` 做算术，
    // 拿到 `undefined` 会算出 NaN。保留它们只为契约兼容，不再承载信息。
    const droppedBySource = Object.fromEntries(
      results
        .filter(({ result }) => result.dropped > 0)
        .map(({ source: itemSource, result }) => [itemSource, result.dropped]),
    );
    const meta = {
      page,
      limit,
      quality,
      count,
      dropped: results.reduce((sum, { result }) => sum + result.dropped, 0),
      droppedBySource,
      total: results.reduce((sum, { result }) => sum + result.total, 0),
      hasMore: results.some(({ result }) => result.nextPage !== null),
      source,
    };
    response.end(`${JSON.stringify({ type: "end", meta })}\n`);
  }

  /**
   * 搜索并把整页结果收进数组，供开放接口的 JSON 形态使用。
   *
   * 与 [stream] 同一条上游链路与同一批 Song 映射，差别只在落点：
   * stream 逐行写 NDJSON，这里返回 `{ songs, meta }` 交给统一信封。
   * 不写响应头，错误仍可正常变成干净的 JSON 错误体。
   */
  async collect(
    userId: number | undefined,
    keyword: string,
    page: number,
    limit: number,
    quality: number,
    playBase: string,
    source: SearchSource = "all",
  ): Promise<{ songs: Array<ReturnType<SongMapper["toSong"]>>; meta: Record<string, unknown> }> {
    const clients = source === "all" ? this.registry.aggregated() : [this.registry.of(source)];
    const attempts = await Promise.allSettled(
      clients.map(async (client) => ({
        client,
        source: client.source,
        result: await client.searchSongs(keyword, page, limit),
      })),
    );
    const results = attempts.flatMap((attempt) => (attempt.status === "fulfilled" ? [attempt.value] : []));
    if (results.length === 0) {
      const failed = attempts.find((attempt) => attempt.status === "rejected");
      throw (failed as PromiseRejectedResult | undefined)?.reason;
    }

    const favoritedBySource =
      userId === undefined
        ? new Map<MusicSource, Set<string>>()
        : new Map(
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

    const songs = [];
    for (const { client, source: itemSource, result } of results) {
      const favorited = favoritedBySource.get(itemSource) ?? new Set<string>();
      for (const item of result.list) {
        const identity = this.identityOf(item);
        songs.push(
          this.mapper.toSong(
            item,
            playBase,
            quality,
            identity !== undefined && favorited.has(identity),
            itemSource,
            !client.numericIdOnly,
          ),
        );
      }
    }
    const droppedBySource = Object.fromEntries(
      results
        .filter(({ result }) => result.dropped > 0)
        .map(({ source: itemSource, result }) => [itemSource, result.dropped]),
    );
    return {
      songs,
      meta: {
        page,
        limit,
        quality,
        count: songs.length,
        dropped: results.reduce((sum, { result }) => sum + result.dropped, 0),
        droppedBySource,
        total: results.reduce((sum, { result }) => sum + result.total, 0),
        hasMore: results.some(({ result }) => result.nextPage !== null),
        source,
      },
    };
  }

  /** 收藏与队列使用同一稳定身份：优先正数字 ID，否则退回上游 mid。 */
  private identityOf(item: UpstreamSong): string | undefined {
    const id = Number(item.songID);
    if (Number.isInteger(id) && id > 0) return String(id);
    const mid = String(item.songMID ?? "").trim();
    return mid || undefined;
  }
}
