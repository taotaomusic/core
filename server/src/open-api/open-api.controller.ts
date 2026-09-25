import { Controller, Get, Param, ParseIntPipe, Query, Req, Res, UseGuards } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import { AppConfigService } from "../config/app-config.service";
import type { SearchSource } from "../music/search.service";
import { SearchService } from "../music/search.service";
import type { Song } from "../music/song.mapper";
import { SongMapper } from "../music/song.mapper";
import { MUSIC_SOURCES } from "../upstream/music-source.client";
import type { MusicSource, SongKey } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
import { ApiKeyGuard } from "./open-api-key.guard";

/** 搜索单页默认/上限条数，与内部 MusicController 保持同一口径。 */
const DEFAULT_PAGE_SIZE = 60;
const MAX_PAGE_SIZE = 60;

/**
 * 对外开放的搜歌接口（`/api/v1/open/...`）。
 *
 * 全部路由都要开放 API Key，没有免鉴权入口，因此：
 * - 类级 [Public]：跳过全局 AccessTokenGuard（开放调用方没有用户令牌）；
 * - 类级 [RateLimit]("open-api")：先按地址/key 做限流；
 * - 类级 [UseGuards]([ApiKeyGuard])：再校验 key 本身。
 *
 * **ApiKeyGuard 刻意挂在类上而不是逐方法标注**：本控制器带类级 `@Public()`，
 * 一旦有人新增路由却忘了挂守卫，它就同时躲过全局 AccessTokenGuard 与 key 校验，
 * 直接对外裸奔 —— 这类「漏标注 = 漏洞」不会报错、类型检查也看不出来。挂到类上后
 * 新增路由自动受保护。
 *
 * 这与管理端 [AdminGuarded]「不能挂类上」不冲突 —— 那条约束针对的是
 * 「同一控制器里混有公开路由（如 login）」的场景，本控制器没有此类路由，
 * 全部方法都必须校验 key，正好适合类级守卫。
 *
 * 小工具（positiveIntOr / sourceOf 等）刻意在本文件内复制一份而**不 import MusicController**：
 * 跨控制器 import 私有方法会把两个不相干的路由耦合在一起，口径变化时两边都得改。
 */
@Public()
@RateLimit("open-api")
@UseGuards(ApiKeyGuard)
@Controller("open")
export class OpenApiController {
  constructor(
    private readonly config: AppConfigService,
    private readonly search: SearchService,
    private readonly registry: MusicSourceRegistry,
    private readonly mapper: SongMapper,
  ) {}

