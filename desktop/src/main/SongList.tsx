import type { UIEvent } from "react";
import { songKeyOf, type Song } from "../api";
import { useApp } from "../state/AppState";
import { hideOnError } from "./img";
import "./search.css";

/**
 * 通用歌曲列表：搜索页与收藏页共用。
 * 滚动容器（.list）在组件内部，因此无限滚动监听、骨架屏、错误/空态、
 * 加载更多页脚都由本组件处理；播放与收藏走 useApp()。
 */
export function SongList({
  songs,
  loading = false,
  error = "",
  emptyHint = "暂无歌曲",
  hasMore = false,
  total,
  onLoadMore,
  loadingMore = false,
  onPlayIndex,
}: {
  songs: Song[];
  /** 首屏加载中：显示骨架屏 */
  loading?: boolean;
  /** 错误文案（红字）；已有结果时显示在列表底部，可继续下滑重试 */
  error?: string;
  /** 空列表提示文案 */
  emptyHint?: string;
  /** 是否还有下一页；false 且有结果时显示收口页脚 */
  hasMore?: boolean;
  /** 服务端命中总数，用于收口页脚「已显示全部 N 首（共搜到 total 首）」 */
  total?: number;
  /** 列表距底不足 300px 时触发（由调用方加载下一页） */
  onLoadMore?: () => void;
  /** 下一页加载中：页脚显示「正在加载更多…」 */
  loadingMore?: boolean;
  /** 行点击播放回调；缺省时整列设为队列并从该曲播放 */
  onPlayIndex?: (i: number) => void;
}) {
  const { current, isFavorite, toggleFavorite, playList, toast } = useApp();
  const currentKey = current != null ? songKeyOf(current) : null;

  /** 无限滚动：距底不足 300px 且允许加载时触发 onLoadMore；并发去抖由调用方 busy 标记保证。 */
  function handleScroll(e: UIEvent<HTMLDivElement>) {
    if (!onLoadMore || !hasMore || loading || loadingMore) return;
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300) onLoadMore();
  }

  /** 行点击：不可播放只提示不播放；否则交给 onPlayIndex 或默认整列播放。 */
  function handleRowClick(i: number, s: Song) {
    if (s.playable === false) {
      toast("该歌曲暂不可播放");
      return;
    }
    if (onPlayIndex) onPlayIndex(i);
    else playList(songs, i);
  }

  return (
    <div className="list" onScroll={handleScroll}>
      {loading && songs.length === 0 && (
        <div className="sp-skel">
          {Array.from({ length: 6 }, (_, i) => (
            <div key={i} className="sp-skel-row">
              <div className="sp-skel-cover" />
              <div className="sp-skel-lines">
                <div className="sp-skel-line w60" />
                <div className="sp-skel-line w35" />
              </div>
            </div>
          ))}
        </div>
      )}
      {!loading && error !== "" && songs.length === 0 && (
        <p className="hint-center sp-hint-err">{error}</p>
      )}
      {!loading && error === "" && songs.length === 0 && (
        <p className="hint-center">{emptyHint}</p>
      )}
      {songs.map((s, i) => {
        const active = currentKey !== null && songKeyOf(s) === currentKey;
        const off = s.playable === false;
        const fav = isFavorite(s);
        return (
          <div
            key={songKeyOf(s)}
            className={`song${active ? " active" : ""}${off ? " songrow-off" : ""}`}
            onClick={() => handleRowClick(i, s)}
          >
            <div className="songrow-cover">
              <span className="note">♪</span>
              {s.coverUrl ? <img src={s.coverUrl} alt="" onError={hideOnError} /> : null}
            </div>
            <div className="meta">
              <div className="songrow-name">
                <span className="name">{s.title}</span>
                {s.vip ? <span className="vip-badge">VIP</span> : null}
              </div>
              <div className="artist">{s.album ? `${s.artist} · ${s.album}` : s.artist}</div>
            </div>
            <button
              type="button"
              className={`songrow-fav${fav ? " on" : ""}`}
              title={fav ? "取消收藏" : "收藏"}
              onClick={(e) => {
                e.stopPropagation();
                toggleFavorite(s);
              }}
            >
              {fav ? "♥" : "♡"}
            </button>
          </div>
        );
      })}
      {!loading && error !== "" && songs.length > 0 && (
        <p className="songrow-more-err">{error}（可继续下滑重试）</p>
      )}
      {loadingMore && <p className="songrow-foot">正在加载更多…</p>}
      {!loadingMore && songs.length > 0 && !hasMore && (
        <p className="songrow-foot">
          {total != null
            ? `已显示全部 ${songs.length} 首（共搜到 ${total} 首）`
            : `已显示全部 ${songs.length} 首`}
        </p>
      )}
    </div>
  );
}
