import { Injectable } from "@nestjs/common";
import type { PoolClient } from "pg";
import { DatabaseService } from "../database/database.service";

/** 单个歌单允许保存的歌曲数量，避免恶意请求制造超大的事务和响应。 */
export const MAX_PLAYLIST_SONGS = 5_000;

export type PlaylistSongKey = {
  source: string;
  songId: string;
};

/** 客户端写入歌单时的歌曲快照。身份字段必填，其余字段缺失时使用空值。 */
export type PlaylistSongInput = PlaylistSongKey & {
  mid?: string | null;
  title?: string;
  artist?: string;
  album?: string;
  coverUrl?: string | null;
  duration?: string | null;
  audioUrl?: string | null;
  lyricUrl?: string | null;
  type?: number | null;
};

export type PlaylistSongRecord = PlaylistSongKey & {
  mid: string | null;
  title: string;
  artist: string;
  album: string;
  coverUrl: string | null;
  duration: string | null;
  audioUrl: string | null;
  lyricUrl: string | null;
  type: number | null;
  position: number;
  addedAt: number;
  updatedAt: number;
};

export type PlaylistRecord = {
  id: number;
  name: string;
  description: string;
  coverUrl: string | null;
  songCount: number;
  revision: number;
  createdAt: number;
  updatedAt: number;
};

export type PlaylistDetail = PlaylistRecord & { songs: PlaylistSongRecord[] };

/** 歌曲集合与歌单当前内容不一致时返回 400，而不是静默丢歌。 */
export class PlaylistOrderError extends Error {}

type PlaylistBase = Omit<PlaylistRecord, "songCount">;
type StoredPlaylistSong = PlaylistSongRecord;

// cover_url 为空时兜底取歌单内按曲目顺序第一张歌曲封面，客户端列表无需再逐个拉详情。
const PLAYLIST_COLUMNS = `p.id, p.name, p.description,
  COALESCE(p.cover_url, (
    SELECT ps.cover_url FROM playlist_songs ps
    WHERE ps.playlist_id = p.id AND ps.cover_url IS NOT NULL
    ORDER BY ps.position ASC LIMIT 1
  )) AS "coverUrl", p.revision,
  p.created_at AS "createdAt", p.updated_at AS "updatedAt"`;

const SONG_COLUMNS = `source, song_id AS "songId", mid, title, artist, album,
  cover_url AS "coverUrl", duration, audio_url AS "audioUrl",
  lyric_url AS "lyricUrl", song_type AS "type", position,
  added_at AS "addedAt", updated_at AS "updatedAt"`;

@Injectable()
export class PlaylistsRepository {
  constructor(private readonly database: DatabaseService) {}

  /** 只返回当前账号的歌单，歌单 ID 永远按 user_id 再过滤，不能靠客户端隐藏。 */
  list(userId: number): Promise<PlaylistRecord[]> {
    return this.database.all<PlaylistRecord>(
      `SELECT ${PLAYLIST_COLUMNS}, count(s.song_id)::integer AS "songCount"
       FROM playlists p
       LEFT JOIN playlist_songs s ON s.playlist_id = p.id
       WHERE p.user_id = $1
       GROUP BY p.id
       ORDER BY p.updated_at DESC, p.id DESC`,
      [userId],
    );
  }

  /** 读取歌单详情及有序歌曲；不存在或不属于当前账号时统一返回 undefined。 */
  async find(userId: number, playlistId: number): Promise<PlaylistDetail | undefined> {
    const playlist = await this.database.first<PlaylistRecord>(
      `SELECT ${PLAYLIST_COLUMNS}, count(s.song_id)::integer AS "songCount"
       FROM playlists p
       LEFT JOIN playlist_songs s ON s.playlist_id = p.id
       WHERE p.user_id = $1 AND p.id = $2
       GROUP BY p.id`,
      [userId, playlistId],
    );
    if (!playlist) return undefined;
    const songs = await this.database.all<PlaylistSongRecord>(
      `SELECT ${SONG_COLUMNS}
       FROM playlist_songs
       WHERE playlist_id = $1
       ORDER BY position ASC`,
      [playlistId],
    );
    return { ...playlist, songs };
  }

