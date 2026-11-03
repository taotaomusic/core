import { createHash } from "node:crypto";
import { createWriteStream } from "node:fs";
import { mkdir, rename, unlink } from "node:fs/promises";
import { dirname } from "node:path";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";

/** 下载选项。 */
export type DownloadOptions = {
  /** 期望的 sha256（64 位十六进制，大小写不限）；给了一定校验，不给跳过校验。 */
  sha256?: string;
  /** 单次请求超时毫秒，默认 60_000（安装包大，比 bootstrap 宽）。 */
  timeoutMs?: number;
  /** 网络级失败最大尝试次数，默认 3。断点续传从中断处继续。 */
  attempts?: number;
  /** 进度回调（0-100，按已下载字节 / 总字节计算；总长未知时不回调）。 */
  onProgress?: (percent: number) => void;
};

/** 下载结果：落盘路径与实际校验值。 */
export type DownloadResult = {
  /** 最终文件路径（.part 临时文件已 rename 成它）。 */
  path: string;
  /** 实际下载字节数。 */
  bytes: number;
  /** 文件内容的 sha256（小写十六进制）。 */
  sha256: string;
};

/**
 * 带断点续传与 sha256 校验的下载器 —— 与 Kotlin `UpdateDownloader` 同一套落盘惯例：
 * 先写 `<target>.part` 临时文件，校验通过后原子 rename 成目标名。
 *
 * 服务端（NestJS ApkService）支持 Range 续传；本实现若发现 .part 已存在，
 * 从它的长度发起 `Range: bytes=<len>-` 请求，服务端返回 206 则续写，
 * 返回 200（不支持 Range 或文件被服务端清理）则从头覆盖。
 *
 * 与 Kotlin 侧相同的**校验纪律**：sha256 不匹配就删临时文件并抛错，
 * 绝不把半截/被篡改的安装包留给安装器。
 */
export async function downloadToFile(
  url: string,
  targetPath: string,
  options: DownloadOptions = {},
): Promise<DownloadResult> {
  const timeoutMs = options.timeoutMs ?? 60_000;
  const attempts = Math.max(1, options.attempts ?? 3);
  await mkdir(dirname(targetPath), { recursive: true });

  const partPath = `${targetPath}.part`;
  let lastError: unknown;
  for (let attempt = 1; attempt <= attempts; attempt++) {
    try {
      return await attemptOnce(url, targetPath, partPath, timeoutMs, options);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(500 * 2 ** (attempt - 1));
    }
  }
  throw lastError instanceof Error ? lastError : new Error("下载失败");
}

async function attemptOnce(
  url: string,
  targetPath: string,
  partPath: string,
  timeoutMs: number,
  options: DownloadOptions,
): Promise<DownloadResult> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    // 断点续传：.part 已有字节就从它的长度开始要。
    const existing = await statSize(partPath);
    const headers: Record<string, string> = { "User-Agent": "taotao-hotupdate-node" };
    if (existing > 0) headers.Range = `bytes=${existing}-`;

    const response = await fetch(url, { headers, signal: controller.signal });
    if (!response.ok && response.status !== 206) {
      throw new Error(`下载失败：HTTP ${response.status}`);
    }

    // 206 = 续传命中；200 = 服务端不支持 Range / .part 已失效，从头写。
    const resumed = response.status === 206 && existing > 0;
    const totalHeader = response.headers.get("content-length");
    const total = totalHeader === null ? null : Number(totalHeader) + (resumed ? existing : 0);

    const hash = createHash("sha256");
    let bytes = resumed ? existing : 0;
    let lastPercent = -1;
    const source = Readable.fromWeb(response.body as import("node:stream/web").ReadableStream);
    const target = createWriteStream(partPath, { flags: resumed ? "a" : "w" });
    source.on("data", (chunk: Buffer) => {
      hash.update(chunk);
      bytes += chunk.length;
      if (options.onProgress && total && total > 0) {
        const percent = Math.min(100, Math.floor((bytes / total) * 100));
        if (percent !== lastPercent) {
          lastPercent = percent;
          options.onProgress(percent);
        }
      }
    });
    await pipeline(source, target);

    const sha256 = hash.digest("hex").toLowerCase();
    if (options.sha256 && sha256 !== options.sha256.toLowerCase()) {
      await unlink(partPath).catch(() => undefined);
      throw new Error(`sha256 校验失败：期望 ${options.sha256}，实际 ${sha256}`);
    }
    // 原子 rename：半截文件永远停在 .part，安装器只会看到完整文件。
    await rename(partPath, targetPath);
    return { path: targetPath, bytes, sha256 };
  } catch (error) {
    throw error instanceof Error && error.name === "AbortError"
      ? new Error(`下载超时（${timeoutMs}ms）`)
      : error;
  } finally {
    clearTimeout(timer);
  }
}

async function statSize(path: string): Promise<number> {
  try {
    const { stat } = await import("node:fs/promises");
    return (await stat(path)).size;
  } catch {
    return 0;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
