import { Injectable } from "@nestjs/common";
import { randomBytes } from "node:crypto";
import { createReadStream, existsSync } from "node:fs";
import { mkdir, rename, rm, stat } from "node:fs/promises";
import { basename, join } from "node:path";
import { spawn } from "node:child_process";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { ReleaseRepository } from "../release/release.repository";
import { NeteaseClient } from "../upstream/netease.client";
import { TencentClient } from "../upstream/tencent.client";
import { SongShareRepository, type SongShareRecord, type SongShareSnapshot } from "./song-share.repository";

export type CreateSongShareInput = {
  source: "tencent" | "netease";
  remoteId?: number;
  mid?: string;
  type?: number;
};

const PREVIEW_SECONDS = 60;
const PREVIEW_BITRATE = "64k";

@Injectable()
export class SongShareService {
  private readonly generating = new Map<string, Promise<string>>();

  constructor(
    private readonly config: AppConfigService,
    private readonly repository: SongShareRepository,
    private readonly releases: ReleaseRepository,
    private readonly tencent: TencentClient,
    private readonly netease: NeteaseClient,
  ) {}

  async create(userId: number, input: CreateSongShareInput, request: Request) {
    const key = this.keyOf(input);
    const info = await (input.source === "netease" ? this.netease : this.tencent).requestSongInfo(key);
    const remoteId = Number(info.songID) > 0 ? String(info.songID) : input.remoteId ? String(input.remoteId) : null;
    const mid = String(info.songMID || input.mid || "").trim() || null;
    const stableId = remoteId || mid;
    if (!stableId) throw ApiErrors.badRequest(4001, "歌曲缺少可分享的稳定身份");
    const snapshot: SongShareSnapshot = {
      source: input.source,
      song_id: stableId,
      remote_id: remoteId,
      mid,
      song_type: input.type ?? null,
      title: info.title.trim() || "未知歌曲",
      artist: info.singer.trim() || "未知歌手",
      album: info.album.trim(),
      cover_url: info.cover.trim() || null,
      duration_seconds: Math.max(0, Math.trunc(info.interval)),
      vip: info.pay.includes("付费") ? 1 : 0,
    };
    const share = await this.repository.upsert(userId, this.newToken(), snapshot);
    return {
      token: share.token,
      url: `${this.publicBaseOf(request)}/s/${share.token}`,
    };
  }

  async metadata(token: string, request: Request) {
    const share = await this.requiredShare(token);
    void this.repository.noteAccess(token).catch(() => undefined);
    const base = this.publicBaseOf(request);
    const latestVersion = await this.releases.latestFullyRolledOut(this.config.defaultChannel);
    return {
      title: share.title,
      artist: share.artist,
      album: share.album,
      coverUrl: share.cover_url,
      duration: this.durationLabel(share.duration_seconds),
      songId: share.remote_id,
      mid: share.mid,
      type: share.song_type,
      source: share.source,
      vip: share.vip === 1,
      previewDurationSeconds: Math.min(PREVIEW_SECONDS, share.duration_seconds || PREVIEW_SECONDS),
      previewUrl: `${base}/api/v1/public/shares/${share.token}/preview`,
      appDownloadUrl: latestVersion
        ? `${base}/api/v1/app/apk/${latestVersion}`
        : base,
    };
  }

