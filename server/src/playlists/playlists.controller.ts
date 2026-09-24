import {
  Body,
  Controller,
  Delete,
  Get,
  HttpCode,
  HttpStatus,
  Param,
  Patch,
  Post,
  Put,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { MUSIC_SOURCES } from "../upstream/music-source.client";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import type { SessionUser } from "../common/request.types";
import {
  MAX_PLAYLIST_SONGS,
  PlaylistOrderError,
  PlaylistsRepository,
  type PlaylistSongInput,
  type PlaylistSongKey,
} from "./playlists.repository";

const SOURCE_PATTERN = /^[a-z0-9_-]{2,32}$/i;
// 歌单项必须能被当前音乐路由解析；新增来源时先补齐 music 模块和双端客户端。
// 白名单来自上游适配层的 MUSIC_SOURCES，不要在业务模块里另立一份。
const SUPPORTED_SOURCES = new Set<string>(MUSIC_SOURCES);
const SONG_ID_PATTERN = /^[\w.-]{1,128}$/;
const MAX_NAME_LENGTH = 80;
const MAX_DESCRIPTION_LENGTH = 1_000;
const MAX_TEXT_LENGTH = 512;
const MAX_URL_LENGTH = 2_048;

/** 云端歌单接口。所有路由都默认经过全局访问令牌守卫，只能读写当前账号的数据。 */
@Controller("playlists")
export class PlaylistsController {
  constructor(private readonly playlists: PlaylistsRepository) {}

  @Get()
  list(@CurrentUser() user: SessionUser) {
    return this.playlists.list(user.id);
  }

  @Post()
  @HttpCode(HttpStatus.CREATED)
  create(@CurrentUser() user: SessionUser, @Body() body: Record<string, unknown>) {
    const name = this.requiredText(body?.name ?? body?.title, "歌单名称", MAX_NAME_LENGTH);
    const description = this.optionalText(body?.description, "歌单简介", MAX_DESCRIPTION_LENGTH) ?? "";
    const coverUrl = this.optionalUrl(body?.coverUrl);
    return this.playlists.create(user.id, name, description, coverUrl);
  }

  @Get(":playlistId")
  async detail(@CurrentUser() user: SessionUser, @Param("playlistId") playlistIdParam: string) {
    const playlist = await this.playlists.find(user.id, this.idOf(playlistIdParam));
    if (!playlist) throw this.notFound();
    return playlist;
  }

  /** 单独的歌曲列表别名，移动端可在不下载歌单元数据时刷新歌曲顺序。 */
  @Get(":playlistId/songs")
  async songs(@CurrentUser() user: SessionUser, @Param("playlistId") playlistIdParam: string) {
    const playlist = await this.playlists.find(user.id, this.idOf(playlistIdParam));
    if (!playlist) throw this.notFound();
    return playlist.songs;
  }

  @Patch(":playlistId")
  async update(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    const name = body?.name === undefined && body?.title === undefined
      ? undefined
      : this.requiredText(body?.name ?? body?.title, "歌单名称", MAX_NAME_LENGTH);
    const description = body?.description === undefined
      ? undefined
      : this.optionalText(body.description, "歌单简介", MAX_DESCRIPTION_LENGTH) ?? "";
    const coverUrl = body?.coverUrl === undefined ? undefined : this.optionalUrl(body.coverUrl);
    if (name === undefined && description === undefined && coverUrl === undefined) {
      throw ApiErrors.badRequest(4000, "请至少提供歌单名称、简介或封面");
    }
    const playlist = await this.playlists.update(
      user.id,
      this.idOf(playlistIdParam),
      name,
      description,
      coverUrl,
    );
    if (!playlist) throw this.notFound();
    return playlist;
  }

  /** PUT 是双端早期实现使用的兼容别名，语义仍是只更新提交的字段。 */
  @Put(":playlistId")
  updatePut(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    return this.update(user, playlistIdParam, body);
  }

  @Delete(":playlistId")
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@CurrentUser() user: SessionUser, @Param("playlistId") playlistIdParam: string): Promise<void> {
    if (!(await this.playlists.remove(user.id, this.idOf(playlistIdParam)))) throw this.notFound();
  }

  /** 添加歌曲到歌单末尾；重复歌曲只更新快照，接口保持幂等。 */
  @Post(":playlistId/songs")
  @HttpCode(HttpStatus.OK)
  async addSong(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    try {
      const playlist = await this.playlists.addSong(
        user.id,
        this.idOf(playlistIdParam),
        this.songInputOf(body),
      );
      if (!playlist) throw this.notFound();
      return playlist;
    } catch (error) {
      this.rethrowOrderError(error);
    }
  }

  @Delete(":playlistId/songs/:source/:songId")
  async removeSong(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Param("source") source: string,
    @Param("songId") songId: string,
  ) {
    const key = this.songKeyOf(source, songId);
    const result = await this.playlists.removeSong(user.id, this.idOf(playlistIdParam), key);
    if (!result) throw this.notFound();
    // 保留 removed 标志，客户端重复删除时可以安全地视作成功；完整歌单放在 playlist。
    return { removed: result.removed, playlist: result.detail };
  }

  /**
   * 调整顺序。接受 `{songs:[{source,songId}, ...]}`，也兼容客户端传入的
   * `{order:[...]}`；服务端要求集合完全一致，防止旧设备覆盖新设备的添加。
   */
  @Patch(":playlistId/songs/order")
  async reorder(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    try {
      const raw = body?.songs ?? body?.songIds ?? body?.order;
      const keys = this.songKeysOf(raw);
      const playlist = await this.playlists.reorder(user.id, this.idOf(playlistIdParam), keys);
      if (!playlist) throw this.notFound();
      return playlist;
    } catch (error) {
      this.rethrowOrderError(error);
    }
  }

  /** PUT 顺序接口与 PATCH 使用同一严格集合校验。 */
  @Put(":playlistId/songs/order")
  reorderPut(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    return this.reorder(user, playlistIdParam, body);
  }

  /** 完整替换歌曲集合，供云端导入和离线设备首次同步。 */
  @Put(":playlistId/songs")
  async replaceSongs(
    @CurrentUser() user: SessionUser,
    @Param("playlistId") playlistIdParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    try {
      const raw = body?.songs;
      if (!Array.isArray(raw)) throw ApiErrors.badRequest(4000, "songs 必须是数组");
      if (raw.length > MAX_PLAYLIST_SONGS) {
        throw ApiErrors.badRequest(4004, `歌单最多保存 ${MAX_PLAYLIST_SONGS} 首歌曲`);
      }
      const songs = raw.map((item) => this.songInputOf(item));
      const playlist = await this.playlists.replaceSongs(user.id, this.idOf(playlistIdParam), songs);
      if (!playlist) throw this.notFound();
      return playlist;
    } catch (error) {
      this.rethrowOrderError(error);
    }
  }

  private songInputOf(value: unknown): PlaylistSongInput {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      throw ApiErrors.badRequest(4004, "歌曲参数不合法");
    }
    const body = value as Record<string, unknown>;
    const source = this.sourceOf(body.source);
    const mid = this.optionalSongId(body.mid);
    const songIdValue = body.songId ?? body.id ?? body.remoteId ?? mid;
    // songID=0 只是上游占位，真正稳定的身份是 mid；避免所有 mid-only 歌曲共用键 "0"。
    const songId = this.songIdOf(
      (typeof songIdValue === "number" && songIdValue === 0) || songIdValue === "0" ? mid : songIdValue,
    );
    return {
      source,
      songId,
      mid,
      title: this.optionalText(body.title, "歌曲名称", MAX_TEXT_LENGTH) ?? "",
      artist: this.optionalText(body.artist ?? body.singer, "歌手", MAX_TEXT_LENGTH) ?? "",
      album: this.optionalText(body.album, "专辑", MAX_TEXT_LENGTH) ?? "",
      coverUrl: this.optionalSnapshotUrl(body.coverUrl ?? body.coverUri),
      duration: this.optionalText(body.duration, "时长", 32),
      audioUrl: this.optionalSnapshotUrl(body.audioUrl ?? body.audioUri),
      lyricUrl: this.optionalSnapshotUrl(body.lyricUrl ?? body.lyricUri),
      type: this.optionalInteger(body.type, "歌曲类型", 0, 1_000_000),
    };
  }

  private songKeysOf(value: unknown): PlaylistSongKey[] {
    if (!Array.isArray(value)) throw ApiErrors.badRequest(4000, "songs 必须是数组");
    if (value.length > MAX_PLAYLIST_SONGS) {
      throw ApiErrors.badRequest(4004, `歌单最多保存 ${MAX_PLAYLIST_SONGS} 首歌曲`);
    }
    return value.map((item) => {
      if (!item || typeof item !== "object" || Array.isArray(item)) {
        throw ApiErrors.badRequest(4004, "排序歌曲参数不合法");
      }
      const body = item as Record<string, unknown>;
      return this.songKeyOf(body.source, body.songId ?? body.id ?? body.remoteId ?? body.mid, body.mid);
    });
  }

  private songKeyOf(sourceValue: unknown, songIdValue: unknown, midValue?: unknown): PlaylistSongKey {
    const mid = midValue === undefined ? null : this.optionalSongId(midValue);
    const normalizedId = (typeof songIdValue === "number" && songIdValue === 0) || songIdValue === "0" ? mid : songIdValue;
    return { source: this.sourceOf(sourceValue), songId: this.songIdOf(normalizedId) };
  }

  private sourceOf(value: unknown): string {
    const source = typeof value === "string" ? value.trim().toLowerCase() : "";
    if (!SOURCE_PATTERN.test(source) || !SUPPORTED_SOURCES.has(source)) {
      throw ApiErrors.badRequest(4004, "暂不支持的音乐来源");
    }
    return source;
  }

  private songIdOf(value: unknown): string {
    const songId = typeof value === "number" && Number.isSafeInteger(value)
      ? String(value)
      : typeof value === "string" ? value.trim() : "";
    if (!SONG_ID_PATTERN.test(songId)) throw ApiErrors.badRequest(4004, "歌曲 ID 不合法");
    return songId;
  }

  private optionalSongId(value: unknown): string | null {
    if (value === undefined || value === null || value === "") return null;
    return this.songIdOf(value);
  }

  private requiredText(value: unknown, label: string, maxLength: number): string {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text || text.length > maxLength || /[\u0000-\u001F\u007F]/.test(text)) {
      throw ApiErrors.badRequest(4000, `${label}须为 1 至 ${maxLength} 个字符`);
    }
    return text;
  }

  private optionalText(value: unknown, label: string, maxLength: number): string | undefined {
    if (value === undefined || value === null) return undefined;
    if (typeof value !== "string") throw ApiErrors.badRequest(4000, `${label}格式不正确`);
    const text = value.trim();
    if (text.length > maxLength || /[\u0000-\u001F\u007F]/.test(text)) {
      throw ApiErrors.badRequest(4000, `${label}不能超过 ${maxLength} 个字符`);
    }
    return text;
  }

  private optionalUrl(value: unknown): string | null {
    if (value === undefined || value === null || value === "") return null;
    if (typeof value !== "string") throw ApiErrors.badRequest(4000, "地址格式不正确");
    const url = value.trim();
    if (!url || url.length > MAX_URL_LENGTH || /[\u0000-\u001F\u007F]/.test(url)) {
      throw ApiErrors.badRequest(4000, "地址长度或格式不正确");
    }
    // 歌曲快照可保存服务端相对路径；绝对地址只接受 http(s)，避免把本地路径同步到云端。
    if (url.startsWith("/") || /^https?:\/\//i.test(url)) return url;
    throw ApiErrors.badRequest(4000, "地址必须是 HTTP(S) 或服务端相对路径");
  }

  /** 本地下载路径不具备跨设备意义，收到完整 Song 快照时将其静默丢弃。 */
  private optionalSnapshotUrl(value: unknown): string | null {
    if (typeof value === "string" && value.trim().toLowerCase().startsWith("file:")) return null;
    return this.optionalUrl(value);
  }

  private optionalInteger(value: unknown, label: string, min: number, max: number): number | null {
    if (value === undefined || value === null || value === "") return null;
    if (typeof value !== "number" || !Number.isSafeInteger(value) || value < min || value > max) {
      throw ApiErrors.badRequest(4000, `${label}不合法`);
    }
    return value;
  }

  private idOf(value: string): number {
    const id = Number(value);
    if (!Number.isSafeInteger(id) || id <= 0) throw ApiErrors.badRequest(4000, "歌单 ID 不合法");
    return id;
  }

  private notFound() {
    return ApiErrors.notFound(4045, "歌单不存在");
  }

  private rethrowOrderError(error: unknown): never {
    if (error instanceof PlaylistOrderError) throw ApiErrors.badRequest(4004, error.message);
    throw error;
  }
}
