import { Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { once } from "node:events";
import { createReadStream, createWriteStream, existsSync, mkdirSync, renameSync, rmSync, statSync } from "node:fs";
import type { Request, Response } from "express";
import { basename, resolve } from "node:path";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import type { ReleaseRecord } from "./release.repository";

/** 安装包大小上限，防止管理令牌泄漏后被用来塞满磁盘。 */
const MAX_APK_BYTES = 300 * 1024 * 1024;

export type StoredApk = { apkFile: string; size: number; sha256: string };

@Injectable()
export class ApkService {
  constructor(private readonly config: AppConfigService) {}

  /**
   * 接收上传的安装包。
   *
   * 请求体是 APK 原始字节而不是 base64 或 multipart：base64 有 33% 膨胀，
   * 而 multipart 会把 14MB 缓进内存。这里边读边写盘并同步计算 sha256，
   * 先写 `.part` 再改名，避免中断留下半个可下发的包。
   */
  async store(request: Request, channel: string, versionCode: number, expectedSha256: string): Promise<StoredApk> {
    return this.storeAs(request, `${channel}-${versionCode}.apk`, expectedSha256);
  }

  /**
   * 接收上传的补丁包。文件名带上宿主版本与补丁版本，一个宿主版本可以有多个补丁。
   */
  async storePatch(
    request: Request,
    channel: string,
    targetVersionCode: number,
    patchVersion: number,
    expectedSha256: string,
  ): Promise<StoredApk> {
    return this.storeAs(request, `${channel}-${targetVersionCode}-patch-${patchVersion}.apk`, expectedSha256);
  }

  private async storeAs(request: Request, apkFile: string, expectedSha256: string): Promise<StoredApk> {
    mkdirSync(this.config.apkDirectory, { recursive: true });
    const target = resolve(this.config.apkDirectory, apkFile);
    const temporary = `${target}.part`;
    const hash = createHash("sha256");
    let size = 0;
    let magicChecked = false;

    try {
      const output = createWriteStream(temporary);
      for await (const chunk of request) {
        const buffer = chunk as Buffer;
        // APK 是 ZIP 容器，尽早用魔数拦住上传错文件的情况。
        if (!magicChecked && buffer.length >= 2) {
          if (buffer[0] !== 0x50 || buffer[1] !== 0x4b) throw new Error("上传内容不是 APK");
          magicChecked = true;
        }
        size += buffer.length;
        if (size > MAX_APK_BYTES) throw new Error("安装包超过大小上限");
        hash.update(buffer);
        if (!output.write(buffer)) await once(output, "drain");
      }
      output.end();
      await once(output, "finish");
    } catch (error) {
      rmSync(temporary, { force: true });
      throw ApiErrors.badRequest(4005, error instanceof Error ? error.message : "安装包写入失败");
    }

    if (size === 0) {
      rmSync(temporary, { force: true });
      throw ApiErrors.badRequest(4005, "安装包内容为空");
    }
    const sha256 = hash.digest("hex");
    if (expectedSha256 && expectedSha256.toLowerCase() !== sha256) {
      rmSync(temporary, { force: true });
      throw ApiErrors.badRequest(4006, `安装包校验不一致，实际为 ${sha256}`);
    }
    renameSync(temporary, target);
    return { apkFile, size, sha256 };
  }

  /**
   * 下载安装包，支持 Range 断点续传：安装包约 14 MB，弱网下必须能续传。
   *
   * 客户端只看状态码，不读 `Content-Range` —— 但 206 的实体必须真的从 Range
   * 起始字节开始，否则包会坏掉且要等 sha256 校验才发现。
   */
  download(request: Request, response: Response, release: ReleaseRecord): void {
    this.downloadFile(
      request,
      response,
      release.apk_file,
      release.apk_sha256,
      `taotao-${release.version_name}.apk`,
    );
  }

  /**
   * 按文件名下发一个 APK_DIR 下的文件，支持 Range。
   *
   * 安装包与热修复补丁共用这一份：两者的下发要求完全一样（可续传、可缓存、
   * 文件名必须来自数据库并再取一次 basename 防路径穿越）。
   */
  downloadFile(
    request: Request,
    response: Response,
    fileName: string,
    sha256: string,
    downloadName: string,
  ): void {
    // 文件名来自数据库并再次取 basename，避免任何路径穿越。
    const file = resolve(this.config.apkDirectory, basename(fileName));
    if (!existsSync(file)) throw ApiErrors.notFound(4042, "文件缺失");

    const total = statSync(file).size;
    const headers: Record<string, string> = {
      "content-type": "application/vnd.android.package-archive",
      "accept-ranges": "bytes",
      etag: `"${sha256}"`,
      "cache-control": "public, max-age=86400",
      "content-disposition": `attachment; filename="${downloadName}"`,
    };

    const range = /^bytes=(\d*)-(\d*)$/.exec(request.headers.range ?? "");
    if (range) {
      const start = range[1] ? Number(range[1]) : 0;
      const end = range[2] ? Math.min(Number(range[2]), total - 1) : total - 1;
      if (start >= total || start > end) {
        response.writeHead(416, { "content-range": `bytes */${total}` });
        response.end();
        return;
      }
      response.writeHead(206, {
        ...headers,
        "content-length": String(end - start + 1),
        "content-range": `bytes ${start}-${end}/${total}`,
      });
      if (request.method === "HEAD") {
        response.end();
        return;
      }
      createReadStream(file, { start, end }).pipe(response);
      return;
    }

    response.writeHead(200, { ...headers, "content-length": String(total) });
    if (request.method === "HEAD") {
      response.end();
      return;
    }
    createReadStream(file).pipe(response);
  }
}