  async create(
    userId: number,
    name: string,
    description: string,
    coverUrl: string | null,
  ): Promise<PlaylistRecord> {
    const now = Date.now();
    const playlist = (await this.database.first<PlaylistBase>(
      `INSERT INTO playlists (user_id, name, description, cover_url, revision, created_at, updated_at)
       VALUES ($1, $2, $3, $4, 1, $5, $5)
       RETURNING id, name, description, cover_url AS "coverUrl", revision,
                 created_at AS "createdAt", updated_at AS "updatedAt"`,
      [userId, name, description, coverUrl, now],
    ))!;
    return { ...playlist, songCount: 0 };
  }

  /** 更新歌单资料。undefined 表示不改，null 仅用于清除封面。 */
  async update(
    userId: number,
    playlistId: number,
    name: string | undefined,
    description: string | undefined,
    coverUrl: string | null | undefined,
  ): Promise<PlaylistRecord | undefined> {
    return this.database.transaction(async (client) => {
      if (!(await this.lockPlaylist(client, userId, playlistId))) return undefined;
      const now = Date.now();
      const playlist = (
        await client.query<PlaylistRecord>(
          `UPDATE playlists
           SET name = CASE WHEN $3::boolean THEN $4 ELSE name END,
               description = CASE WHEN $5::boolean THEN $6 ELSE description END,
               cover_url = CASE WHEN $7::boolean THEN $8 ELSE cover_url END,
               revision = revision + 1,
               updated_at = $9
           WHERE user_id = $1 AND id = $2
           RETURNING id, name, description, cover_url AS "coverUrl",
                     0::integer AS "songCount", revision,
                     created_at AS "createdAt", updated_at AS "updatedAt"`,
          [
            userId,
            playlistId,
            name !== undefined,
            name ?? null,
            description !== undefined,
            description ?? null,
            coverUrl !== undefined,
            coverUrl ?? null,
            now,
          ],
        )
      ).rows[0];
      if (!playlist) return undefined;
      const count = (
        await client.query<{ count: number }>(
          `SELECT count(*)::integer AS count FROM playlist_songs WHERE playlist_id = $1`,
          [playlistId],
        )
      ).rows[0]?.count ?? 0;
      return { ...playlist, songCount: count };
    });
  }

  async remove(userId: number, playlistId: number): Promise<boolean> {
    return (await this.database.run("DELETE FROM playlists WHERE user_id = $1 AND id = $2", [userId, playlistId])) === 1;
  }

  /** 添加歌曲到末尾；同一首歌重复添加时更新快照但不生成重复项。 */
  async addSong(userId: number, playlistId: number, input: PlaylistSongInput): Promise<PlaylistDetail | undefined> {
    return this.database.transaction(async (client) => {
      const playlist = await this.lockPlaylist(client, userId, playlistId);
      if (!playlist) return undefined;
      const existing = (
        await client.query<StoredPlaylistSong>(
          `SELECT ${SONG_COLUMNS}
           FROM playlist_songs
           WHERE playlist_id = $1 AND source = $2 AND song_id = $3
           FOR UPDATE`,
          [playlistId, input.source, input.songId],
        )
      ).rows[0];
      const now = Date.now();
      let changed = false;
      if (existing) {
        changed = this.songSnapshotChanged(existing, input);
        if (changed) {
          await client.query(
            `UPDATE playlist_songs
             SET mid = $4, title = $5, artist = $6, album = $7, cover_url = $8,
                 duration = $9, audio_url = $10, lyric_url = $11, song_type = $12,
                 updated_at = $13
             WHERE playlist_id = $1 AND source = $2 AND song_id = $3`,
            [
              playlistId,
              input.source,
              input.songId,
              input.mid ?? null,
              input.title ?? "",
              input.artist ?? "",
              input.album ?? "",
              input.coverUrl ?? null,
              input.duration ?? null,
              input.audioUrl ?? null,
              input.lyricUrl ?? null,
              input.type ?? null,
              now,
            ],
          );
        }
      } else {
        const count = (
          await client.query<{ count: number }>(
            "SELECT count(*)::integer AS count FROM playlist_songs WHERE playlist_id = $1",
            [playlistId],
          )
        ).rows[0]?.count ?? 0;
        if (count >= MAX_PLAYLIST_SONGS) throw new PlaylistOrderError(`歌单最多保存 ${MAX_PLAYLIST_SONGS} 首歌曲`);
        await client.query(
          `INSERT INTO playlist_songs
             (playlist_id, source, song_id, mid, title, artist, album, cover_url,
              duration, audio_url, lyric_url, song_type, position, added_at, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $14)`,
          [
            playlistId,
            input.source,
            input.songId,
            input.mid ?? null,
            input.title ?? "",
            input.artist ?? "",
            input.album ?? "",
            input.coverUrl ?? null,
            input.duration ?? null,
            input.audioUrl ?? null,
            input.lyricUrl ?? null,
            input.type ?? null,
            count,
            now,
          ],
        );
        changed = true;
      }
      if (changed) await this.bumpPlaylist(client, playlistId, now);
      return this.detailWithClient(client, playlistId, playlist);
    });
  }

