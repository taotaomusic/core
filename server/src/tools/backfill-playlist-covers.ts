import { config as loadEnv } from "dotenv";
// 与 ConfigModule.forRoot 保持同一约定：默认读部署目录的 .env，ENV_FILE 可指定其它路径。
loadEnv({ path: process.env.ENV_FILE ?? ".env" });

import { ConfigService } from "@nestjs/config";
import { AppConfigService } from "../config/app-config.service";
import { DatabaseService } from "../database/database.service";
import { KuwoClient } from "../upstream/kuwo.client";
import type { SongKey } from "../upstream/music-source.client";
import { isMusicSource } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
import { MusicSourceAccountRepository } from "../upstream/music-source-account.repository";
import { NeteaseClient } from "../upstream/netease.client";
import { TencentClient } from "../upstream/tencent.client";

/**
 * 回填歌单歌曲快照的封面。
 *
 * 背景：`cover_url` 为空的歌单在列表里依赖「取歌单内第一张非空歌曲封面」兜底
 * （见 `playlists.repository.ts` 的 PLAYLIST_COLUMNS），但早期加入的歌曲快照
 * 本身没存封面，兜底无从取图，客户端只能一直显示音符占位。本工具按
 * source + 歌曲身份逐行调用上游 `requestSongInfo` 拿真实封面，
 * 回填 `playlist_songs.cover_url`，让存量歌单立即有图可兜。
 *
 * 用法（在部署目录执行，与 `node dist/main.js` 同目录，读取同一份 .env）：
 *   node dist/tools/backfill-playlist-covers.js               # 实际回填
 *   node dist/tools/backfill-playlist-covers.js --dry-run     # 只探测不写库
 *   node dist/tools/backfill-playlist-covers.js --playlist=1  # 只处理指定歌单
 *
 * 安全性：只更新 cover_url 为空的行，可重复执行（幂等）；不改 revision 与
 * updated_at —— 封面兜底是读取期计算，客户端刷新歌单列表即可看到，不需要
 * 触发设备重新同步歌单内容。
 *
 * 新增音源时这里不用改：分派走 MusicSourceRegistry，与主服务同一张注册表。
 */
type MissingCoverRow = {
  playlist_id: number;
  source: string;
  song_id: string;
  mid: string | null;
  song_type: number | null;
  title: string;
};

/** 相邻两次上游请求的间隔，对免费的公共上游保持克制。 */
const UPSTREAM_THROTTLE_MS = 250;

function parseArgs(argv: string[]): { dryRun: boolean; playlistId?: number } {
  let dryRun = false;
  let playlistId: number | undefined;
  for (const arg of argv) {
    if (arg === "--dry-run") dryRun = true;
    const playlistMatch = /^--playlist=(\d+)$/.exec(arg);
    if (playlistMatch) playlistId = Number(playlistMatch[1]);
  }
  return { dryRun, playlistId };
}

/**
 * 由快照行构造上游歌曲身份。
 *
 * song_id 是稳定字符串键：数字就是上游数字 ID；非数字说明是 mid-only 歌曲
 * （客户端把 mid 存进了 song_id，见 TencentMusicApi.playlistSongId），
 * 此时优先用 mid 列，缺列时退回 song_id 本身。
 */
function songKeyOf(row: MissingCoverRow): SongKey | undefined {
  const key: SongKey = {};
  const numericId = Number(row.song_id);
  if (Number.isInteger(numericId) && numericId > 0) key.id = numericId;
  const mid = row.mid?.trim() || (Number.isInteger(numericId) && numericId > 0 ? "" : row.song_id.trim());
  if (mid) key.mid = mid;
  if (row.song_type !== null && row.song_type !== undefined) key.type = row.song_type;
  if (!key.id && !key.mid) return undefined;
  return key;
}

function describeRow(row: MissingCoverRow): string {
  const title = row.title || "(无标题)";
  return `歌单 ${row.playlist_id} · ${row.source}/${row.song_id}「${title}」`;
}

