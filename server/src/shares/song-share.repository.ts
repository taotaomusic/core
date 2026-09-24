import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";
import type { MusicSource } from "../upstream/music-source.client";

export type SongShareRecord = {
  token: string;
  user_id: number;
  source: MusicSource;
  song_id: string;
  remote_id: string | null;
  mid: string | null;
  song_type: number | null;
  title: string;
  artist: string;
  album: string;
  cover_url: string | null;
  duration_seconds: number;
  vip: number;
  enabled: number;
  access_count: number;
  created_at: number;
  updated_at: number;
};

export type SongShareSnapshot = Pick<
  SongShareRecord,
  "source" | "song_id" | "remote_id" | "mid" | "song_type" | "title" | "artist" | "album" | "cover_url" | "duration_seconds" | "vip"
>;

@Injectable()
export class SongShareRepository {
  constructor(private readonly database: DatabaseService) {}

  upsert(userId: number, token: string, song: SongShareSnapshot): Promise<SongShareRecord> {
    const now = Date.now();
    return this.database.transaction(async (client) => {
      const result = await client.query<SongShareRecord>(
        `INSERT INTO song_share
           (token, user_id, source, song_id, remote_id, mid, song_type, title, artist, album,
            cover_url, duration_seconds, vip, created_at, updated_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $14)
         ON CONFLICT (user_id, source, song_id) DO UPDATE SET
           remote_id = excluded.remote_id,
           mid = excluded.mid,
           song_type = excluded.song_type,
           title = excluded.title,
           artist = excluded.artist,
           album = excluded.album,
           cover_url = excluded.cover_url,
           duration_seconds = excluded.duration_seconds,
           vip = excluded.vip,
           enabled = 1,
           updated_at = excluded.updated_at
         RETURNING *`,
        [
          token,
          userId,
          song.source,
          song.song_id,
          song.remote_id,
          song.mid,
          song.song_type,
          song.title,
          song.artist,
          song.album,
          song.cover_url,
          song.duration_seconds,
          song.vip,
          now,
        ],
      );
      const record = result.rows[0];
      if (!record) throw new Error("歌曲分享记录写入失败");
      return record;
    });
  }

  find(token: string): Promise<SongShareRecord | undefined> {
    return this.database.first<SongShareRecord>(
      "SELECT * FROM song_share WHERE token = $1 AND enabled = 1",
      [token],
    );
  }

  async noteAccess(token: string): Promise<void> {
    await this.database.run(
      "UPDATE song_share SET access_count = access_count + 1 WHERE token = $1 AND enabled = 1",
      [token],
    );
  }
}
