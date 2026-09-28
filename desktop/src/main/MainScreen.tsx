import { useState } from "react";
import { searchSongs, SessionExpired, readableError, type Song } from "../api";
import { usePlayer } from "./usePlayer";
import { SearchBar } from "./SearchBar";
import { SongList } from "./SongList";
import { PlayerBar } from "./PlayerBar";

/** 登录后的主界面：搜索 + 结果列表 + 底部播放条。会话过期回登录页。 */
export function MainScreen({ onLogout }: { onLogout: () => void }) {
  const [songs, setSongs] = useState<Song[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const player = usePlayer(onLogout);

  async function doSearch(kw: string) {
    setLoading(true);
    setError("");
    try {
      setSongs(await searchSongs(kw));
    } catch (e) {
      if (e instanceof SessionExpired) return onLogout();
      setError(readableError(e));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="app">
      <SearchBar onSearch={doSearch} onLogout={onLogout} />
      <SongList
        songs={songs}
        loading={loading}
        error={error || player.error}
        current={player.current}
        onPlay={(i) => player.playFrom(songs, i)}
      />
      <PlayerBar
        current={player.current}
        audioRef={player.audioRef}
        hasPrev={player.hasPrev}
        hasNext={player.hasNext}
        onPrev={player.prev}
        onNext={player.next}
      />
    </div>
  );
}
