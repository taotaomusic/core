import type { Song } from "../api";
import { hideOnError } from "./img";

/** 搜索结果列表。点某首 → 以整列为队列从该曲播放。 */
export function SongList({
  songs,
  loading,
  error,
  current,
  onPlay,
}: {
  songs: Song[];
  loading: boolean;
  error: string;
  current: Song | null;
  onPlay: (index: number) => void;
}) {
  return (
    <div className="list">
      {loading && <p className="hint-center">搜索中…</p>}
      {error && <p className="hint-center" style={{ color: "#d33" }}>{error}</p>}
      {!loading && !error && songs.length === 0 && <p className="hint-center">输入关键词开始搜索</p>}
      {songs.map((s, i) => {
        const active = !!current && current.id === s.id && current.mid === s.mid;
        return (
          <div key={`${s.source}:${s.id}:${s.mid ?? ""}`} className={`song ${active ? "active" : ""}`} onClick={() => onPlay(i)}>
            <img className="cover" src={s.coverUrl || ""} alt="" onError={hideOnError} />
            <div className="meta">
              <div className="name">{s.title}</div>
              <div className="artist">{s.artist}</div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
