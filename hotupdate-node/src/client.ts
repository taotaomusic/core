import type { ApiEnvelope, BootstrapData, NormalizedBootstrap } from "./protocol.js";
import { normalizeBootstrap } from "./protocol.js";

/** bootstrap 请求参数。`sdk` 是 Android SDK_INT；非 Android 客户端传 0 会被服务端拒绝。 */
export type BootstrapParams = {
  channel?: string;
  versionCode: number;
  sdk: number;
  deviceId: string;
  /** 访问令牌；未登录传 null，服务端退回按设备号分桶。 */
  token?: string | null;
};

/** 客户端行为参数（全部可选，默认值见 HotUpdateClient）。 */
export type HotUpdateClientOptions = {
  /** 单次请求超时毫秒，默认 15_000。用 AbortController 实现。 */
  timeoutMs?: number;
  /** 网络级失败（超时/连接错误/5xx）的最大尝试次数，默认 2（即最多重试 1 次）。 */
  attempts?: number;
  /** 重试基础退避毫秒，默认 500；第 n 次重试等待 base * 2^(n-1)。 */
  retryBackoffMs?: number;
};

/**
 * `/app/bootstrap` 客户端 —— 与 Kotlin `AppUpdateApi` 同一套请求语义。
 *
 * 兼容要点（改任何一条都可能让热更新通道静默失效，改前读 RELEASE.md）：
 * - 接口**公开**但可带令牌；**过期令牌也照发**，服务端会降级为按设备号分桶，
 *   绝不能因为 401 就丢弃结果（客户端把异常吞成「已是最新」是设计行为）；
 * - 服务端信封是 `{ code, message, data }`，`code !== 0` 视为业务失败；
 * - 响应头 `x-latest-version-code` / `x-latest-patch-version` 只是提示，
 *   是否真的要更新仍由本次 bootstrap 的权威结果判定。
 *
 * 韧性：单次请求带超时（AbortController）；仅对**网络级失败**（超时、连接错误、
 * 5xx）按指数退避重试 —— 4xx 是确定性失败，重试只会放大问题。
 */
export class HotUpdateClient {
  private readonly timeoutMs: number;
  private readonly attempts: number;
  private readonly retryBackoffMs: number;

  constructor(
    /** 服务端 base URL，例如 `https://music.xydaigua.cn`。 */
    private readonly endpoint: string,
    /** 全 App 统一 User-Agent。 */
    private readonly userAgent: string,
    /** 可注入的 fetch（Node 20+ 原生 fetch 即可；测试可传 stub）。 */
    private readonly fetchImpl: typeof fetch = fetch,
    options: HotUpdateClientOptions = {},
  ) {
    this.timeoutMs = options.timeoutMs ?? 15_000;
    this.attempts = Math.max(1, options.attempts ?? 2);
    this.retryBackoffMs = options.retryBackoffMs ?? 500;
  }

  /** 调一次 bootstrap（含超时与网络级重试）。确定性失败（4xx/业务码）直接抛错。 */
  async bootstrap(params: BootstrapParams): Promise<{ data: BootstrapData; headers: Headers }> {
    const query = new URLSearchParams({
      channel: params.channel ?? "release",
      versionCode: String(params.versionCode),
      sdk: String(params.sdk),
      deviceId: params.deviceId,
    });
    const headers: Record<string, string> = {
      Accept: "application/json",
      "User-Agent": this.userAgent,
    };
    const token = params.token?.trim();
    if (token) headers.Authorization = `Bearer ${token}`;

    let lastError: unknown;
    for (let attempt = 1; attempt <= this.attempts; attempt++) {
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), this.timeoutMs);
      try {
        const response = await this.fetchImpl(`${this.endpoint}/api/v1/app/bootstrap?${query}`, {
          headers,
          signal: controller.signal,
        });
        // 5xx 视为暂时性失败，进入下一轮；4xx 直接抛。
        if (response.status >= 500) {
          lastError = new Error(`检查更新失败：HTTP ${response.status}`);
          if (attempt < this.attempts) {
            await sleep(this.retryBackoffMs * 2 ** (attempt - 1));
            continue;
          }
          throw lastError;
        }
        if (!response.ok) throw new Error(`检查更新失败：HTTP ${response.status}`);
        const envelope = (await response.json()) as ApiEnvelope<BootstrapData>;
        if (envelope.code !== 0) throw new Error(envelope.message || "检查更新失败");
        return { data: envelope.data, headers: response.headers };
      } catch (error) {
        lastError = error;
        // AbortError（超时）与 fetch 连接错误属网络级失败，可重试；其余（4xx、业务码）直接抛。
        const retryable =
          error instanceof Error && (error.name === "AbortError" || !("status" in error));
        if (retryable && attempt < this.attempts) {
          await sleep(this.retryBackoffMs * 2 ** (attempt - 1));
          continue;
        }
        throw error instanceof Error && error.name === "AbortError"
          ? new Error(`检查更新超时（${this.timeoutMs}ms）`)
          : error;
      } finally {
        clearTimeout(timer);
      }
    }
    throw lastError instanceof Error ? lastError : new Error("检查更新失败");
  }

  /** bootstrap + 归一化一步到位；响应头里的提示版本一并带出。 */
  async check(params: BootstrapParams): Promise<{
    result: NormalizedBootstrap;
    latestVersionCode: number | null;
    latestPatchVersion: number | null;
  }> {
    const { data, headers } = await this.bootstrap(params);
    return {
      result: normalizeBootstrap(data),
      latestVersionCode: numberOrNull(headers.get("x-latest-version-code")),
      latestPatchVersion: numberOrNull(headers.get("x-latest-patch-version")),
    };
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function numberOrNull(value: string | null): number | null {
  if (value === null) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}