async function main(): Promise<void> {
  const { dryRun, playlistId } = parseArgs(process.argv.slice(2));
  const appConfig = new AppConfigService(new ConfigService());
  if (!appConfig.databaseUrl) {
    throw new Error("缺少 DATABASE_URL —— 请在部署目录（.env 所在位置）执行本工具");
  }

  const database = new DatabaseService(appConfig);
  // 手工组装依赖：注册表与主服务共用同一套适配器，避免脚本里出现第二份分派逻辑。
  const registry = new MusicSourceRegistry(
    new TencentClient(appConfig),
    new NeteaseClient(),
    new KuwoClient(new MusicSourceAccountRepository(database)),
  );

  const rows = await database.all<MissingCoverRow>(
    `SELECT playlist_id, source, song_id, mid, song_type, title
     FROM playlist_songs
     WHERE (cover_url IS NULL OR cover_url = '')${playlistId !== undefined ? " AND playlist_id = $1" : ""}
     ORDER BY playlist_id, position`,
    playlistId !== undefined ? [playlistId] : [],
  );
  if (rows.length === 0) {
    console.log("没有需要回填的歌曲：所有快照都已有封面。");
    return;
  }
  console.log(`共 ${rows.length} 首歌曲缺封面${dryRun ? "（dry-run，不写库）" : ""}，开始逐首向上游探测…`);

  let updated = 0;
  let upstreamEmpty = 0;
  let failed = 0;
  let skipped = 0;
  const updatedByPlaylist = new Map<number, number>();

  for (const row of rows) {
    if (!isMusicSource(row.source)) {
      skipped += 1;
      console.log(`跳过（未知音源）：${describeRow(row)}`);
      continue;
    }
    const key = songKeyOf(row);
    if (!key) {
      skipped += 1;
      console.log(`跳过（无可用歌曲身份）：${describeRow(row)}`);
      continue;
    }
    try {
      const info = await registry.of(row.source).requestSongInfo(key);
      const cover = info.cover.trim();
      if (!cover) {
        upstreamEmpty += 1;
        console.log(`上游无封面：${describeRow(row)}`);
      } else if (dryRun) {
        console.log(`[dry-run] 将回填：${describeRow(row)} -> ${cover}`);
      } else {
        const changed = await database.run(
          `UPDATE playlist_songs SET cover_url = $1
           WHERE playlist_id = $2 AND source = $3 AND song_id = $4 AND (cover_url IS NULL OR cover_url = '')`,
          [cover, row.playlist_id, row.source, row.song_id],
        );
        if (changed > 0) {
          updated += 1;
          updatedByPlaylist.set(row.playlist_id, (updatedByPlaylist.get(row.playlist_id) ?? 0) + 1);
          console.log(`已回填：${describeRow(row)}`);
        } else {
          console.log(`并发跳过（已被其它进程填好）：${describeRow(row)}`);
        }
      }
    } catch (error) {
      failed += 1;
      const message = error instanceof Error ? error.message : String(error);
      console.log(`失败：${describeRow(row)} —— ${message}`);
    }
    await new Promise((resolveSleep) => setTimeout(resolveSleep, UPSTREAM_THROTTLE_MS));
  }

  console.log("----");
  console.log(
    `完成：回填 ${updated}，上游无封面 ${upstreamEmpty}，失败 ${failed}，跳过 ${skipped}，共 ${rows.length}。`,
  );
  if (updatedByPlaylist.size > 0) {
    for (const [id, count] of updatedByPlaylist) {
      console.log(`  歌单 ${id}：回填 ${count} 首封面`);
    }
    console.log("客户端重新进入歌单列表即可看到封面，无需重启服务。");
  }
  // 只在整轮全部失败时以非零码退出，方便外层脚本感知；部分失败属上游波动，重跑即可。
  if (failed > 0 && updated === 0) process.exitCode = 1;
}

main().catch((error) => {
  console.error(`回填中止：${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});
