import { useEffect, useRef, useState } from "react";
import { createSongShare, readableError, SessionExpired } from "../api";
import { useApp } from "../state/AppState";
import { hideOnError } from "./img";
import "./player.css";

/** 秒 → mm:ss；负数或非有效数字给 --:--。 */
function formatTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return "--:--";
  const total = Math.floor(sec);
  const mm = String(Math.floor(total / 60)).padStart(2, "0");
  const ss = String(total % 60).padStart(2, "0");
  return `${mm}:${ss}`;
}

/** 循环模式按钮的悬浮提示文案。 */
function repeatTitle(mode: "off" | "all" | "one"): string {
  if (mode === "one") return "循环：单曲循环";
  if (mode === "all") return "循环：列表循环";
  return "循环：关闭";
}

/** 分享接口的返回结构（url 形如 https://域名/s/token）。 */
type ShareResult = { token: string; url: string };

/** 复制文本到剪贴板：优先 Clipboard API，失败退回 textarea + execCommand。 */
async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const ta = document.createElement("textarea");
      ta.value = text;
      // 固定定位 + 透明，避免复制瞬间页面跳动或闪出输入框
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand("copy");
      ta.remove();
      return ok;
    } catch {
      return false;
    }
  }
}

/**
 * 全屏播放详情页：大封面 + 歌名/收藏 + 自绘进度条 + 传输控制 + 播放队列面板 + 分享弹窗。
 * 对标安卓 PlayerDetailPage；showDetail 为 false 时渲染 null。
 */
