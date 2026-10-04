import { useEffect, useRef } from "react";
import type { Song } from "../api";
import { useApp } from "../state/AppState";
import "./playlists.css";

/**
 * 歌曲行「⋯」更多菜单：行内浮层，含「下一首播放」「加入歌单」以及可选的
 * 「查看歌手」「查看专辑」（仅当调用方提供对应回调且歌曲携带对应 id 才出现）。
 * 组件自身不持有打开状态：open / onToggle / onClose 由调用方
 * （SongList / PlaylistsPage）用「当前打开的行 key」单值 state 控制，
 * 保证同一时刻只有一行开菜单。点浮层外部或按 Esc 收起。
 */
export function SongRowMenu({
  song,
  open,
  onToggle,
  onClose,
  onOpenAddToPlaylist,
  onOpenArtist,
  onOpenAlbum,
}: {
  song: Song;
  /** 本行菜单是否展开 */
  open: boolean;
  /** 点击「⋯」切换展开 / 收起 */
  onToggle: () => void;
  /** 点外部 / Esc 时收起（调用方把打开的行 key 置空） */
  onClose: () => void;
  /** 点击「加入歌单」：交给调用方打开加入歌单弹窗 */
  onOpenAddToPlaylist: (song: Song) => void;
  /** 点击「查看歌手」：不提供或歌曲没有 artistId 时该项不出现 */
  onOpenArtist?: (song: Song) => void;
  /** 点击「查看专辑」：不提供或歌曲没有 albumId 时该项不出现 */
  onOpenAlbum?: (song: Song) => void;
}) {
  const { playNext } = useApp();
  const wrapRef = useRef<HTMLSpanElement>(null);

  // 展开期间监听 document 点击与 Esc：点浮层外或按 Esc 都收起。
  // 「⋯」与菜单项的点击在容器上 stopPropagation，不会触发这里的关闭逻辑。
  useEffect(() => {
    if (!open) return;
    const handleDocClick = (e: MouseEvent) => {
      if (wrapRef.current && e.target instanceof Node && wrapRef.current.contains(e.target)) return;
      onClose();
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("click", handleDocClick);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("click", handleDocClick);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [open, onClose]);

  return (
    <span ref={wrapRef} className="srmenu-wrap" onClick={(e) => e.stopPropagation()}>
      <button
        type="button"
        className={`srmenu-trigger${open ? " on" : ""}`}
        title="更多操作"
        onClick={(e) => {
          e.stopPropagation();
          onToggle();
        }}
      >
        ⋯
      </button>
      {open && (
        <div className="srmenu-pop" role="menu">
          <button
            type="button"
            className="srmenu-item"
            onClick={() => {
              // playNext 内部已自带 toast（「已添加到下一首播放」），这里只调用并收起菜单，避免重复提示
              playNext(song);
              onClose();
            }}
          >
            下一首播放
          </button>
          <button
            type="button"
            className="srmenu-item"
            onClick={() => {
              onClose();
              onOpenAddToPlaylist(song);
            }}
          >
            加入歌单
          </button>
          {/* 跳转详情两项：回调与歌曲关联 id 都齐备才渲染，缺一不可见 */}
          {onOpenArtist && song.artistId != null && (
            <button
              type="button"
              className="srmenu-item"
              onClick={() => {
                onClose();
                onOpenArtist(song);
              }}
            >
              查看歌手
            </button>
          )}
          {onOpenAlbum && song.albumId != null && (
            <button
              type="button"
              className="srmenu-item"
              onClick={() => {
                onClose();
                onOpenAlbum(song);
              }}
            >
              查看专辑
            </button>
          )}
        </div>
      )}
    </span>
  );
}
