import { Controller, Get, Param, ParseIntPipe, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import type { SessionUser } from "../common/request.types";
import { AppConfigService } from "../config/app-config.service";
import { MUSIC_SOURCES } from "../upstream/music-source.client";
import type { MusicSource, SongKey } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
import type { SearchSource } from "./search.service";
import { SearchService } from "./search.service";
import { SongMapper } from "./song.mapper";
import { StreamService } from "./stream.service";

/** 搜索单页默认条数。客户端会显式要 60，这里的默认值只对手工调试生效。 */
const DEFAULT_PAGE_SIZE = 60;
const MAX_PAGE_SIZE = 60;
const MAX_INFO_BATCH_SIZE = 60;
const DEFAULT_SUGGESTION_SIZE = 10;
const MAX_SUGGESTION_SIZE = 20;

/**
 * 搜索、播放与歌词。
 *
 * 搜索、播放转发、歌词三个端点自己写响应体（NDJSON 流、音频流、纯文本），标了 [RawResponse]；
 * `/link` 和 `/info` 是普通 JSON，走统一信封。
 *
 * 上游一律经 [MusicSourceRegistry] 取，**这个文件里不允许出现按 `source` 分支的逻辑**：
 * 判断该由适配器自己的能力标记（`numericIdOnly` / `supportsTierProbe`）表达，
 * 否则每加一个音源就要回来改一次。
 */
@Controller()
export class MusicController {
  constructor(
    private readonly config: AppConfigService,
    private readonly search: SearchService,
    private readonly stream: StreamService,
    private readonly registry: MusicSourceRegistry,
    private readonly mapper: SongMapper,
  ) {}

  /**
   * 搜索框联想词。返回普通 JSON 信封，`data` 是按上游顺序排列的字符串数组。
   * 当前只有酷我（波点）提供此能力，因此默认 source=kuwo。
   */
  @Get("search/suggestions")
  async searchSuggestions(
    @Query("keyword") keyword?: string,
    @Query("limit") limit?: string,
    @Query("source") source?: string,
  ): Promise<string[]> {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const client = this.registry.of(this.sourceOf(source ?? "kuwo"));
    if (!client.searchSuggestions) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持搜索联想`);
    }
    return client.searchSuggestions(
      trimmed,
      Math.min(MAX_SUGGESTION_SIZE, this.positiveIntOr(limit, DEFAULT_SUGGESTION_SIZE)),
    );
  }

  /** 获取官方搜索首页热词，默认取酷我（波点）数据。 */
  @Get("search/hot")
  async searchHotKeywords(
    @Query("limit") limit?: string,
    @Query("source") source?: string,
  ) {
    const client = this.registry.of(this.sourceOf(source ?? "kuwo"));
    if (!client.searchHotKeywords) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持热搜`);
    }
    return client.searchHotKeywords(
      Math.min(MAX_SUGGESTION_SIZE, this.positiveIntOr(limit, DEFAULT_SUGGESTION_SIZE)),
    );
  }

  @RawResponse()
  @Get("search")
  async searchSongs(
    @Req() request: Request,
    @Res() response: Response,
    @CurrentUser() user: SessionUser,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("limit") limit?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    // 客户端历史参数名是 num，v3 上游叫 limit，两个都接受。
    await this.search.stream(
      response,
      user.id,
      trimmed,
      this.positiveIntOr(page, 1),
      Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num ?? limit, DEFAULT_PAGE_SIZE)),
      this.mapper.qualityOf(quality),
      this.playBaseOf(request),
      this.searchSourceOf(source),
    );
  }

  /**
   * 解析播放地址。
   *
   * 从搜索里拆出来的独立接口：搜索只给元信息，客户端点播时才来要地址。
   * 返回的是**上游直链**，客户端直接拉 QQ 的 CDN，音频字节不再经过本服务。
   *
   * `mid` 与 `type` 由搜索结果原样带回：`songID` 为 0 的歌只能靠 `mid` 解析，
   * 而 `type` 不带会让部分歌曲拿不到地址。
   *
   * 拿不到地址时按既有约定走 **502**，绝不能 401 —— 那会触发客户端的续期重放，
   * 二次失败后把用户踢回登录页。
   */
  @Get("songs/:id/link")
  async link(
    @Param("id", ParseIntPipe) id: number,
    @Query("quality") quality?: string,
    @Query("mid") mid?: string,
    @Query("type") type?: string,
    @Query("source") source?: string,
  ) {
    const requested = this.mapper.qualityOf(quality);
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const client = this.registry.of(selectedSource);
    // 先问一次可用档位，能直接命中真实存在的档，省掉逐级试错的多次请求。
    // info 自己失败不算致命，退回音质阶梯。不支持分档的音源跳过这一步，
    // 否则只是白白多打一次上游。
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
   * 歌曲信息与可用音质档位。
   *
   * 客户端的音质选择器用它只列出这首歌**真实存在**的档位，避免选了无损却静默降到
   * 标准音质；`size` 同时用来提示流量。
   */
  @Get("songs/:id/info")
  async info(
    @Param("id", ParseIntPipe) id: number,
    @Query("mid") mid?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.sourceOf(source);
    return this.songInfo(this.songKeyOf(id, mid, selectedSource), selectedSource);
  }

  /**
   * 最近播放补全资料的批量入口。客户端一次最多请求 60 首，服务端分批并发访问上游，
   * 避免新设备拉 500 条历史时发出 500 个移动端 HTTP 请求。
   *
   * 单首上游资料失败不应让整个批次退化为移动端 N 次逐首请求：成功项照常返回，
   * 缺失项由客户端以本地可播放占位项展示，下一次刷新再尝试补全。
   */
  @Get("songs/batch-info")
  async infoBatch(
    @Query("ids") ids?: string,
    @Query("mids") mids?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.sourceOf(source);
    const client = this.registry.of(selectedSource);
    const uniqueIds = [...new Set((ids ?? "").split(",").map((value) => Number(value.trim())))]
      .filter((value) => Number.isInteger(value) && value > 0);
    const uniqueMids = [
      ...new Set(
        (mids ?? "")
          .split(",")
          .map((value) => value.trim())
          .filter(Boolean),
      ),
    ];
    if (client.numericIdOnly && uniqueMids.length > 0) {
      throw ApiErrors.badRequest(4001, `${client.displayName}歌曲必须提供正整数 ID`);
    }
    const keys: SongKey[] = [
      ...uniqueIds.map((id) => ({ id })),
      ...uniqueMids.map((mid) => ({ mid })),
    ];
    if (keys.length === 0) throw ApiErrors.badRequest(4001, "请提供歌曲 ID 或 mid");
    if (keys.length > MAX_INFO_BATCH_SIZE) {
      throw ApiErrors.badRequest(4001, `单次最多查询 ${MAX_INFO_BATCH_SIZE} 首歌曲`);
    }
    const songs = [];
    for (let index = 0; index < keys.length; index += 8) {
      const batch = await Promise.allSettled(
        keys.slice(index, index + 8).map((key) => this.songInfo(key, selectedSource)),
      );
      songs.push(...batch.flatMap((result) => (result.status === "fulfilled" ? [result.value] : [])));
    }
    return { songs };
  }

  private async songInfo(key: SongKey, source: MusicSource = "tencent") {
    const info = await this.registry.of(source).requestSongInfo({
      id: key.id,
      mid: key.mid,
    });
    return {
      songId: info.songID,
      mid: info.songMID,
      title: info.title,
      artist: info.singer,
      album: info.album,
      coverUrl: info.cover,
      vip: info.pay.includes("付费"),
      durationSeconds: info.interval,
      // size 为 0 的档位这首歌没有，直接不下发，客户端不用自己过滤。
      qualities: info.tiers
        .filter((tier) => tier.size > 0)
        .map((tier) => ({ quality: tier.type, label: tier.label, size: tier.size })),
    };
  }

  @RawResponse()
  @Get("songs/:id/play")
  async play(
    @Req() request: Request,
    @Res() response: Response,
    @Param("id", ParseIntPipe) id: number,
    @Query("quality") quality?: string,
    @Query("mid") mid?: string,
    @Query("type") type?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const target = await this.stream.resolvePlayUrl(
      { ...key, type: this.optionalInt(type) },
      this.mapper.qualityOf(quality),
      selectedSource,
    );
    await this.stream.proxy(request, response, target);
  }

  /**
   * 歌词。
   *
   * 默认返回**纯 LRC 文本** —— 装机的旧客户端把响应体直接当歌词展示，不能改成 JSON。
   * 只有显式带 `format=json` 才给出逐字时间轴（yrc）与翻译。
   *
   * 「没有歌词」由上游抛出并归成 502，**绝不能返回 401**：那会触发客户端的续期重放，
   * 二次失败后把用户踢回登录页。
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
   * 拼装 `audioUrl` 的基地址。
   * 优先用配置的对外地址，未配置时按请求推导 —— 与安装包下载地址同一套逻辑。
   */
  private playBaseOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }

  /**
   * 查询参数转正整数，非法或缺省时用兜底值。
   *
   * 不能直接 `Math.max(1, Number(value))`：`Number("abc")` 是 NaN，而 `Math.max` 会
   * 把 NaN 原样传下去，最终拼出字面量 `page=NaN` 打给上游。
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
   * 单曲接口统一校验身份。
   *
   * **`id` 必须和 `source` 一起用**：数字 ID 只在所属音源内有意义，同一个数字在
   * QQ 和酷我里是两首完全不同的歌，混用不会报错、只会安静地返回另一首歌。
   *
   * 哪些音源允许 mid-only 由适配器的 `numericIdOnly` 决定，这里不写按音源的分支 ——
   * 新增音源时只需要在适配器上标一个标记。
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
   * 解析音源参数。默认 QQ 音乐，未知来源必须明确拒绝 ——
   * 静默兜底会把别的音源的 ID 发给 QQ 上游。
   */
  private sourceOf(value: string | undefined): MusicSource {
    if (value === undefined || value === "") return "tencent";
    const matched = MUSIC_SOURCES.find((source) => source === value);
    if (!matched) throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    return matched;
  }

  /** 搜索默认聚合；其余单曲接口必须指定为某一个实际来源。 */
  private searchSourceOf(value: string | undefined): SearchSource {
    if (value === undefined || value === "" || value === "all") return "all";
    return this.sourceOf(value);
  }
}
