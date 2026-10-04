import { useState } from "react";
import type { UIEvent } from "react";
import { absoluteUrl, songKeyOf, type Song } from "../api";
import { useApp } from "../state/AppState";
import { AddToPlaylistDialog } from "./AddToPlaylistDialog";
import { hideOnError } from "./img";
import { SongRowMenu } from "./SongRowMenu";
import "./search.css";

/**
 * 通用歌曲列表：搜索页与收藏页共用。
 * 滚动容器（.list）在组件内部，因此无限滚动监听、骨架屏、错误/空态、
 * 加载更多页脚都由本组件处理；播放与收藏走 useApp()。
 * 每行行尾带「⋯」更多菜单（下一首播放 / 加入歌单 / 查看歌手 / 查看专辑），
 * 菜单打开状态用「当前打开的行 key」单值 state 管理，同一时刻只有一行展开。
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
  onOpenArtist,
  onOpenAlbum,
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
  /** 「⋯ → 查看歌手」回调：仅歌曲携带 artistId 时菜单项可见 */
  onOpenArtist?: (song: Song) => void;
  /** 「⋯ → 查看专辑」回调：仅歌曲携带 albumId 时菜单项可见 */
  onOpenAlbum?: (song: Song) => void;
}) {
  const { current, isFavorite, toggleFavorite, playList, toast } = useApp();
  const currentKey = current != null ? songKeyOf(current) : null;
  // 当前展开「⋯」菜单的行 key（songKeyOf）；null 表示全部收起
  const [menuKey, setMenuKey] = useState<string | null>(null);
  // 「加入歌单」弹窗的目标歌曲；null 表示弹窗关闭
  const [addToSong, setAddToSong] = useState<Song | null>(null);

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
    <>
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
          const key = songKeyOf(s);
          const active = currentKey !== null && key === currentKey;
          const off = s.playable === false;
          const fav = isFavorite(s);
          return (
            <div
              key={key}
              className={`song${active ? " active" : ""}${off ? " songrow-off" : ""}`}
              onClick={() => handleRowClick(i, s)}
            >
              <div className="songrow-cover">
                <span className="note">♪</span>
                {/* 封面地址过 absoluteUrl 归一：相对路径补 API 域名，避免 webview 裂图 */}
                {s.coverUrl ? (
                  <img src={absoluteUrl(s.coverUrl)} alt="" onError={hideOnError} />
                ) : null}
              </div>
              <div className="meta">
                <div className="songrow-name">
                  <span className="name">{s.title}</span>
                  {s.vip ? <span className="vip-badge">VIP</span> : null}
                </div>
                <div className="artist">{s.album ? `${s.artist} · ${s.album}` : s.artist}</div>
              </div>
              {/* 「⋯」更多菜单：hover 显色与心形一致，展开状态由 menuKey 单值管理 */}
              <SongRowMenu
                song={s}
                open={menuKey === key}
                onToggle={() => setMenuKey((k) => (k === key ? null : key))}
                onClose={() => setMenuKey((k) => (k === key ? null : k))}
                onOpenAddToPlaylist={(song) => {
                  setMenuKey(null);
                  setAddToSong(song);
                }}
                onOpenArtist={onOpenArtist}
                onOpenAlbum={onOpenAlbum}
              />
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
      {/* 加入歌单弹窗：由行「⋯ → 加入歌单」打开，song 为 null 时不渲染 */}
      <AddToPlaylistDialog song={addToSong} onClose={() => setAddToSong(null)} />
    </>
  );
}
