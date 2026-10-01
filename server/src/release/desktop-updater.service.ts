import { Injectable, Logger } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";

/** desktop-latest 的 updater 清单固定地址（tauri-action 生成并上传）。 */
const DESKTOP_LATEST_JSON =
  "https://github.com/taotaomusic/core/releases/download/desktop-latest/latest.json";

/** 只允许改写指向 GitHub 自己域名的安装包直链。 */
const GITHUB_HOSTS = new Set([
  "github.com",
  "objects.githubusercontent.com",
  "release-assets.githubusercontent.com",
]);

/** 缓存有效期：updater 轮询不频繁，5 分钟足够，发版时 webhook 会主动失效。 */
const CACHE_TTL_MS = 5 * 60 * 1000;

type TauriPlatform = { signature: string; url: string };
type TauriManifest = {
  version: string;
  notes?: string;
  pub_date?: string;
  platforms: Record<string, TauriPlatform>;
};

/**
 * Tauri updater 清单代理。
 *
 * 拉取 GitHub 上 tauri-action 生成的 `latest.json`，把每个平台的 `url` 改写成
 * 加了代理前缀的直链（提速），`version`/`signature`/`notes` **原样保留**：
 * minisign 签名是对安装包字节算的，透明代理返回相同字节即校验通过，改 url 不破坏签名。
 */
@Injectable()
export class DesktopUpdaterService {
  private readonly logger = new Logger(DesktopUpdaterService.name);
  private cache: { at: number; manifest: TauriManifest } | null = null;

  constructor(private readonly config: AppConfigService) {}

  invalidateCache(): void {
    this.cache = null;
  }

  async getManifest(): Promise<TauriManifest> {
    const now = Date.now();
    if (this.cache && now - this.cache.at < CACHE_TTL_MS) return this.cache.manifest;

    const response = await fetch(DESKTOP_LATEST_JSON, { redirect: "follow" });
    if (!response.ok) throw ApiErrors.upstream(`拉取 desktop latest.json 失败：${response.status}`);
    const source = (await response.json()) as TauriManifest;
    if (!source || typeof source.version !== "string" || !source.platforms) {
      throw ApiErrors.upstream("desktop latest.json 结构异常");
    }

    const platforms: Record<string, TauriPlatform> = {};
    for (const [key, platform] of Object.entries(source.platforms)) {
      platforms[key] = { signature: platform.signature, url: this.proxied(platform.url) };
    }
    const manifest: TauriManifest = {
      version: source.version,
      notes: source.notes,
      pub_date: source.pub_date,
      platforms,
    };
    this.cache = { at: now, manifest };
    this.logger.log(`刷新 desktop updater 清单：${manifest.version}`);
    return manifest;
  }

  /** 给安装包直链拼下载代理前缀；非 GitHub 域名或空前缀时原样返回。 */
  private proxied(url: string): string {
    const prefix = this.config.downloadProxyPrefix;
    if (!prefix) return url;
    try {
      const host = new URL(url).hostname;
      if (!GITHUB_HOSTS.has(host)) return url;
    } catch {
      return url;
    }
    if (url.startsWith(prefix)) return url;
    return `${prefix.replace(/\/+$/, "")}/${url}`;
  }
}
