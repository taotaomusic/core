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
import { Dialog, DialogContent, DialogTitle } from "../ui/radix";

/**
 * 加入歌单弹窗：song 为 null 时不渲染。
 * 点歌单行直接加入（服务端幂等，重复添加也提示成功并关闭）；
 * 顶部「＋ 新建歌单」展开输入行，创建成功后立即把当前歌曲加入新歌单。
 * 弹窗骨架改用组件库（Radix Dialog）：遮罩 + 卡片沿用原 apd-* 视觉，
 * Esc / 点遮罩关闭、焦点圈定与滚动锁定交由 Radix 处理。
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
    <Dialog open onOpenChange={(next) => { if (!next) onClose(); }}>
      <DialogContent aria-describedby={undefined}>
        {/* Radix Title 提供无障碍标题，视觉上隐藏；可见标题由下方 apd-title 承担 */}
        <DialogTitle>加入歌单</DialogTitle>
        <div className="apd-title" aria-hidden>加入歌单</div>
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
      </DialogContent>
    </Dialog>
  );
}
