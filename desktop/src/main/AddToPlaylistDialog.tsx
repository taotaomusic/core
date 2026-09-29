import { useEffect, useState } from "react";
import {
  addToPlaylist,
  readableError,
  SessionExpired,
  type PlaylistRecord,
  type Song,
} from "../api";
import { useApp } from "../state/AppState";
import { absoluteUrl } from "../api";
import { hideOnError } from "./img";
import "./playlists.css";

/**
 * 加入歌单弹窗：song 为 null 时不渲染。
 * 点歌单行直接加入（服务端幂等，重复添加也提示成功并关闭）；
 * 顶部「＋ 新建歌单」展开输入行，创建成功后立即把当前歌曲加入新歌单。
 * 挂遮罩 + 卡片（视觉规格对齐 pd-modal，类名自带 apd-*）。
 */
export function AddToPlaylistDialog({ song, onClose }: { song: Song | null; onClose: () => void }) {
  const { playlists, playlistsReady, createPlaylistAction, toast } = useApp();
  const [creating, setCreating] = useState(false); // 「＋ 新建歌单」输入行是否展开
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false); // 任一请求进行中，禁用全部按钮防连点

  // 每次打开（song 从空变为具体歌曲）时重置输入与请求状态
  useEffect(() => {
    if (song) {
      setCreating(false);
      setName("");
      setBusy(false);
    }
  }, [song]);

  // 打开期间监听 Esc 关闭；点遮罩关闭由遮罩 onClick 承担
  useEffect(() => {
    if (!song) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [song, onClose]);

  if (!song) return null;
  // 卫语句后捕获为常量：跨异步回调的窄化不依赖编译器版本，target 必为 Song
  const target: Song = song;

  /** 把当前歌曲加入既有歌单：统一成功文案后关闭。 */
  async function handleAdd(p: PlaylistRecord) {
    if (busy) return;
    setBusy(true);
    try {
      await addToPlaylist(p.id, target);
      toast(`已加入歌单「${p.name}」`);
      onClose();
    } catch (e) {
      if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一登出
      toast(readableError(e));
    } finally {
      setBusy(false);
    }
  }

  /** 新建歌单并把当前歌曲加进去；创建失败（返回 null）由 action 内部提示，这里静默收场。 */
  async function handleCreate() {
    const trimmed = name.trim();
    if (!trimmed || busy) return;
    setBusy(true);
    try {
      const created = await createPlaylistAction(trimmed);
      if (!created) return;
      await addToPlaylist(created.id, target);
      toast(`已加入歌单「${created.name}」`);
      onClose();
    } catch (e) {
      if (e instanceof SessionExpired) return;
      toast(readableError(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="apd-mask" onClick={onClose}>
      <div className="apd-card" onClick={(e) => e.stopPropagation()}>
        <div className="apd-title">加入歌单</div>
        <div className="apd-sub" title={`${song.title} - ${song.artist}`}>
          {song.title} - {song.artist}
        </div>
        {!playlistsReady ? (
          <div className="apd-loading">正在加载歌单…</div>
        ) : (
          <div className="apd-list">
            {playlists.length === 0 ? (
              <div className="apd-empty">还没有歌单，先新建一个吧</div>
            ) : (
              playlists.map((p) => (
                <button
                  key={p.id}
                  type="button"
                  className="apd-row"
                  disabled={busy}
                  onClick={() => void handleAdd(p)}
                >
                  <span className="apd-cover">
                    <span className="note">♪</span>
                    {p.coverUrl ? <img src={absoluteUrl(p.coverUrl)} alt="" onError={hideOnError} /> : null}
                  </span>
                  <span className="apd-row-name">{p.name}</span>
                  <span className="apd-row-count">{p.songCount} 首</span>
                </button>
              ))
            )}
            {creating ? (
              <div className="apd-create">
                <input
                  className="apd-input"
                  autoFocus
                  value={name}
                  maxLength={50}
                  placeholder="歌单名称"
                  disabled={busy}
                  onChange={(e) => setName(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") void handleCreate();
                  }}
                />
                <button
                  type="button"
                  className="apd-create-btn"
                  disabled={busy || !name.trim()}
                  onClick={() => void handleCreate()}
                >
                  创建
                </button>
              </div>
            ) : (
              <button
                type="button"
                className="apd-new"
                disabled={busy}
                onClick={() => setCreating(true)}
              >
                ＋ 新建歌单
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
