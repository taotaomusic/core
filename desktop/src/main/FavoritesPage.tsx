import { useApp } from "../state/AppState";
import { SongList } from "./SongList";

/** 收藏页：云端收藏库列表（/favorites），歌曲元数据由 AppState 批量补全。 */
export function FavoritesPage() {
  const { favSongs, favReady, refreshFavorites } = useApp();
  return (
    <div className="favpage">
      <div className="favpage-head">
        <div>
          <div className="favpage-title">我的收藏</div>
          <div className="favpage-sub">
            {favReady ? `共 ${favSongs.length} 首 · 列表行尾点亮小心心即可收藏` : "正在加载收藏…"}
          </div>
        </div>
        <button className="link" onClick={() => void refreshFavorites()}>
          刷新
        </button>
      </div>
      <SongList
        songs={favSongs}
        loading={!favReady}
        emptyHint="还没有收藏的歌曲，去搜索页点亮小心心吧"
      />
    </div>
  );
}
