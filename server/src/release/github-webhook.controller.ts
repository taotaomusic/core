import { Controller, HttpCode, HttpStatus, Logger, Post, Req } from "@nestjs/common";
import { createHmac, timingSafeEqual } from "node:crypto";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { AdminSettingKeys, AdminSettingRepository } from "../admin-setting/admin-setting.repository";
import { AppConfigService } from "../config/app-config.service";
import { ReleaseRepository } from "./release.repository";
import { DesktopUpdaterService } from "./desktop-updater.service";

/** 只允许从 GitHub 自己的域名拉元数据，收敛 SSRF。 */
const GITHUB_HOSTS = new Set([
  "github.com",
  "objects.githubusercontent.com",
  "raw.githubusercontent.com",
  "release-assets.githubusercontent.com",
]);

/** CI 产出的安卓发布元数据（随 Release 上传的小 JSON 资产）。 */
type AndroidMetadata = {
  versionCode: number;
  versionName: string;
  sha256: string;
  size: number;
  apkAsset: string;
  notes?: string;
};

type GithubAsset = { name: string; size: number; browser_download_url: string };
type GithubRelease = { tag_name?: string; body?: string; assets?: GithubAsset[] };
type GithubWebhookPayload = { action?: string; release?: GithubRelease };

/**
 * GitHub Release webhook 接收器。
 *
 * 发版链路改为「GitHub 发布 Release → 本端点被回调 → 记下版本号/说明/sha256/直链」，
 * 服务器不再接收或下发构件字节，字节由 GitHub Release 承载、下发时拼代理前缀提速。
 *
 * 路由挂在 `main.ts` 的 `RAW_BODY_PATHS` 里：验签要用**原始请求字节**算 HMAC，
 * 不能让 crypto 中间件或 express.json() 碰它（重新序列化后字节变了，签名必对不上）。
 */
@Public()
@RawResponse()
@Controller("app/github-webhook")
export class GithubWebhookController {
  private readonly logger = new Logger(GithubWebhookController.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly releases: ReleaseRepository,
    private readonly audit: AdminAuditService,
    private readonly settings: AdminSettingRepository,
    private readonly desktopUpdater: DesktopUpdaterService,
  ) {}

  @Post()
  @HttpCode(HttpStatus.OK)
  async receive(@Req() request: Request): Promise<{ ok: true; handled: string }> {
    const raw = await this.readRawBody(request);
    this.verifySignature(request, raw, await this.resolveSecret());

    const event = String(request.headers["x-github-event"] ?? "");
    if (event !== "release") return { ok: true, handled: `ignored:event=${event || "none"}` };

    const payload = this.parse(raw);
    const action = payload.action ?? "";
    // published / released：首次发布；edited：资产或说明被改（CI clobber 上传会触发）。
    if (!["published", "released", "edited"].includes(action)) {
      return { ok: true, handled: `ignored:action=${action}` };
    }

    const tag = payload.release?.tag_name ?? "";
    switch (tag) {
      case "latest":
        return { ok: true, handled: await this.handleAndroid(payload.release!, request) };
      case "desktop-latest":
        this.desktopUpdater.invalidateCache();
        return { ok: true, handled: "desktop:cache-invalidated" };
      default:
        return { ok: true, handled: `ignored:tag=${tag || "none"}` };
    }
  }

  /** 安卓：从 Release 资产取 metadata.json 补齐 versionCode/sha256，登记外链。 */
  private async handleAndroid(release: GithubRelease, request: Request): Promise<string> {
    const assets = release.assets ?? [];
    const metaAsset = assets.find((a) => a.name === "metadata.json");
    if (!metaAsset) throw ApiErrors.badRequest(4005, "Release latest 缺少 metadata.json 资产");
    const meta = await this.fetchMetadata(metaAsset.browser_download_url);

    const apkAsset = assets.find((a) => a.name === meta.apkAsset)
      ?? assets.find((a) => /^TaotaoMusic-.*\.apk$/.test(a.name));
    if (!apkAsset) throw ApiErrors.badRequest(4005, "Release latest 缺少 APK 资产");

    const channel = this.config.defaultChannel;
    await this.releases.upsertRelease({
      channel,
      version_code: meta.versionCode,
      version_name: meta.versionName,
      // 字节不落盘：apk_file 留空，下发走 apk_url 外链。
      apk_file: "",
      apk_url: apkAsset.browser_download_url,
      apk_size: meta.size || apkAsset.size,
      apk_sha256: meta.sha256.toLowerCase(),
      release_note: (meta.notes ?? release.body ?? "").trim(),
      // 新版本默认不放量（upsert 的 ON CONFLICT 刻意不覆盖已有 rollout，不会打断在途灰度）。
      rollout_percent: 0,
      min_sdk: 24,
      enabled: 1,
    });

    await this.audit.record(
      request as AdminAuthenticatedRequest,
      "release.webhook_register",
      "release",
      `${channel}#${meta.versionCode}`,
      { channel, versionCode: meta.versionCode, versionName: meta.versionName, apkSha256: meta.sha256 },
    );
    this.logger.log(`webhook 登记安卓版本 ${meta.versionName}(${meta.versionCode})`);
    return `android:registered:${meta.versionCode}`;
  }

