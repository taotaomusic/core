import { Controller, Get, Param, ParseIntPipe, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import type { SessionUser } from "../common/request.types";
import { AppConfigService } from "../config/app-config.service";
import { TencentClient } from "../upstream/tencent.client";
import { SearchService } from "./search.service";
import { SongMapper } from "./song.mapper";
import { StreamService } from "./stream.service";

/** 搜索单页默认条数。客户端会显式要 60，这里的默认值只对手工调试生效。 */
const DEFAULT_PAGE_SIZE = 60;
const MAX_PAGE_SIZE = 60;

/**
 * 搜索、播放与歌词。
 *
 * 搜索、播放转发、歌词三个端点自己写响应体（NDJSON 流、音频流、纯文本），标了 [RawResponse]；
 * `/link` 和 `/info` 是普通 JSON，走统一信封。
 */
@Controller()
export class MusicController {
  constructor(
    private readonly config: AppConfigService,
    private readonly search: SearchService,
    private readonly stream: StreamService,
    private readonly upstream: TencentClient,
    private readonly mapper: SongMapper,
  ) {}

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
  ) {
    const requested = this.mapper.qualityOf(quality);
    // 先问一次可用档位，能直接命中真实存在的档，省掉逐级试错的多次请求。
    // info 自己失败不算致命，退回音质阶梯。
    const available = await this.upstream
      .requestSongInfo({ id, mid })
      .then((info) => new Set(info.tiers.filter((tier) => tier.size > 0).map((tier) => tier.type)))
      .catch(() => undefined);

    const link = await this.upstream.resolveLink(
      { id, mid, type: this.optionalInt(type) },
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
  async info(@Param("id", ParseIntPipe) id: number, @Query("mid") mid?: string) {
    const info = await this.upstream.requestSongInfo({ id, mid });
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
    @Query("type") type?: string,
  ): Promise<void> {
    const target = await this.stream.resolvePlayUrl(id, this.mapper.qualityOf(quality), this.optionalInt(type));
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
  ): Promise<void> {
    const rich = await this.upstream.requestLyric(id);
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
}
