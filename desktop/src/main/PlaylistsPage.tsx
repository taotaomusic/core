import { useEffect, useState } from "react";
import type { DragEvent as ReactDragEvent } from "react";
import {
  absoluteUrl,
  deletePlaylist,
  fetchPlaylistDetail,
  readableError,
  removeFromPlaylist,
  reorderPlaylist,
  SessionExpired,
  songKeyOf,
  updatePlaylist,
  type PlaylistDetail,
  type Song,
} from "../api";
import { useApp } from "../state/AppState";
import { AddToPlaylistDialog } from "./AddToPlaylistDialog";
import { hideOnError } from "./img";
import { SongRowMenu } from "./SongRowMenu";
// 曲目行复用 search.css 里的 songrow-* 全局类，显式导入保证样式自足
import "./search.css";
import "./playlists.css";

/**
 * 歌单页：列表（卡片网格）+ 详情（曲目列表）两级视图，内部 state 切换。
 * 详情行自带序号、心形收藏、「×」移除、整行拖拽排序与「⋯」菜单（复用 SongRowMenu）。
 */
export function PlaylistsPage() {
  const {
    playlists,
    playlistsReady,
    refreshPlaylists,
    createPlaylistAction,
    current,
    isFavorite,
    toggleFavorite,
    playList,
    toast,
  } = useApp();

  // ---- 视图与详情数据 ----
  const [view, setView] = useState<"list" | "detail">("list");
  const [detail, setDetail] = useState<PlaylistDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");

  // ---- 弹窗状态：新建（列表页）/ 重命名、删除确认（详情页）/ 加入歌单 ----
  const [createOpen, setCreateOpen] = useState(false);
  const [renameOpen, setRenameOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [addToSong, setAddToSong] = useState<Song | null>(null);

  // ---- 行内菜单：当前打开的行 key，单值保证同一时刻只有一行展开 ----
  const [menuKey, setMenuKey] = useState<string | null>(null);

  // ---- 拖拽排序 ----
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);
  // 移除请求进行中的行 key，防连点
  const [removingKey, setRemovingKey] = useState<string | null>(null);

  const currentKey = current != null ? songKeyOf(current) : null;

  /** 进入详情：先清旧数据再拉取，避免闪现上一份内容。 */
  async function openDetail(id: number) {
    setView("detail");
    setDetail(null);
    setDetailError("");
    setDetailLoading(true);
    try {
      setDetail(await fetchPlaylistDetail(id));
    } catch (e) {
      if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一登出
      setDetailError(readableError(e));
    } finally {
      setDetailLoading(false);
    }
  }

  /** 回到列表视图。 */
  function backToList() {
    setView("list");
    setDetail(null);
    setDetailError("");
  }

  /** 播放全部：空歌单只提示，否则整单设为队列从头播放。 */
  function handlePlayAll() {
    if (!detail) return;
    if (detail.songs.length === 0) {
      toast("歌单是空的，先添加几首吧");
      return;
    }
    playList(detail.songs, 0);
  }

  /** 行点击播放：不可播放只提示；否则整单设为队列并从该曲播放。 */
  function handleRowClick(i: number, s: Song) {
    if (s.playable === false) {
      toast("该歌曲暂不可播放");
      return;
    }
    if (detail) playList(detail.songs, i);
  }

  /** 从歌单移除一首：成功后按序号本地移除 + 轻提示，并刷新列表页的曲目计数。 */
  async function handleRemove(i: number, s: Song) {
    if (!detail || removingKey) return;
    setRemovingKey(songKeyOf(s));
    try {
      await removeFromPlaylist(detail.id, s);
      const playlistId = detail.id;
      setDetail((d) => (d && d.id === playlistId ? { ...d, songs: d.songs.filter((_, idx) => idx !== i) } : d));
      toast("已从歌单移除");
      void refreshPlaylists();
    } catch (e) {
      if (e instanceof SessionExpired) return;
      toast(readableError(e));
    } finally {
      setRemovingKey(null);
    }
  }

  /** 把重排结果提交到服务端；失败（含 400/4004 排序不一致）重新拉详情对账。 */
  async function commitReorder(playlistId: number, next: Song[]) {
    try {
      await reorderPlaylist(playlistId, next);
    } catch (e) {
      if (e instanceof SessionExpired) return;
      toast(readableError(e));
      try {
        setDetail(await fetchPlaylistDetail(playlistId));
      } catch {
        // 对账拉取失败：保留本地顺序，下次操作仍可重试
      }
    }
  }

  function handleDragStart(e: ReactDragEvent<HTMLDivElement>, i: number) {
    // Firefox 要求必须有 dataTransfer 数据才会启动拖拽
    e.dataTransfer.setData("text/plain", String(i));
    e.dataTransfer.effectAllowed = "move";
    setDragIndex(i);
  }

  function handleDragOver(e: ReactDragEvent<HTMLDivElement>, i: number) {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    if (overIndex !== i) setOverIndex(i);
  }

  function handleDrop(e: ReactDragEvent<HTMLDivElement>, i: number) {
    e.preventDefault();
    const from = dragIndex;
    setDragIndex(null);
    setOverIndex(null);
    if (from === null || from === i || !detail) return;
    // 本地重排：摘出被拖行，插到目标位置
    const next = detail.songs.slice();
    const moved = next.splice(from, 1)[0];
    next.splice(i, 0, moved);
    setDetail({ ...detail, songs: next });
    void commitReorder(detail.id, next);
  }

  function handleDragEnd() {
    setDragIndex(null);
    setOverIndex(null);
  }

  /** 重命名：成功后本地更新名称并同步列表页。 */
  async function handleRename(name: string) {
    if (!detail) return;
    const trimmed = name.trim();
    setRenameOpen(false);
    if (!trimmed || trimmed === detail.name) return;
    try {
      await updatePlaylist(detail.id, { name: trimmed });
      const playlistId = detail.id;
      setDetail((d) => (d && d.id === playlistId ? { ...d, name: trimmed } : d));
      toast("已重命名");
      void refreshPlaylists();
    } catch (e) {
      if (e instanceof SessionExpired) return;
      toast(readableError(e));
    }
  }

  /** 删除歌单：成功后回列表视图并刷新。 */
  async function handleDelete() {
    if (!detail) return;
    const target = detail;
    setDeleteOpen(false);
    try {
      await deletePlaylist(target.id);
      toast(`已删除歌单「${target.name}」`);
      setDetail(null);
      setView("list");
      void refreshPlaylists();
    } catch (e) {
      if (e instanceof SessionExpired) return;
      toast(readableError(e));
    }
  }

  /** 列表页新建歌单：成功提示由这里补，列表刷新由 action 内部完成。 */
  async function handleCreateNew(name: string) {
    const trimmed = name.trim();
    if (!trimmed) return;
    try {
      const created = await createPlaylistAction(trimmed);
      if (!created) return; // 失败已由 action 内部提示
      toast(`已创建歌单「${created.name}」`);
      setCreateOpen(false);
    } catch (e) {
      if (e instanceof SessionExpired) return;
      toast(readableError(e));
    }
  }

  return (
    <div className="pl-page">
      {view === "list" ? (
        <>
          <div className="pl-head">
            <div className="pl-title">歌单</div>
            <button type="button" className="pl-btn-new" onClick={() => setCreateOpen(true)}>
              ＋ 新建歌单
            </button>
          </div>
          {!playlistsReady ? (
            <p className="hint-center">正在加载歌单…</p>
          ) : playlists.length === 0 ? (
            <p className="hint-center">还没有歌单，点右上角「＋ 新建歌单」创建一个吧</p>
          ) : (
            <div className="pl-grid">
              {playlists.map((p) => (
                <div key={p.id} className="pl-card" onClick={() => void openDetail(p.id)}>
                  <div className="pl-card-cover">
                    <span className="note">♪</span>
                    {/* 封面地址过 absoluteUrl 归一：相对路径补 API 域名，避免 webview 裂图 */}
                    {p.coverUrl ? (
                      <img src={absoluteUrl(p.coverUrl)} alt="" onError={hideOnError} />
                    ) : null}
                  </div>
                  <div className="pl-card-name" title={p.name}>
                    {p.name}
                  </div>
                  <div className="pl-card-count">{p.songCount} 首</div>
                </div>
              ))}
            </div>
          )}
        </>
      ) : (
        <>
          <button type="button" className="pl-back" onClick={backToList}>
            ‹ 返回歌单列表
          </button>
          {detailLoading && !detail ? (
            <p className="hint-center">正在加载歌单…</p>
          ) : detailError && !detail ? (
            <p className="hint-center pl-hint-err">{detailError}</p>
          ) : detail ? (
            <>
              <div className="pl-detail-head">
                <div className="pl-detail-cover">
                  <span className="note">♪</span>
                  {detail.coverUrl ? (
                    <img src={absoluteUrl(detail.coverUrl)} alt="" onError={hideOnError} />
                  ) : null}
                </div>
                <div className="pl-detail-info">
                  <div className="pl-detail-name">{detail.name}</div>
                  <div className="pl-detail-sub">{detail.songs.length} 首</div>
                  <div className="pl-detail-actions">
                    <button type="button" className="pl-btn primary" onClick={handlePlayAll}>
                      ▶ 播放全部
                    </button>
                    <button type="button" className="pl-btn" onClick={() => setRenameOpen(true)}>
                      重命名
                    </button>
                    <button type="button" className="pl-btn danger" onClick={() => setDeleteOpen(true)}>
                      删除歌单
                    </button>
                  </div>
                </div>
              </div>
              <div className="pl-songs">
                {detail.songs.map((s, i) => {
                  const key = songKeyOf(s);
                  const active = currentKey !== null && key === currentKey;
                  const fav = isFavorite(s);
                  return (
                    <div
                      key={key}
                      className={`song pl-row${active ? " active" : ""}${
                        dragIndex === i ? " dragging" : ""
                      }${overIndex === i && dragIndex !== null && dragIndex !== i ? " drop-target" : ""}`}
                      draggable
                      onDragStart={(e) => handleDragStart(e, i)}
                      onDragOver={(e) => handleDragOver(e, i)}
                      onDrop={(e) => handleDrop(e, i)}
                      onDragEnd={handleDragEnd}
                      onClick={() => handleRowClick(i, s)}
                    >
                      <div className={`pl-index${active ? " on" : ""}`}>{active ? "▶" : i + 1}</div>
                      <div className="songrow-cover">
                        <span className="note">♪</span>
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
                      <SongRowMenu
                        song={s}
                        open={menuKey === key}
                        onToggle={() => setMenuKey((k) => (k === key ? null : key))}
                        onClose={() => setMenuKey((k) => (k === key ? null : k))}
                        onOpenAddToPlaylist={(song) => {
                          setMenuKey(null);
                          setAddToSong(song);
                        }}
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
                      <button
                        type="button"
                        className="pl-remove"
                        title="从歌单移除"
                        disabled={removingKey === key}
                        onClick={(e) => {
                          e.stopPropagation();
                          void handleRemove(i, s);
                        }}
                      >
                        ×
                      </button>
                    </div>
                  );
                })}
                {detail.songs.length === 0 && (
                  <p className="hint-center">歌单还是空的，去歌曲行「⋯ → 加入歌单」添加吧</p>
                )}
              </div>
            </>
          ) : null}
        </>
      )}

      {/* 新建歌单小弹窗（列表视图） */}
      {createOpen && (
        <PlPromptModal
          title="新建歌单"
          placeholder="歌单名称"
          confirmText="创建"
          onClose={() => setCreateOpen(false)}
          onConfirm={(name) => void handleCreateNew(name)}
        />
      )}

      {/* 重命名小弹窗（详情视图） */}
      {renameOpen && detail && (
        <PlPromptModal
          title="重命名歌单"
          placeholder="歌单名称"
          initial={detail.name}
          confirmText="保存"
          onClose={() => setRenameOpen(false)}
          onConfirm={(name) => void handleRename(name)}
        />
      )}

      {/* 删除确认小弹窗（详情视图） */}
      {deleteOpen && detail && (
        <div className="apd-mask" onClick={() => setDeleteOpen(false)}>
          <div className="apd-card" onClick={(e) => e.stopPropagation()}>
            <div className="apd-title">删除歌单</div>
            <div className="apd-sub">确定删除歌单「{detail.name}」吗？该操作不可恢复。</div>
            <div className="apd-actions">
              <button type="button" className="apd-btn" onClick={() => setDeleteOpen(false)}>
                取消
              </button>
              <button type="button" className="apd-btn danger" onClick={() => void handleDelete()}>
                删除
              </button>
            </div>
          </div>
        </div>
      )}

      <AddToPlaylistDialog song={addToSong} onClose={() => setAddToSong(null)} />
    </div>
  );
}

/**
 * 歌单页的小输入弹窗（新建 / 重命名共用 apd 弹窗规格）：
 * 遮罩点击与 Esc 关闭，Enter 直接确认；确认后的收起与否由父级回调决定。
 */
function PlPromptModal({
  title,
  placeholder,
  initial = "",
  confirmText,
  onConfirm,
  onClose,
}: {
  title: string;
  placeholder: string;
  initial?: string;
  confirmText: string;
  onConfirm: (name: string) => void;
  onClose: () => void;
}) {
  const [name, setName] = useState(initial);
  const trimmed = name.trim();

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    document.addEventListener("keydown", handleKeyDown);
    return () => document.removeEventListener("keydown", handleKeyDown);
  }, [onClose]);

  return (
    <div className="apd-mask" onClick={onClose}>
      <div className="apd-card" onClick={(e) => e.stopPropagation()}>
        <div className="apd-title">{title}</div>
        <div className="apd-create">
          <input
            className="apd-input"
            autoFocus
            value={name}
            maxLength={50}
            placeholder={placeholder}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && trimmed) onConfirm(trimmed);
            }}
          />
          <button
            type="button"
            className="apd-create-btn"
            disabled={!trimmed}
            onClick={() => onConfirm(trimmed)}
          >
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
}