  /** 删除歌曲并压紧后续位置；删除不存在的歌曲是幂等操作。 */
  async removeSong(
    userId: number,
    playlistId: number,
    key: PlaylistSongKey,
  ): Promise<{ detail: PlaylistDetail; removed: boolean } | undefined> {
    return this.database.transaction(async (client) => {
      const playlist = await this.lockPlaylist(client, userId, playlistId);
      if (!playlist) return undefined;
      const removed = (
        await client.query(
          `DELETE FROM playlist_songs
           WHERE playlist_id = $1 AND source = $2 AND song_id = $3`,
          [playlistId, key.source, key.songId],
        )
      ).rowCount === 1;
      if (removed) {
        await this.normalizePositions(client, playlistId);
        await this.bumpPlaylist(client, playlistId, Date.now());
      }
      return { detail: await this.detailWithClient(client, playlistId, playlist), removed };
    });
  }

  /**
   * 以完整有序键列表替换顺序。列表必须与服务端现有集合完全一致，
   * 否则拒绝请求，避免某台旧设备同步时意外删掉新设备刚加入的歌曲。
   */
  async reorder(
    userId: number,
    playlistId: number,
    keys: PlaylistSongKey[],
  ): Promise<PlaylistDetail | undefined> {
    return this.database.transaction(async (client) => {
      const playlist = await this.lockPlaylist(client, userId, playlistId);
      if (!playlist) return undefined;
      if (keys.length > MAX_PLAYLIST_SONGS) throw new PlaylistOrderError(`歌单最多保存 ${MAX_PLAYLIST_SONGS} 首歌曲`);
      const existing = (
        await client.query<Pick<PlaylistSongRecord, "source" | "songId" | "position">>(
          `SELECT source, song_id AS "songId", position
           FROM playlist_songs WHERE playlist_id = $1 ORDER BY position`,
          [playlistId],
        )
      ).rows;
      const existingKeys = existing.map((song) => `${song.source}\u0000${song.songId}`);
      const requestedKeys = keys.map((song) => `${song.source}\u0000${song.songId}`);
      if (
        existingKeys.length !== requestedKeys.length ||
        new Set(requestedKeys).size !== requestedKeys.length ||
        existingKeys.some((key) => !requestedKeys.includes(key))
      ) {
        throw new PlaylistOrderError("排序列表与歌单内容不一致，请刷新后重试");
      }
      const changed = existing.some((song, index) => song.source !== keys[index]?.source || song.songId !== keys[index]?.songId);
      if (changed) {
        // 唯一位置约束是立即检查的，先整体搬到临时区再写入目标位置，避免交换位置时冲突。
        await client.query(
          "UPDATE playlist_songs SET position = position + $2 WHERE playlist_id = $1",
          [playlistId, MAX_PLAYLIST_SONGS + 1],
        );
        for (const [position, key] of keys.entries()) {
          await client.query(
            `UPDATE playlist_songs SET position = $4, updated_at = $5
             WHERE playlist_id = $1 AND source = $2 AND song_id = $3`,
            [playlistId, key.source, key.songId, position, Date.now()],
          );
        }
        await this.bumpPlaylist(client, playlistId, Date.now());
      }
      return this.detailWithClient(client, playlistId, playlist);
    });
  }

