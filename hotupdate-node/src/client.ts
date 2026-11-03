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

/**
 * `/app/bootstrap` 客户端 —— 与 Kotlin `AppUpdateApi` 同一套请求语义。
 *
 * 兼容要点（改任何一条都可能让热更新通道静默失效，改前读 RELEASE.md）：
 * - 接口**公开**但可带令牌；**过期令牌也照发**，服务端会降级为按设备号分桶，
 *   绝不能因为 401 就丢弃结果（客户端把异常吞成「已是最新」是设计行为）；
 * - 服务端信封是 `{ code, message, data }`，`code !== 0` 视为业务失败；
 * - 响应头 `x-latest-version-code` / `x-latest-patch-version` 只是提示，
 *   是否真的要更新仍由本次 bootstrap 的权威结果判定。
 */
export class HotUpdateClient {
  constructor(
    /** 服务端 base URL，例如 `https://music.xydaigua.cn`。 */
    private readonly endpoint: string,
    /** 全 App 统一 User-Agent。 */
    private readonly userAgent: string,
    /** 可注入的 fetch（Node 20+ 原生 fetch 即可；测试可传 stub）。 */
    private readonly fetchImpl: typeof fetch = fetch,
  ) {}

  /** 调一次 bootstrap。网络或业务失败都会抛错，调用方自行兜底成「保持现状」。 */
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
    const response = await this.fetchImpl(`${this.endpoint}/api/v1/app/bootstrap?${query}`, { headers });
    if (!response.ok) throw new Error(`检查更新失败：HTTP ${response.status}`);
    const envelope = (await response.json()) as ApiEnvelope<BootstrapData>;
    if (envelope.code !== 0) throw new Error(envelope.message || "检查更新失败");
    return { data: envelope.data, headers: response.headers };
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

function numberOrNull(value: string | null): number | null {
  if (value === null) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}
