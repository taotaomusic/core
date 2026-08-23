import { Controller, Get, Param, ParseIntPipe, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import { TencentClient } from "../upstream/tencent.client";
import { SearchService } from "./search.service";
import { SongMapper } from "./song.mapper";
import { StreamService } from "./stream.service";

/**
 * 搜索、播放与歌词。
 *
 * 三个端点都自己写响应体（NDJSON 流、音频流、纯文本），所以全部标了 [RawResponse]。
 */
@Controller()
export class MusicController {
  constructor(
    private readonly search: SearchService,
    private readonly stream: StreamService,
    private readonly upstream: TencentClient,
    private readonly mapper: SongMapper,
  ) {}

  @RawResponse()
  @Get("search")
  async searchSongs(
    @Res() response: Response,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("limit") limit?: string,
    @Query("quality") quality?: string,
  ): Promise<void> {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const currentPage = Math.max(1, Number(page ?? 1));
    // 客户端历史参数名是 num，v3 上游叫 limit，两个都接受。
    const pageSize = Math.min(60, Math.max(1, Number(num ?? limit ?? 10)));
    await this.search.stream(response, trimmed, currentPage, pageSize, this.mapper.qualityOf(quality));
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
    const target = await this.stream.resolvePlayUrl(
      id,
      this.mapper.qualityOf(quality),
      type === undefined ? undefined : Number(type),
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
  ): Promise<void> {
    const rich = await this.upstream.requestLyric(id);
    if (format === "json") {
      response.status(200).json({ code: 0, message: "success", data: rich });
      return;
    }
    response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
    response.end(rich.lrc || rich.yrc);
  }
}
