import { Body, Controller, Delete, Get, Post, Query } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import type { SessionUser } from "../common/request.types";
import {
  PlaybackRepository,
  PlaybackHistoryRevisionError,
  PlaybackSessionIdentityError,
  type PlaybackSessionInput,
} from "./playback.repository";

const IDENTIFIER_PATTERN = /^[\w-]{1,128}$/;
const SOURCE_PATTERN = /^[a-z0-9_-]{2,32}$/i;
const MAX_LISTENED_MS = 24 * 60 * 60 * 1_000;
const MAX_FUTURE_SKEW_MS = 5 * 60 * 1_000;
const RECENT_LIMIT = 500;
const MAX_HISTORY_REVISION = 2_147_483_647;

/** 最近播放与听歌统计接口，全部受全局访问令牌守卫保护。 */
@Controller("playback")
export class PlaybackController {
  constructor(private readonly playback: PlaybackRepository) {}

  @Post("sessions")
  async report(@CurrentUser() user: SessionUser, @Body() body: Record<string, unknown>) {
    const input = this.sessionInputOf(body);
    try {
      return await this.playback.report(user.id, input);
    } catch (error) {
      if (error instanceof PlaybackSessionIdentityError) {
        throw ApiErrors.conflict(4095, error.message);
      }
      if (error instanceof PlaybackHistoryRevisionError) {
        throw ApiErrors.conflict(4096, error.message);
      }
      throw error;
    }
  }

  @Get("recent")
  async listRecent(@CurrentUser() user: SessionUser, @Query("limit") limitParam?: string) {
    const parsed = Number(limitParam ?? RECENT_LIMIT);
    const limit = Number.isInteger(parsed) ? Math.min(RECENT_LIMIT, Math.max(1, parsed)) : RECENT_LIMIT;
    return this.playback.listRecent(user.id, limit);
  }

  /** 独立状态端点，避免改变既有 recent 的裸数组响应契约。 */
  @Get("recent/state")
  historyState(@CurrentUser() user: SessionUser) {
    return this.playback.historyState(user.id);
  }

  @Get("stats")
  stats(@CurrentUser() user: SessionUser) {
    return this.playback.overallStats(user.id);
  }

  /** 单曲倒带日记：当前用户某一首歌的完整播放画像。source/songId 校验与上报会话一致。 */
  @Get("diary")
  diary(
    @CurrentUser() user: SessionUser,
    @Query("source") sourceParam?: string,
    @Query("songId") songIdParam?: string,
  ) {
    const source = this.sourceOf(sourceParam);
    const songId = this.identifierOf(songIdParam, "songId");
    return this.playback.diary(user.id, source, songId);
  }

  /** 只清空最近播放的可见列表，累计次数和听歌时间继续保留。 */
  @Delete("recent")
  clearRecent(@CurrentUser() user: SessionUser, @Query("marker") marker?: string) {
    // marker 对旧客户端可选；新客户端在本地持久化它，以便响应丢失后用同一标识重试。
    const clearMarker = marker === undefined ? undefined : this.identifierOf(marker, "marker");
    return this.playback.clearRecent(user.id, clearMarker);
  }

  private sessionInputOf(body: Record<string, unknown>): PlaybackSessionInput {
    const sessionId = this.identifierOf(body?.sessionId, "sessionId");
    const deviceId = this.identifierOf(body?.deviceId, "deviceId");
    const source = this.sourceOf(body?.source);
    const songId = this.identifierOf(body?.songId, "songId");
    // 历史版本是可选字段，缺失时视作旧客户端的第 0 代；这样部署服务端不会打断已发布
    // 客户端的统计上报。新客户端必须先拉 /recent/state 并随会话快照一起持久化该版本。
    const historyRevision = body?.historyRevision === undefined
      ? 0
      : this.integerOf(body.historyRevision, "historyRevision", 0, MAX_HISTORY_REVISION);
    const startedAt = this.timestampOf(body?.startedAt, "startedAt");
    const lastPlayedAt = this.timestampOf(body?.lastPlayedAt, "lastPlayedAt");
    if (lastPlayedAt < startedAt) throw ApiErrors.badRequest(4006, "lastPlayedAt 不能早于 startedAt");
    const listenedMs = this.integerOf(body?.listenedMs, "listenedMs", 0, MAX_LISTENED_MS);
    if (listenedMs > lastPlayedAt - startedAt + MAX_FUTURE_SKEW_MS) {
      throw ApiErrors.badRequest(4006, "listenedMs 不能明显超过会话经过时间");
    }
    if (body?.completed !== undefined && typeof body.completed !== "boolean") {
      throw ApiErrors.badRequest(4006, "completed 必须是布尔值");
    }
    const completed = body?.completed === true;
    const durationSeconds = body?.durationSeconds === undefined || body.durationSeconds === null
      ? null
      : this.integerOf(body.durationSeconds, "durationSeconds", 1, 24 * 60 * 60);

    return {
      sessionId,
      deviceId,
      source,
      songId,
      historyRevision,
      startedAt,
      lastPlayedAt,
      listenedMs,
      completed,
      durationSeconds,
    };
  }

  private identifierOf(value: unknown, label: string): string {
    const text = typeof value === "string" ? value.trim() : "";
    if (!IDENTIFIER_PATTERN.test(text)) throw ApiErrors.badRequest(4006, `${label} 不合法`);
    return text;
  }

  private sourceOf(value: unknown): string {
    const source = typeof value === "string" ? value.trim().toLowerCase() : "";
    if (!SOURCE_PATTERN.test(source)) throw ApiErrors.badRequest(4006, "source 不合法");
    return source;
  }

  private timestampOf(value: unknown, label: string): number {
    const timestamp = this.integerOf(value, label, 1, Number.MAX_SAFE_INTEGER);
    if (timestamp > Date.now() + MAX_FUTURE_SKEW_MS) throw ApiErrors.badRequest(4006, `${label} 超出允许时间范围`);
    return timestamp;
  }

  private integerOf(value: unknown, label: string, min: number, max: number): number {
    if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) {
      throw ApiErrors.badRequest(4006, `${label} 必须是 ${min} 至 ${max} 的整数`);
    }
    return value;
  }

}