  /** 完整替换歌曲集合，供云端同步或导入歌单使用。 */
  async replaceSongs(
    userId: number,
    playlistId: number,
    songs: PlaylistSongInput[],
  ): Promise<PlaylistDetail | undefined> {
    return this.database.transaction(async (client) => {
      const playlist = await this.lockPlaylist(client, userId, playlistId);
      if (!playlist) return undefined;
      if (songs.length > MAX_PLAYLIST_SONGS) throw new PlaylistOrderError(`歌单最多保存 ${MAX_PLAYLIST_SONGS} 首歌曲`);
      const unique = new Set(songs.map((song) => `${song.source}\u0000${song.songId}`));
      if (unique.size !== songs.length) throw new PlaylistOrderError("歌单中不能重复添加同一首歌曲");
      const now = Date.now();
      await client.query("DELETE FROM playlist_songs WHERE playlist_id = $1", [playlistId]);
      for (const [position, song] of songs.entries()) {
        await client.query(
          `INSERT INTO playlist_songs
             (playlist_id, source, song_id, mid, title, artist, album, cover_url,
              duration, audio_url, lyric_url, song_type, position, added_at, updated_at)
           VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $14)`,
          [
            playlistId,
            song.source,
            song.songId,
            song.mid ?? null,
            song.title ?? "",
            song.artist ?? "",
            song.album ?? "",
            song.coverUrl ?? null,
            song.duration ?? null,
            song.audioUrl ?? null,
            song.lyricUrl ?? null,
            song.type ?? null,
            position,
            now,
          ],
        );
      }
      await this.bumpPlaylist(client, playlistId, now);
      return this.detailWithClient(client, playlistId, playlist);
    });
  }

  private async lockPlaylist(client: PoolClient, userId: number, playlistId: number): Promise<PlaylistBase | undefined> {
    const result = await client.query<PlaylistBase>(
      `SELECT id, name, description, cover_url AS "coverUrl", revision,
              created_at AS "createdAt", updated_at AS "updatedAt"
       FROM playlists WHERE user_id = $1 AND id = $2 FOR UPDATE`,
      [userId, playlistId],
    );
    return result.rows[0];
  }

  private async bumpPlaylist(client: PoolClient, playlistId: number, now: number): Promise<void> {
    await client.query("UPDATE playlists SET revision = revision + 1, updated_at = $2 WHERE id = $1", [playlistId, now]);
  }

  private async normalizePositions(client: PoolClient, playlistId: number): Promise<void> {
    // 位置唯一约束立即生效，不能直接把 1 改成 0、0 改成 1；临时偏移后再归一化。
    await client.query(
      "UPDATE playlist_songs SET position = position + $2 WHERE playlist_id = $1",
      [playlistId, MAX_PLAYLIST_SONGS + 1],
    );
    await client.query(
      `WITH ordered AS (
         SELECT ctid, row_number() OVER (ORDER BY position) - 1 AS new_position
         FROM playlist_songs WHERE playlist_id = $1
       )
       UPDATE playlist_songs item
       SET position = ordered.new_position
       FROM ordered
       WHERE item.ctid = ordered.ctid`,
      [playlistId],
    );
  }

  private async detailWithClient(
    client: PoolClient,
    playlistId: number,
    _playlist: PlaylistBase,
  ): Promise<PlaylistDetail> {
    // 写事务刚递增了 revision，不能继续使用加锁时读到的旧对象；重新读取同一行让
    // 客户端拿到可用于下一次同步的最新版本号和更新时间。
    const playlist = (
      await client.query<PlaylistBase>(
        `SELECT id, name, description,
                COALESCE(cover_url, (
                  SELECT ps.cover_url FROM playlist_songs ps
                  WHERE ps.playlist_id = playlists.id AND ps.cover_url IS NOT NULL
                  ORDER BY ps.position ASC LIMIT 1
                )) AS "coverUrl", revision,
                created_at AS "createdAt", updated_at AS "updatedAt"
         FROM playlists WHERE id = $1`,
        [playlistId],
      )
    ).rows[0] ?? _playlist;
    const songs = (
      await client.query<PlaylistSongRecord>(
        `SELECT ${SONG_COLUMNS}
         FROM playlist_songs WHERE playlist_id = $1 ORDER BY position`,
        [playlistId],
      )
    ).rows;
    return { ...playlist, songCount: songs.length, songs };
  }

  private songSnapshotChanged(existing: StoredPlaylistSong, input: PlaylistSongInput): boolean {
    return (
      existing.mid !== (input.mid ?? null) ||
      existing.title !== (input.title ?? "") ||
      existing.artist !== (input.artist ?? "") ||
      existing.album !== (input.album ?? "") ||
      existing.coverUrl !== (input.coverUrl ?? null) ||
      existing.duration !== (input.duration ?? null) ||
      existing.audioUrl !== (input.audioUrl ?? null) ||
      existing.lyricUrl !== (input.lyricUrl ?? null) ||
      existing.type !== (input.type ?? null)
    );
  }
}
