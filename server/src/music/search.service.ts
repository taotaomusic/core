import { Injectable } from "@nestjs/common";
import type { Response } from "express";
import { createSemaphore } from "../common/semaphore";
import { AppConfigService } from "../config/app-config.service";
import { TencentClient, type UpstreamSong } from "../upstream/tencent.client";
import { SongMapper, type Song } from "./song.mapper";

/** 首字节探测的超时。用于判断上游给出的播放地址是否真的能拉动。 */
const PROBE_TIMEOUT_MS = 8_000;

@Injectable()
export class SearchService {
  constructor(
    private readonly config: AppConfigService,
    private readonly upstream: TencentClient,
    private readonly mapper: SongMapper,
  ) {}

  /**
   * 搜索并以 NDJSON 流式返回。
   *
   * 响应必须是**裸 NDJSON**，不能被统一信封包住：客户端逐行取 `type` 字段，
   * 包了外壳后会取不到 → 0 首歌 + 无任何错误，表现为「搜不到东西」。
   *
   * 并发解析播放地址：所有任务一次性创建、由信号量限制在飞数，再按原始顺序依次
   * 写出，顺序稳定且总耗时取决于最慢的一批而不是累加（串行时 20 首约 11 秒）。
   */
  async stream(response: Response, keyword: string, page: number, limit: number, quality: number): Promise<void> {
    const result = await this.upstream.searchSongs(keyword, page, limit);
    response.writeHead(200, {
      "content-type": "application/x-ndjson; charset=utf-8",
      "cache-control": "no-cache",
      "transfer-encoding": "chunked",
      "access-control-allow-origin": "*",
    });

    const acquire = createSemaphore(this.config.searchConcurrency);
    // 必须在创建 promise 时就挂上处理器，把失败折叠成 { ok: false }。
    // 若留到下面的循环里再 try/await，先失败的那个任务在轮到它之前就是一个
    // 未处理的 rejection，Node 15 起默认直接终止进程 —— 一首拿不到地址的歌就能弄挂服务。
    const pending = result.list.map((item) =>
      acquire(() => this.resolveSong(item, quality)).then(
        (song) => ({ ok: true as const, song }),
        () => ({ ok: false as const }),
      ),
    );

    let count = 0;
    let dropped = 0;
    for (const task of pending) {
      const outcome = await task;
      if (!outcome.ok) {
        // 拿不到可用播放地址的歌曲不返回，但要在 end 行里报出数量，界面才能提示。
        dropped++;
        continue;
      }
      response.write(`${JSON.stringify({ type: "song", data: outcome.song })}\n`);
      count++;
    }
    const meta = { page, limit, quality, count, dropped, total: result.total, hasMore: result.nextPage !== null };
    response.end(`${JSON.stringify({ type: "end", meta })}\n`);
  }

  /** 补齐一首歌的播放地址；每一档音质都会探测首字节，全部不可用才抛错。 */
  private async resolveSong(item: UpstreamSong, quality: number): Promise<Song> {
    const song = this.mapper.toSong(item);
    const link = await this.upstream.resolveLink(
      { id: Number(item.songID), mid: item.songMID, type: item.type },
      quality,
      (url) => this.probe(url),
    );
    return { ...song, audioUrl: link.url };
  }

  /** 拉首两个字节确认地址真的能用；上游偶尔给出 code 正常但拉不动的死链。 */
  private async probe(url: string): Promise<boolean> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), PROBE_TIMEOUT_MS);
    try {
      const response = await fetch(url, {
        headers: { range: "bytes=0-1", "user-agent": "TaotaoMusic/1.0" },
        redirect: "follow",
        signal: controller.signal,
      });
      await response.body?.cancel();
      return response.status >= 200 && response.status < 300;
    } catch {
      return false;
    } finally {
      clearTimeout(timer);
    }
  }
}