  private async fetchMetadata(url: string): Promise<AndroidMetadata> {
    this.assertGithubUrl(url);
    const response = await fetch(url, { redirect: "follow" });
    if (!response.ok) throw ApiErrors.upstream(`拉取 metadata.json 失败：${response.status}`);
    const data = (await response.json()) as Partial<AndroidMetadata>;
    if (!Number.isInteger(data.versionCode) || (data.versionCode ?? 0) <= 0) {
      throw ApiErrors.badRequest(4005, "metadata.json 的 versionCode 不合法");
    }
    if (!data.versionName || !/^\d+(\.\d+)*$/.test(data.versionName)) {
      throw ApiErrors.badRequest(4005, "metadata.json 的 versionName 不合法");
    }
    if (!data.sha256 || !/^[0-9a-fA-F]{64}$/.test(data.sha256)) {
      throw ApiErrors.badRequest(4005, "metadata.json 的 sha256 不合法");
    }
    return {
      versionCode: data.versionCode!,
      versionName: data.versionName,
      sha256: data.sha256,
      size: Number(data.size) || 0,
      apkAsset: String(data.apkAsset ?? ""),
      notes: data.notes,
    };
  }

  private assertGithubUrl(url: string): void {
    let host: string;
    try {
      const parsed = new URL(url);
      if (parsed.protocol !== "https:") throw new Error("not https");
      host = parsed.hostname;
    } catch {
      throw ApiErrors.badRequest(4005, "metadata 直链不合法");
    }
    if (!GITHUB_HOSTS.has(host)) throw ApiErrors.badRequest(4005, `拒绝从非 GitHub 域名拉取：${host}`);
  }

  /** 读原始请求字节（RAW_BODY_PATHS 路由不经 express.json，流在这里被消费）。 */
  private async readRawBody(request: Request): Promise<Buffer> {
    const chunks: Buffer[] = [];
    let size = 0;
    for await (const chunk of request) {
      const buffer = chunk as Buffer;
      size += buffer.length;
      // GitHub release 负载通常几十 KB；留足余量同时挡住异常超大体。
      if (size > 1024 * 1024) throw ApiErrors.badRequest(4005, "webhook 负载过大");
      chunks.push(buffer);
    }
    return Buffer.concat(chunks);
  }

  /**
   * 取验签密钥：优先后台「系统设置」里配置的值，其次回落环境变量 `GITHUB_WEBHOOK_SECRET`
   * （便于空库首次引导）。两者都空视为未启用。
   */
  private async resolveSecret(): Promise<string> {
    const fromDb = await this.settings.getValue(AdminSettingKeys.githubWebhookSecret);
    return fromDb || this.config.githubWebhookSecret;
  }

  /**
   * 校验 `X-Hub-Signature-256`：`sha256=` + HMAC-SHA256(raw, secret)，timingSafeEqual 比对。
   * 未配置 secret 直接拒绝——未启用即视为关闭，绝不放行未验签请求。
   */
  private verifySignature(request: Request, raw: Buffer, secret: string): void {
    if (!secret) throw ApiErrors.serviceUnavailable(5031, "webhook 密钥未配置，webhook 未启用");
    const provided = String(request.headers["x-hub-signature-256"] ?? "");
    const expected = "sha256=" + createHmac("sha256", secret).update(raw).digest("hex");
    const a = Buffer.from(provided);
    const b = Buffer.from(expected);
    if (a.length !== b.length || !timingSafeEqual(a, b)) {
      throw ApiErrors.unauthorized(4015, "webhook 签名校验失败");
    }
  }

  private parse(raw: Buffer): GithubWebhookPayload {
    try {
      return JSON.parse(raw.toString("utf8")) as GithubWebhookPayload;
    } catch {
      throw ApiErrors.badRequest(4005, "webhook 负载不是合法 JSON");
    }
  }
}