export function PlayerDetail() {
  const app = useApp();
  const [showQueue, setShowQueue] = useState(true);
  // 拖动进度条时的预览位置（秒）；null 表示未在拖动
  const [dragSec, setDragSec] = useState<number | null>(null);
  const [sharing, setSharing] = useState(false);
  const [share, setShare] = useState<ShareResult | null>(null);
  const trackRef = useRef<HTMLDivElement>(null);

  // ESC 关闭分享弹窗
  useEffect(() => {
    if (!share) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setShare(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [share]);

  const current = app.current;
  const duration = app.duration;
  const fav = current ? app.isFavorite(current) : false;
  // 拖动中优先显示预览位置，否则用实时播放位置
  const shownSec = dragSec ?? app.position;
  const pct = duration > 0 ? Math.min(100, (shownSec / duration) * 100) : 0;

  /** 指针在滑轨上的水平位置换算成秒（已夹到 0..duration）。 */
  function secFromEvent(e: React.PointerEvent<HTMLDivElement>): number {
    const el = trackRef.current;
    if (!el || duration <= 0) return 0;
    const rect = el.getBoundingClientRect();
    const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width));
    return ratio * duration;
  }

  function onTrackDown(e: React.PointerEvent<HTMLDivElement>) {
    if (duration <= 0) return;
    e.currentTarget.setPointerCapture(e.pointerId);
    setDragSec(secFromEvent(e));
  }

  function onTrackMove(e: React.PointerEvent<HTMLDivElement>) {
    if (dragSec === null) return;
    setDragSec(secFromEvent(e));
  }

  function onTrackUp(e: React.PointerEvent<HTMLDivElement>) {
    if (dragSec === null) return;
    setDragSec(null);
    app.seek(secFromEvent(e));
  }

  /** 收起详情页：顺手清掉分享弹窗与拖动残留状态。 */
  function collapse() {
    setShare(null);
    setDragSec(null);
    app.setShowDetail(false);
  }

  /** 请求分享链接；会话过期静默（由顶层会话态处理），其余错误 toast。 */
  async function startShare() {
    if (!current || sharing) return;
    setSharing(true);
    try {
      setShare(await createSongShare(current));
    } catch (e) {
      if (e instanceof SessionExpired) return;
      app.toast(readableError(e));
    } finally {
      setSharing(false);
    }
  }

  /** 复制分享链接；成功 toast 并关弹窗，失败提示手动复制。 */
  async function copyShareLink() {
    if (!share) return;
    const ok = await copyText(share.url);
    if (ok) {
      app.toast("链接已复制，去粘贴给朋友吧");
      setShare(null);
    } else {
      app.toast("复制失败，请手动选中链接复制");
    }
  }

  if (!app.showDetail) return null;

  return (
    <div className="pd-overlay">
      {/* 顶栏：收起 + 标题 */}
      <div className="pd-top">
        <button className="pd-collapse" title="收起" onClick={collapse}>⌄</button>
        <div className="pd-top-title">正在播放</div>
        <div />
      </div>
      {current ? (
        <div className="pd-body">
          <div className="pd-main">
            <div className="pd-cover">
              <span className="pd-cover-note">♪</span>
              {current.coverUrl ? <img src={current.coverUrl} alt="" onError={hideOnError} /> : null}
            </div>
            <div className="pd-title-row">
              <div className="pd-song" title={current.title}>{current.title}</div>
              {!!current.vip && <span className="pd-vip">VIP</span>}
              <button
                className={`pd-heart${fav ? " on" : ""}`}
                title={fav ? "取消收藏" : "收藏"}
                onClick={() => app.toggleFavorite(current)}
              >
                {fav ? "♥" : "♡"}
              </button>
            </div>
            <div className="pd-artist">{current.artist}</div>

            {/* 下部控制区：进度条 + 快捷操作 + 传输控制 */}
            <div className="pd-controls">
              <div className="pd-slider-row">
                <span className="pd-time">{duration > 0 ? formatTime(shownSec) : "--:--"}</span>
                <div
                  className={`pd-track${duration > 0 ? "" : " disabled"}`}
                  ref={trackRef}
                  onPointerDown={onTrackDown}
                  onPointerMove={onTrackMove}
                  onPointerUp={onTrackUp}
                >
                  <div className="pd-track-fill" style={{ width: `${pct}%` }} />
                  <div className="pd-track-thumb" style={{ left: `${pct}%` }} />
                </div>
                <span className="pd-time">{duration > 0 ? formatTime(duration) : "--:--"}</span>
              </div>
              <div className="pd-quick">
                <button className="pd-quick-btn" disabled={sharing} onClick={() => { void startShare(); }}>
                  {sharing ? "分享中…" : "↗ 分享"}
                </button>
                <button className="pd-quick-btn" onClick={() => setShowQueue((v) => !v)}>☰ 队列</button>
              </div>
              <div className="pd-transport">
                <button
                  className="pd-tbtn"
                  data-mode={app.repeat}
                  title={repeatTitle(app.repeat)}
                  onClick={() => app.cycleRepeat()}
                >
                  ♻
                  {app.repeat === "one" && <span className="pd-repeat-badge">1</span>}
                </button>
                <button className="pd-tbtn" title="上一首" onClick={() => app.prev()}>⏮</button>
                <button
                  className="pd-play"
                  title={app.isPlaying ? "暂停" : "播放"}
                  onClick={() => app.togglePlay()}
                >
                  {app.isPlaying ? "⏸" : "▶"}
                </button>
                <button className="pd-tbtn" title="下一首" onClick={() => app.next()}>⏭</button>
              </div>
            </div>
          </div>

          {/* 播放队列面板 */}
          {showQueue && (
            <div className="pd-queue">
              <div className="pd-queue-head">
                <span className="pd-queue-title">播放队列</span>
                <span className="pd-queue-count">{app.queue.length} 首</span>
              </div>
              <div className="pd-queue-list">
                {app.queue.length === 0 && <div className="pd-queue-empty">队列为空</div>}
                {app.queue.map((s, i) => {
                  const active = i === app.index;
                  return (
                    <div
                      key={`${s.source}:${s.id}:${s.mid ?? ""}`}
                      className={`pd-queue-row${active ? " active" : ""}`}
                      onClick={() => app.playAt(i)}
                    >
                      <span className="pd-queue-idx">{active ? "▶" : i + 1}</span>
                      <span className="pd-queue-meta">
                        <span className="pd-queue-name">{s.title}</span>
                        <span className="pd-queue-artist">{s.artist}</span>
                      </span>
                    </div>
                  );
                })}
              </div>
              <div className="pd-queue-foot">共 {app.queue.length} 首</div>
            </div>
          )}
        </div>
      ) : (
        <div className="pd-empty">未在播放</div>
      )}

      {/* 分享弹窗 */}
      {share && (
        <div
          className="pd-modal-mask"
          onMouseDown={(e) => { if (e.target === e.currentTarget) setShare(null); }}
        >
          <div className="pd-modal">
            <div className="pd-modal-title">分享歌曲</div>
            <div className="pd-modal-song">
              {current ? `${current.title} - ${current.artist}` : ""}
            </div>
            <input
              className="pd-modal-link"
              readOnly
              value={share.url}
              onFocus={(e) => e.currentTarget.select()}
            />
            <div className="pd-modal-actions">
              <button className="pd-modal-btn primary" onClick={() => { void copyShareLink(); }}>复制链接</button>
              <button className="pd-modal-btn" onClick={() => setShare(null)}>完成</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
