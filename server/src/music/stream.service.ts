import { Injectable, Logger } from "@nestjs/common";
import { once } from "node:events";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import type { MusicSource, SongKey } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";

@Injectable()
export class StreamService {
  private readonly logger = new Logger(StreamService.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly registry: MusicSourceRegistry,
  ) {}

  /**
   * 转发音频流。
   *
   * 相比迁移前新增了 Range 透传：原实现忽略客户端的 Range，每次都从首字节转发，
   * 导致拖动进度条只能从头重下。现在把 Range 交给上游并回写 206 与 Content-Range。
   *
   * 无 Range 时仍返回 200 全量 —— 老客户端的 seek 依赖这个全量回放能力，不能去掉。
   *
   * 这条路径**绝不能返回 401**：ExoPlayer 侧没有重放逻辑，而离线下载侧的 401
   * 会白白消耗一次令牌续期。上游故障统一走 5021 / 502。
   */
  async proxy(request: Request, response: Response, target: string): Promise<void> {
    const parsed = new URL(target);
    if (!this.config.isAllowedMediaHost(parsed.hostname)) {
      throw ApiErrors.badRequest(4002, "不允许转发此地址");
    }

    const range = request.headers.range;
    const upstream = await fetch(parsed, {
      headers: {
        "user-agent": "TaotaoMusic/1.0",
        ...(range ? { range } : {}),
      },
      redirect: "follow",
    });
    if (!upstream.ok || !upstream.body) {
      this.logger.warn(`媒体资源获取失败：HTTP ${upstream.status}`);
      // 一律归成 502，**不能把上游的状态码原样透出**：上游的 401/403 会被客户端
      // 当成自己的令牌失效，触发续期重放，二次失败后把用户踢回登录页。
      response.status(502).json({ code: 5021, message: "媒体资源获取失败" });
      return;
    }

    // 上游支持 Range 时会回 206，这里连同 content-range 一起透传；
    // 上游忽略了 Range 就照原样回 200，客户端会退回软跳转。
    const headers: Record<string, string> = {
      "content-type": upstream.headers.get("content-type") ?? "application/octet-stream",
      "access-control-allow-origin": "*",
      "accept-ranges": upstream.headers.get("accept-ranges") ?? "bytes",
    };
    const contentLength = upstream.headers.get("content-length");
    if (contentLength) headers["content-length"] = contentLength;
    const contentRange = upstream.headers.get("content-range");
    if (contentRange) headers["content-range"] = contentRange;

    response.writeHead(upstream.status, headers);
    for await (const chunk of upstream.body as unknown as AsyncIterable<Uint8Array>) {
      // 必须处理背压：忽略 write 的返回值会让慢速手机 + 快速 CDN 把整首歌缓进内存。
      if (!response.write(chunk)) await once(response, "drain");
    }
    response.end();
  }

  /** 只取播放地址，供转发使用。跳过可用性探测，让实际转发去暴露问题。 */
  async resolvePlayUrl(
    key: SongKey,
    quality: number,
    source: MusicSource = "tencent",
  ): Promise<string> {
    const link = await this.registry.of(source).resolveLink(key, quality);
    return link.url;
  }
}