  /**
   * JSON 形态搜索。
   *
   * 走统一信封（不标 RawResponse），`data = { songs, meta }`。
   * 返回前经 [toOpenSongs] 改写：去掉 audioUrl、lyricUrl 指向开放侧歌词路径、favorited 恒 false。
   */
  @Get("search")
  async searchSongs(
    @Res({ passthrough: true }) response: Response,
    @Req() request: Request,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("limit") limit?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ) {
    this.allowCrossOrigin(response);
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const collected = await this.search.collect(
      undefined,
      trimmed,
      this.positiveIntOr(page, 1),
      Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num ?? limit, DEFAULT_PAGE_SIZE)),
      this.mapper.qualityOf(quality),
      this.playBaseOf(request),
      this.searchSourceOf(source),
    );
    return { songs: this.toOpenSongs(collected.songs), meta: collected.meta };
  }

  /**
   * NDJSON 流式搜索，与内部 `/search` 同格式。
   *
   * [SearchService.stream] 自己 writeHead 并带 `access-control-allow-origin: *`，
   * 本方法不再额外设 CORS 头。userId 传 undefined —— 开放调用方没有用户身份，
   * 收藏查询整段跳过，`favorited` 恒 false。
   */
  @RawResponse()
  @Get("search/stream")
  async searchStream(
    @Res() response: Response,
    @Req() request: Request,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("limit") limit?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    await this.search.stream(
      response,
      undefined,
      trimmed,
      this.positiveIntOr(page, 1),
      Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num ?? limit, DEFAULT_PAGE_SIZE)),
      this.mapper.qualityOf(quality),
      this.playBaseOf(request),
      this.searchSourceOf(source),
    );
  }

  /**
   * 歌词。逻辑与 MusicController.lyrics 相同。
   *
   * 默认纯文本；`format=json` 时因标了 [RawResponse] 不会套信封，这里手动
   * `response.json({ code: 0, ... })` 给出与信封一致的形状。
   */
  @RawResponse()
  @Get("songs/:id/lyrics")
  async lyrics(
    @Res() response: Response,
    @Param("id", ParseIntPipe) id: number,
    @Query("format") format?: string,
    @Query("mid") mid?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    this.allowCrossOrigin(response);
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const rich = await this.registry.of(selectedSource).requestLyric(key);
    if (format === "json") {
      response.status(200).json({ code: 0, message: "success", data: rich });
      return;
    }
    response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
    response.end(rich.lrc || rich.yrc);
  }

  /**
   * 解析播放地址。逻辑与 MusicController.link 相同（含 supportsTierProbe 预探测）。
   *
   * 开放侧搜索结果不带 audioUrl，第三方拿元信息后来这里换直链。
   */
  @Get("songs/:id/link")
  async link(
    @Res({ passthrough: true }) response: Response,
    @Param("id", ParseIntPipe) id: number,
    @Query("quality") quality?: string,
    @Query("mid") mid?: string,
    @Query("type") type?: string,
    @Query("source") source?: string,
  ) {
    this.allowCrossOrigin(response);
    const requested = this.mapper.qualityOf(quality);
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const client = this.registry.of(selectedSource);
    // 先问一次可用档位，能直接命中真实存在的档，省掉逐级试错的多次请求。
    // info 自己失败不算致命，退回音质阶梯。不支持分档的音源跳过这一步。
    const available = client.supportsTierProbe
      ? await client
          .requestSongInfo(key)
          .then((info) => new Set(info.tiers.filter((tier) => tier.size > 0).map((tier) => tier.type)))
          .catch(() => undefined)
      : undefined;

    const link = await client.resolveLink(
      { ...key, type: this.optionalInt(type) },
      requested,
      undefined,
      available,
    );
    return {
      songId: id,
      url: link.url,
      quality: link.quality,
      requestedQuality: requested,
      kbps: link.kbps,
      fallback: link.quality !== requested,
    };
  }

  /**
   * 开放侧 Song 改写。
   *
   * - 去掉 `audioUrl`：外部拿不到内部播放代理的权限，播放请走 `/open/songs/:id/link`。
   * - `lyricUrl` 指向 `/api/v1/open/songs/{id}/lyrics`（开放侧路径），id 为 0 时写 0，同内部逻辑。
   * - `favorited` 强制 false：开放调用方没有用户身份。
   */
  private toOpenSongs(songs: Song[]): Array<Omit<Song, "audioUrl">> {
    return songs.map((song) => {
      const { audioUrl: _audioUrl, ...rest } = song;
      const id = song.id > 0 ? song.id : 0;
      const params = new URLSearchParams({ source: song.source });
      if (song.mid) params.set("mid", song.mid);
      return {
        ...rest,
        favorited: false,
        lyricUrl: song.lyricUrl === undefined
          ? undefined
          : `/api/v1/open/songs/${id}/lyrics?${params}`,
      };
    });
  }

  /** 开放 JSON 端点的 CORS：通配来源，并放行 key 相关请求头。 */
  private allowCrossOrigin(response: Response): void {
    response.setHeader("access-control-allow-origin", "*");
    response.setHeader("access-control-allow-headers", "x-api-key, authorization, content-type");
  }

  /**
   * 拼装 `audioUrl` 的基地址。与 MusicController.playBaseOf 同一口径：
   * 优先配置的对外地址，未配置时按请求推导。
   */
  private playBaseOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }

  /**
   * 查询参数转正整数，非法或缺省时用兜底值。与 MusicController 同一口径 ——
   * 不能直接 `Math.max(1, Number(value))`：`Number("abc")` 是 NaN 会原样传给上游。
   */
  private positiveIntOr(value: string | undefined, fallback: number): number {
    if (value === undefined || value.trim() === "") return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
  }

  private optionalInt(value: string | undefined): number | undefined {
    if (value === undefined || value.trim() === "") return undefined;
    const parsed = Number(value);
    return Number.isInteger(parsed) ? parsed : undefined;
  }

  /**
   * 单曲接口统一校验身份。与 MusicController.songKeyOf 同一口径：
   * id 必须和 source 一起用，数字 ID 只在所属音源内有意义。
   */
  private songKeyOf(id: number, mid: string | undefined, source: MusicSource): SongKey {
    const client = this.registry.of(source);
    const normalizedMid = mid?.trim();
    if (Number.isInteger(id) && id > 0) {
      return { id, ...(normalizedMid ? { mid: normalizedMid } : {}) };
    }
    if (!client.numericIdOnly && normalizedMid) return { mid: normalizedMid };
    throw ApiErrors.badRequest(
      4001,
      client.numericIdOnly ? `${client.displayName}歌曲必须提供正整数 ID` : "请提供歌曲 ID 或 mid",
    );
  }

  /**
   * 解析音源参数。与 MusicController.sourceOf 同一口径：
   * 默认 QQ 音乐，未知来源明确拒绝 —— 静默兜底会把别的音源的 ID 发给 QQ 上游。
   */
  private sourceOf(value: string | undefined): MusicSource {
    if (value === undefined || value === "") return "tencent";
    const matched = MUSIC_SOURCES.find((source) => source === value);
    if (!matched) throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    return matched;
  }

  /** 搜索默认聚合；其余单曲接口必须指定为某一个实际来源。与 MusicController 同一口径。 */
  private searchSourceOf(value: string | undefined): SearchSource {
    if (value === undefined || value === "" || value === "all") return "all";
    return this.sourceOf(value);
  }
}
