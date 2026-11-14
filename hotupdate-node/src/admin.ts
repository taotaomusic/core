import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";

/**
 * 发布/补丁/远程配置的管理端 SDK —— 对接 `server/src/release/release-admin.controller.ts`。
 *
 * 认证走管理员 Bearer 会话：先 `login()`（密码登录，必要时用 `totp()` 生成动态码过 2FA），
 * 之后的每个请求自动带上 `Authorization: Bearer <token>`。令牌过期（401）时抛
 * `AdminAuthError`，调用方重新登录即可。
 */
export class HotUpdateAdmin {
  private token: string | null = null;

  constructor(private readonly endpoint: string) {}

  /** 密码登录；账号开了 2FA 时传 `totpCode`（用 `totp(secret)` 生成）。 */
  async login(username: string, password: string, totpCode?: string): Promise<void> {
    const body: Record<string, string> = { username, password };
    if (totpCode) body.totp = totpCode;
    const data = await this.request<{ token?: string; temp_token?: string }>(
      "POST",
      "/api/v1/admin/auth/login",
      body,
    );
    // 需要 2FA 时服务端返回 temp_token；把 temp_token 带回第二步换正式会话。
    if (!data.token && data.temp_token && totpCode) {
      const second = await this.request<{ token?: string }>(
        "POST",
        "/api/v1/admin/auth/totp-verify",
        { temp_token: data.temp_token, totp: totpCode },
      );
      if (!second.token) throw new AdminAuthError("2FA 校验未返回会话令牌");
      this.token = second.token;
      return;
    }
    if (!data.token) throw new AdminAuthError("登录未返回会话令牌");
    this.token = data.token;
  }

  /** 用已有会话令牌初始化（脚本场景常把令牌放环境变量里）。 */
  withToken(token: string): this {
    this.token = token;
    return this;
  }

  // ---- 版本发布 ----

  /** 登记一个「外链版本」：不上传 APK 字节，直接登记外部下载地址（GitHub Release 资产等）。 */
  async linkRelease(input: {
    channel?: string;
    versionCode: number;
    versionName: string;
    apkUrl: string;
    apkSize?: number;
    sha256?: string;
    releaseNote?: string;
    rollout?: number;
    minSdk?: number;
    enabled?: boolean;
  }): Promise<void> {
    await this.request("POST", "/api/v1/app/admin/releases/link", input);
  }

  /** 上传 APK 字节登记新版本（请求体是 APK 原始字节，元数据走查询参数）。 */
  async publishRelease(input: {
    apkPath: string;
    versionCode: number;
    versionName: string;
    channel?: string;
    note?: string;
    rollout?: number;
    minSdk?: number;
  }): Promise<void> {
    const apk = await readFile(input.apkPath);
    const sha256 = createHash("sha256").update(apk).digest("hex");
    const query = new URLSearchParams({
      versionCode: String(input.versionCode),
      versionName: input.versionName,
      sha256,
    });
    if (input.channel) query.set("channel", input.channel);
    if (input.note !== undefined) query.set("note", input.note);
    if (input.rollout !== undefined) query.set("rollout", String(input.rollout));
    if (input.minSdk !== undefined) query.set("minSdk", String(input.minSdk));
    await this.raw("POST", `/api/v1/app/admin/releases?${query}`, apk);
  }

  /** 调整某版本的放量（0–100）。 */
  async rollout(channel: string, versionCode: number, percent: number, enabled?: boolean): Promise<void> {
    await this.request("POST", "/api/v1/app/admin/rollout", { channel, versionCode, percent, enabled });
  }

  /** 抬高最低可用版本（强制更新）；0 表示取消。 */
  async minVersion(channel: string, versionCode: number): Promise<void> {
    await this.request("POST", "/api/v1/app/admin/min-version", { channel, versionCode });
  }

  /** 编辑发布记录的更新说明 / 安装包外链。 */
  async editRelease(input: {
    channel?: string;
    versionCode: number;
    releaseNote?: string;
    apkUrl?: string;
  }): Promise<void> {
    await this.request("POST", "/api/v1/app/admin/releases/edit", input);
  }

  // ---- 热修复补丁 ----

  /** 上传补丁 DEX 并登记（请求体是补丁 zip 原始字节）。 */
  async publishPatch(input: {
    patchPath: string;
    targetVersionCode: number;
    patchVersion: number;
    channel?: string;
    note?: string;
  }): Promise<void> {
    const patch = await readFile(input.patchPath);
    const sha256 = createHash("sha256").update(patch).digest("hex");
    const query = new URLSearchParams({
      targetVersionCode: String(input.targetVersionCode),
      patchVersion: String(input.patchVersion),
      sha256,
    });
    if (input.channel) query.set("channel", input.channel);
    if (input.note !== undefined) query.set("note", input.note);
    await this.raw("POST", `/api/v1/app/admin/patches?${query}`, patch);
  }

  /** 调整补丁放量；`enabled: false` 可紧急下架。 */
  async patchRollout(input: {
    channel?: string;
    targetVersionCode: number;
    patchVersion: number;
    percent: number;
    enabled?: boolean;
  }): Promise<void> {
    await this.request("POST", "/api/v1/app/admin/patch-rollout", input);
  }

  // ---- 远程配置 ----

  /** 写一个远程配置键；`value: null` 删除。 */
  async setConfig(input: {
    channel?: string;
    key: string;
    value: unknown;
    minVersionCode?: number;
    maxVersionCode?: number;
  }): Promise<void> {
    await this.request("POST", "/api/v1/app/admin/config", input);
  }

  /** 列出全部发布记录（不带分页参数时返回全量数组，形状与旧契约一致）。 */
  async listReleases(channel?: string): Promise<unknown> {
    const query = channel ? `?channel=${encodeURIComponent(channel)}` : "";
    return this.request("GET", `/api/v1/app/admin/releases${query}`);
  }

  /** 列出全部补丁记录。 */
  async listPatches(channel?: string): Promise<unknown> {
    const query = channel ? `?channel=${encodeURIComponent(channel)}` : "";
    return this.request("GET", `/api/v1/app/admin/patches${query}`);
  }

  /** 列出远程配置项。 */
  async listConfig(): Promise<unknown> {
    return this.request("GET", "/api/v1/app/admin/config");
  }

  // ---- 基础设施 ----

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(`${this.endpoint}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const payload = (await response.json().catch(() => ({}))) as { code?: number; message?: string; data?: T };
    if (response.status === 401) throw new AdminAuthError(payload.message || "管理员会话已过期，请重新登录");
    if (!response.ok || (payload.code !== undefined && payload.code !== 0)) {
      throw new Error(`${method} ${path} 失败：HTTP ${response.status} ${payload.message ?? ""}`.trimEnd());
    }
    return (payload.data ?? (payload as T)) as T;
  }

  private async raw(method: string, path: string, body: Buffer): Promise<void> {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (this.token) headers.Authorization = `Bearer ${this.token}`;
    const response = await fetch(`${this.endpoint}${path}`, { method, headers, body: new Uint8Array(body) });
    if (response.status === 401) throw new AdminAuthError("管理员会话已过期，请重新登录");
    if (!response.ok) {
      const payload = (await response.json().catch(() => ({}))) as { message?: string };
      throw new Error(`${method} ${path} 失败：HTTP ${response.status} ${payload.message ?? ""}`.trimEnd());
    }
  }
}

export class AdminAuthError extends Error {}