  async streamPreview(token: string, request: Request, response: Response): Promise<void> {
    const share = await this.requiredShare(token);
    const file = await this.ensurePreview(share);
    const info = await stat(file);
    const range = request.headers.range;
    response.setHeader("Content-Type", "audio/mpeg");
    response.setHeader("Accept-Ranges", "bytes");
    response.setHeader("Cache-Control", "public, max-age=86400, immutable");
    if (!range) {
      response.writeHead(200, { "Content-Length": info.size });
      createReadStream(file).pipe(response);
      return;
    }
    const match = /^bytes=(\d*)-(\d*)$/.exec(range);
    if (!match) {
      response.writeHead(416, { "Content-Range": `bytes */${info.size}` });
      response.end();
      return;
    }
    const suffixLength = !match[1] && match[2] ? Number(match[2]) : undefined;
    const start = suffixLength === undefined
      ? Number(match[1] || 0)
      : Math.max(0, info.size - suffixLength);
    const end = suffixLength === undefined && match[2]
      ? Math.min(Number(match[2]), info.size - 1)
      : info.size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || start > end || start >= info.size) {
      response.writeHead(416, { "Content-Range": `bytes */${info.size}` });
      response.end();
      return;
    }
    response.writeHead(206, {
      "Content-Length": end - start + 1,
      "Content-Range": `bytes ${start}-${end}/${info.size}`,
    });
    createReadStream(file, { start, end }).pipe(response);
  }

  private async requiredShare(token: string): Promise<SongShareRecord> {
    if (!/^[A-Za-z0-9_-]{8,24}$/.test(token)) throw ApiErrors.notFound(4045, "分享链接不存在或已失效");
    const share = await this.repository.find(token);
    if (!share) throw ApiErrors.notFound(4045, "分享链接不存在或已失效");
    return share;
  }

  private ensurePreview(share: SongShareRecord): Promise<string> {
    const existing = this.generating.get(share.token);
    if (existing) return existing;
    const task = this.generatePreview(share).finally(() => this.generating.delete(share.token));
    this.generating.set(share.token, task);
    return task;
  }

  private async generatePreview(share: SongShareRecord): Promise<string> {
    await mkdir(this.config.sharePreviewDirectory, { recursive: true });
    const fileName = `${share.token}.mp3`;
    const destination = join(this.config.sharePreviewDirectory, fileName);
    if (share.preview_file && basename(share.preview_file) === fileName && existsSync(destination)) return destination;
    const temp = `${destination}.${process.pid}.${Date.now()}.tmp`;
    const remoteId = Number(share.remote_id || 0) || undefined;
    const target = share.source === "netease"
      ? (await this.netease.resolveLink({ id: remoteId }, 0)).url
      : (await this.tencent.resolveLink({ id: remoteId, mid: share.mid || undefined, type: share.song_type || undefined }, 0)).url;
    const targetHost = new URL(target).hostname;
    if (!this.config.isAllowedMediaHost(targetHost)) throw ApiErrors.upstream("试听地址不可用");
    try {
      await this.runFfmpeg(target, temp);
      await rm(destination, { force: true });
      await rename(temp, destination);
      await this.repository.setPreviewFile(share.token, fileName);
      return destination;
    } catch (error) {
      await rm(temp, { force: true }).catch(() => undefined);
      if (error instanceof Error && error.message.includes("ffmpeg")) {
        throw ApiErrors.serviceUnavailable(5034, "试听服务暂时不可用");
      }
      throw error;
    }
  }

  private runFfmpeg(input: string, output: string): Promise<void> {
    return new Promise((resolve, reject) => {
      const child = spawn(
        this.config.ffmpegExecutable,
        [
          "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
          "-i", input,
          "-t", String(PREVIEW_SECONDS),
          "-vn", "-map_metadata", "-1",
          "-ac", "2", "-ar", "44100", "-b:a", PREVIEW_BITRATE,
          "-f", "mp3", output,
        ],
        { windowsHide: true },
      );
      let stderr = "";
      child.stderr.setEncoding("utf8");
      child.stderr.on("data", (chunk: string) => {
        if (stderr.length < 4_000) stderr += chunk;
      });
      child.once("error", (error) => reject(new Error(`ffmpeg 启动失败：${error.message}`)));
      child.once("exit", (code) => {
        if (code === 0) resolve();
        else reject(new Error(`ffmpeg 裁剪失败（${code ?? "unknown"}）：${stderr.trim()}`));
      });
    });
  }

  private keyOf(input: CreateSongShareInput): { id?: number; mid?: string } {
    const id = Number.isSafeInteger(input.remoteId) && Number(input.remoteId) > 0
      ? input.remoteId
      : undefined;
    if (input.source === "netease") {
      if (!Number.isSafeInteger(id) || Number(id) <= 0) throw ApiErrors.badRequest(4001, "网易云歌曲必须提供正整数 ID");
      return { id };
    }
    const mid = input.mid?.trim();
    if ((!Number.isSafeInteger(id) || Number(id) <= 0) && !mid) throw ApiErrors.badRequest(4001, "请提供歌曲 ID 或 mid");
    return { ...(Number.isSafeInteger(id) && Number(id) > 0 ? { id } : {}), ...(mid ? { mid } : {}) };
  }

  private newToken(): string {
    return randomBytes(9).toString("base64url");
  }

  private publicBaseOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }

  private durationLabel(seconds: number): string {
    const safe = Math.max(0, Math.trunc(seconds));
    return `${Math.floor(safe / 60).toString().padStart(2, "0")}:${(safe % 60).toString().padStart(2, "0")}`;
  }
}
