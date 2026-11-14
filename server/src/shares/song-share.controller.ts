import {
  Body,
  Controller,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Post,
  Req,
  Res,
} from "@nestjs/common";import type { Request, Response } from "express";
import { ApiErrors, ApiException } from "../common/api.exception";
import { isMusicSource } from "../upstream/music-source.client";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import type { SessionUser } from "../common/request.types";
import { SongShareService, type CreateSongShareInput } from "./song-share.service";

/** 歌曲分享：创建需要登录，短链页面读取元数据和试听保持公开。 */
@Controller()
export class SongShareController {
  constructor(private readonly shares: SongShareService) {}

  @Post("shares/songs")
  @HttpCode(HttpStatus.CREATED)
  create(
    @CurrentUser() user: SessionUser,
    @Req() request: Request,
    @Body() body: Record<string, unknown>,
  ) {
    return this.shares.create(user.id, this.inputOf(body), request);
  }

  @Public()
  @RateLimit("app")
  @Get("public/shares/:token")
  metadata(@Param("token") token: string, @Req() request: Request) {
    return this.shares.metadata(token, request);
  }

  @Public()
  @RateLimit("app")
  @RawResponse()
  @Get("public/shares/:token/preview")
  preview(
    @Param("token") token: string,
    @Req() request: Request,
    @Res() response: Response,
  ): Promise<void> {
    return this.shares.streamPreview(token, request, response);
  }

  @Public()
  @RateLimit("app")
  @HttpCode(HttpStatus.NO_CONTENT)
  @Post("public/shares/:token/open-app")
  async openApp(@Param("token") token: string, @Req() request: Request): Promise<void> {
    await this.trackQuietly(() => this.shares.noteOpenApp(token, request));
  }

  @Public()
  @RateLimit("app")
  @HttpCode(HttpStatus.NO_CONTENT)
  @Post("public/shares/:token/play")
  async play(@Param("token") token: string): Promise<void> {
    await this.trackQuietly(() => this.shares.notePlay(token));
  }

  /**
   * 打点兜底：只吞「基础设施故障」，业务语义（非法短码 404/4045、限流 429）如实抛出。
   *
   * `.catch(() => undefined)` 一刀切会把非法短码的 404 也吞成 204，和 metadata
   * 端点的失效语义分叉 —— 分享页拿到 204 会以为打点成功，后台统计却缺一行。
   * 打点的静默边界是「统计坏了不影响分享页」，不是「错误不存在」。
   */
  private async trackQuietly(action: () => Promise<void>): Promise<void> {
    try {
      await action();
    } catch (error) {
      if (error instanceof ApiException) throw error;
      // 非业务异常（数据库抖动等）：静默吞掉，打点不阻塞分享页。
    }
  }

  private inputOf(body: Record<string, unknown>): CreateSongShareInput {
    // 白名单统一走 isMusicSource，不要手写音源数组 —— 每加一个音源都要回来改一遍。
    const source = String(body.source ?? "tencent").trim().toLowerCase();
    if (!isMusicSource(source)) {
      throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    }
    const remoteId = this.optionalPositiveInt(body.remoteId ?? body.songId, "歌曲 ID");
    const type = this.optionalInt(body.type, "歌曲类型");
    const mid = body.mid === undefined || body.mid === null ? undefined : String(body.mid).trim();
    if (mid && mid.length > 128) throw ApiErrors.badRequest(4001, "歌曲 mid 过长");
    return {
      source,
      ...(remoteId === undefined ? {} : { remoteId }),
      ...(mid ? { mid } : {}),
      ...(type === undefined ? {} : { type }),
    };
  }

  private optionalPositiveInt(value: unknown, label: string): number | undefined {
    if (value === undefined || value === null || value === "") return undefined;
    const parsed = Number(value);
    if (!Number.isSafeInteger(parsed) || parsed <= 0) {
      throw ApiErrors.badRequest(4001, `${label}必须是正整数`);
    }
    return parsed;
  }

  private optionalInt(value: unknown, label: string): number | undefined {
    if (value === undefined || value === null || value === "") return undefined;
    const parsed = Number(value);
    if (!Number.isSafeInteger(parsed)) throw ApiErrors.badRequest(4001, `${label}不合法`);
    return parsed;
  }
}
