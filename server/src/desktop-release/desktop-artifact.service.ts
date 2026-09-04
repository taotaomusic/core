import { Injectable } from "@nestjs/common";
import { createHash } from "node:crypto";
import { once } from "node:events";
import {
  createReadStream,
  createWriteStream,
  existsSync,
  mkdirSync,
  renameSync,
  rmSync,
  statSync,
} from "node:fs";
import { basename, dirname, relative, resolve, sep } from "node:path";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";

const MAX_ARTIFACT_BYTES = 500 * 1024 * 1024;
const SHA256_PATTERN = /^[0-9a-f]{64}$/;

export type StoredDesktopArtifact = {
  file: string;
  size: number;
  sha256: string;
};

/** Windows 发布文件的内容寻址存储与 Range 下载。 */
@Injectable()
export class DesktopArtifactService {
  constructor(private readonly config: AppConfigService) {}

  async storeUpload(request: Request, expectedSha256: string, kind: "object" | "patch"): Promise<StoredDesktopArtifact> {
    const expected = expectedSha256.trim().toLowerCase();
    if (!SHA256_PATTERN.test(expected)) throw ApiErrors.badRequest(4005, "sha256 不合法");

    const relativeFile = this.relativeFile(kind, expected);
    const target = this.resolveStored(relativeFile);
    const temporary = `${target}.${process.pid}.${Date.now()}.part`;
    mkdirSync(dirname(target), { recursive: true });
    const hash = createHash("sha256");
    let size = 0;
    try {
      const output = createWriteStream(temporary);
      for await (const chunk of request) {
        const buffer = chunk as Buffer;
        size += buffer.length;
        if (size > MAX_ARTIFACT_BYTES) throw new Error("桌面发布文件超过 500MB 上限");
        hash.update(buffer);
        if (!output.write(buffer)) await once(output, "drain");
      }
      output.end();
      await once(output, "finish");
    } catch (error) {
      rmSync(temporary, { force: true });
      throw ApiErrors.badRequest(4005, error instanceof Error ? error.message : "桌面发布文件写入失败");
    }

    if (size <= 0) {
      rmSync(temporary, { force: true });
      throw ApiErrors.badRequest(4005, "桌面发布文件内容为空");
    }
    const actual = hash.digest("hex");
    if (actual !== expected) {
      rmSync(temporary, { force: true });
      throw ApiErrors.badRequest(4006, `桌面发布文件校验不一致，实际为 ${actual}`);
    }
    if (existsSync(target)) rmSync(temporary, { force: true });
    else renameSync(temporary, target);
    return { file: relativeFile, size, sha256: actual };
  }

  /** 差分服务写好临时文件后按内容哈希提交，重复内容只保存一份。 */
  async commitGeneratedAsync(temporary: string, kind: "object" | "patch"): Promise<StoredDesktopArtifact> {
    const hash = createHash("sha256");
    let size = 0;
    for await (const chunk of createReadStream(temporary)) {
      const buffer = chunk as Buffer;
      size += buffer.length;
      hash.update(buffer);
    }
    if (size <= 0) throw new Error("生成的差分文件为空");
    const sha256 = hash.digest("hex");
    const relativeFile = this.relativeFile(kind, sha256);
    const target = this.resolveStored(relativeFile);
    mkdirSync(dirname(target), { recursive: true });
    if (existsSync(target)) rmSync(temporary, { force: true });
    else renameSync(temporary, target);
    return { file: relativeFile, size, sha256 };
  }

  temporaryPatchFile(): string {
    const directory = resolve(this.config.desktopReleaseDirectory, "tmp");
    mkdirSync(directory, { recursive: true });
    return resolve(directory, `patch-${process.pid}-${Date.now()}-${Math.random().toString(16).slice(2)}.part`);
  }

  objectFile(sha256: string): string {
    if (!SHA256_PATTERN.test(sha256)) throw ApiErrors.badRequest(4005, "sha256 不合法");
    return this.relativeFile("object", sha256);
  }

  verifyObject(sha256: string, expectedSize: number): string {
    const file = this.objectFile(sha256);
    const absolute = this.resolveStored(file);
    if (!existsSync(absolute)) throw ApiErrors.notFound(4042, `发布模块 ${sha256} 尚未上传`);
    if (statSync(absolute).size !== expectedSize) {
      throw ApiErrors.badRequest(4006, `发布模块 ${sha256} 的大小与清单不一致`);
    }
    return file;
  }

  absolutePath(file: string): string {
    return this.resolveStored(file);
  }

  download(
    request: Request,
    response: Response,
    fileName: string,
    sha256: string,
    downloadName: string,
  ): void {
    const file = this.resolveStored(fileName);
    if (!existsSync(file)) throw ApiErrors.notFound(4042, "桌面发布文件缺失");
    const total = statSync(file).size;
    const safeName = basename(downloadName).replace(/["\r\n]/g, "_");
    const headers: Record<string, string> = {
      "content-type": "application/octet-stream",
      "accept-ranges": "bytes",
      etag: `"${sha256}"`,
      "cache-control": "public, max-age=31536000, immutable",
      "content-disposition": `attachment; filename="${safeName}"`,
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
      if (request.method === "HEAD") response.end();
      else createReadStream(file, { start, end }).pipe(response);
      return;
    }
    response.writeHead(200, { ...headers, "content-length": String(total) });
    if (request.method === "HEAD") response.end();
    else createReadStream(file).pipe(response);
  }

  private relativeFile(kind: "object" | "patch", sha256: string): string {
    return `${kind === "object" ? "objects" : "patches"}/${sha256.slice(0, 2)}/${sha256}.bin`;
  }

  private resolveStored(file: string): string {
    const root = resolve(this.config.desktopReleaseDirectory);
    const target = resolve(root, file);
    const pathFromRoot = relative(root, target);
    if (!pathFromRoot || pathFromRoot.startsWith("..") || pathFromRoot.includes(`..${sep}`)) {
      throw ApiErrors.badRequest(4005, "桌面发布文件路径不合法");
    }
    return target;
  }
}
