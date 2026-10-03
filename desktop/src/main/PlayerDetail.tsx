import { useEffect, useRef, useState } from "react";
import {
  absoluteUrl,
  createSongShare,
  fetchQualityTiers,
  labelOfQuality,
  readableError,
  refrainRangeOf,
  SessionExpired,
  type QualityTier,
} from "../api";
import { useApp } from "../state/AppState";
import { hideOnError } from "./img";
import {
  IconChevronDown,
  IconClock,
  IconClose,
  IconHeart,
  IconMusicNote,
  IconNext,
  IconPause,
  IconPlay,
  IconPrev,
  IconQuality,
  IconQueue,
  IconRepeat,
  IconShare,
} from "./icons";
import { LyricsPane } from "./LyricsPane";
import "./player.css";

/** 秒 → mm:ss；负数或非有效数字给 --:--。 */
function formatTime(sec: number): string {
  if (!Number.isFinite(sec) || sec < 0) return "--:--";
  const total = Math.floor(sec);
  const mm = String(Math.floor(total / 60)).padStart(2, "0");
  const ss = String(total % 60).padStart(2, "0");
  return `${mm}:${ss}`;
}

/** 定时关闭剩余秒 → 倒计时文案：不足 1 小时用 mm:ss，超过则 h:mm:ss。 */
function formatSleepRemaining(sec: number): string {
  const total = Math.max(0, Math.floor(sec));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = String(m).padStart(2, "0");
  const ss = String(s).padStart(2, "0");
  if (h > 0) return `${h}:${mm}:${ss}`;
  return `${mm}:${ss}`;
}

/** 循环模式按钮的悬浮提示文案。 */
function repeatTitle(mode: "off" | "all" | "one"): string {
  if (mode === "one") return "循环：单曲循环";
  if (mode === "all") return "循环：列表循环";
  return "循环：关闭";
}

/** 字节数 → 体积文案：≥1GB 显示 x.x GB，否则 x.x MB；无效值返回 null（界面不显示大小）。 */
function formatBytes(size: number): string | null {
  if (!Number.isFinite(size) || size <= 0) return null;
  if (size >= 1024 * 1024 * 1024) return `${(size / (1024 * 1024 * 1024)).toFixed(1)} GB`;
  return `${(size / (1024 * 1024)).toFixed(1)} MB`;
}

/** 分享接口的返回结构（url 形如 https://域名/s/token）。 */
type ShareResult = { token: string; url: string };

/** 定时关闭弹窗提供的热门时长选项（分钟）。 */
const SLEEP_MINUTES = [10, 20, 30, 45, 60, 90];

/** 详情页退场动画时长（毫秒），与 player.css 里 .pd-overlay-exit 的 0.16s 对齐。 */
const PD_CLOSE_MS = 160;

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
 * 全屏播放详情页：大封面/歌词双视图 + 歌名/收藏 + 自绘进度条 + 传输控制
 * + 播放队列面板（拖动排序/删除）+ 分享/音质/定时关闭弹窗。
 * 对标安卓 PlayerDetailPage；showDetail 为 false 时渲染 null。
 */
export function PlayerDetail() {
  const app = useApp();
  // 队列面板显隐：宽窗默认并排展示；窄窗（≤980px，浮层模式）默认收起，点「队列」按钮以浮层弹出
  const [showQueue, setShowQueue] = useState(
    () => !window.matchMedia("(max-width: 980px)").matches,
  );
  // 拖动进度条时的预览位置（秒）；null 表示未在拖动
  const [dragSec, setDragSec] = useState<number | null>(null);
  const [sharing, setSharing] = useState(false);
  const [share, setShare] = useState<ShareResult | null>(null);
  // 左列视图：封面 / 歌词（内部 state，对标安卓中部 Pager，只换中间区域）
  const [lyricsTab, setLyricsTab] = useState<"cover" | "lyrics">("cover");
  // 音质面板：打开态 / 档位列表（null=加载中）/ 失败文案 / 重试计数
  const [qualityOpen, setQualityOpen] = useState(false);
  const [tiers, setTiers] = useState<QualityTier[] | null>(null);
  const [tierErr, setTierErr] = useState("");
  const [tiersNonce, setTiersNonce] = useState(0);
  // 定时关闭弹窗
  const [sleepOpen, setSleepOpen] = useState(false);
  // 队列拖动排序：来源行下标 / 悬停落点行下标（null=未在拖动）
  const [dragFrom, setDragFrom] = useState<number | null>(null);
  const [dragOver, setDragOver] = useState<number | null>(null);
  // 退场动画：showDetail 变 false 后先保留 160ms 播淡出，再真正卸载
  const [closing, setClosing] = useState(false);
  // 上一次 showDetail 值：区分「初始就是关闭」与「刚从打开收起」
  const wasOpenRef = useRef(app.showDetail);
  // 退场计时器句柄：退场中途重新打开时先取消，避免到点把页面卸载掉
  const closeTimerRef = useRef<number | null>(null);
  const trackRef = useRef<HTMLDivElement>(null);

  // ESC 关闭最上层弹窗：音质 > 定时 > 分享
  useEffect(() => {
    if (!share && !qualityOpen && !sleepOpen) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "Escape") return;
      if (qualityOpen) setQualityOpen(false);
      else if (sleepOpen) setSleepOpen(false);
      else setShare(null);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [share, qualityOpen, sleepOpen]);

  // 详情页进出场：打开时立即取消未完成的退场（回到进场动画）；
  // 收起时先播 160ms 淡出再卸载。ESC 收起与 ⌄ 收起都走 setShowDetail(false)，这里统一接管。
  useEffect(() => {
    const wasOpen = wasOpenRef.current;
    wasOpenRef.current = app.showDetail;
    if (app.showDetail) {
      if (closeTimerRef.current !== null) {
        window.clearTimeout(closeTimerRef.current);
        closeTimerRef.current = null;
      }
      setClosing(false);
    } else if (wasOpen) {
      setClosing(true);
      closeTimerRef.current = window.setTimeout(() => {
        closeTimerRef.current = null;
        setClosing(false);
      }, PD_CLOSE_MS);
    }
  }, [app.showDetail]);

  // 卸载时清退场计时器，避免定时器泄漏
  useEffect(() => {
    return () => {
      if (closeTimerRef.current !== null) window.clearTimeout(closeTimerRef.current);
    };
  }, []);

  // 打开音质面板时拉取当前歌曲的可用档位；tiersNonce 供「重试」按钮再次触发
  useEffect(() => {
    if (!qualityOpen) return;
    const song = app.current;
    if (!song) return;
    let alive = true;
    setTiers(null);
    setTierErr("");
    fetchQualityTiers(song)
      .then((list) => {
        if (alive) setTiers(list);
      })
      .catch((e) => {
        if (!alive) return;
        if (e instanceof SessionExpired) {
          setQualityOpen(false); // 会话过期由顶层状态处理，这里静默收起面板
          return;
        }
        setTiers([]);
        setTierErr(readableError(e));
      });
    return () => {
      alive = false;
    };
  }, [qualityOpen, app.current, tiersNonce]);

  const current = app.current;
  const duration = app.duration;
  const fav = current ? app.isFavorite(current) : false;
  // 拖动中优先显示预览位置，否则用实时播放位置
  const shownSec = dragSec ?? app.position;
  const pct = duration > 0 ? Math.min(100, (shownSec / duration) * 100) : 0;
  // 高潮区间标记与小字：与进度条同用一个真实时长做分母；区间无效（缺值/时长未知）时都不显示
  const refrain = refrainRangeOf(current, duration);
  // 定时关闭：顶栏按钮高亮 / 按钮文案 / 悬浮提示 / 弹窗中与剩余时间对应的分钟选项
  const sleepOn = app.sleepPendingStop || app.sleepRemainingSec != null;
  const sleepLabel = app.sleepPendingStop
    ? "本首后停"
    : app.sleepRemainingSec != null
      ? formatSleepRemaining(app.sleepRemainingSec)
      : "定时";
  const sleepTitle = app.sleepPendingStop
    ? "定时关闭：将在本首播完后暂停"
    : app.sleepRemainingSec != null
      ? `定时关闭：剩余 ${formatSleepRemaining(app.sleepRemainingSec)}`
      : "定时关闭";
  const sleepRemainMin =
    app.sleepRemainingSec != null ? Math.round(app.sleepRemainingSec / 60) : null;

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

  /** 收起详情页：顺手清掉弹窗与拖动残留状态。 */
  function collapse() {
    setShare(null);
    setQualityOpen(false);
    setSleepOpen(false);
    setDragSec(null);
    setDragFrom(null);
    setDragOver(null);
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

  /** 选择音质档位：交给 AppState 持久化并在正在播放时无缝换流。 */
  function pickQuality(t: QualityTier) {
    if (!current) return;
    app.setPreferredQuality(t.quality);
    setQualityOpen(false);
    app.toast(`已切换到 ${t.label || labelOfQuality(t.quality)}（对当前歌曲立即生效）`);
  }

  /** 选择定时时长：立即生效并关闭弹窗。 */
  function pickSleepMinutes(min: number) {
    app.setSleepTimer(min);
    setSleepOpen(false);
    app.toast(`将在 ${min} 分钟后暂停播放`);
  }

  /** 取消定时：等待「本首播完再停」的状态也一并解除。 */
  function cancelSleep() {
    app.setSleepTimer(null);
    setSleepOpen(false);
    app.toast("已取消定时关闭");
  }

  /** 队列拖动开始：记下来源行下标并设置移动语义。 */
  function onQueueDragStart(i: number, e: React.DragEvent<HTMLDivElement>) {
    setDragFrom(i);
    e.dataTransfer.effectAllowed = "move";
    // 部分平台要求 dataTransfer 非空才发起拖拽，塞入来源下标作占位
    e.dataTransfer.setData("text/plain", String(i));
  }

  /** 队列拖动经过：允许放置并高亮落点行。 */
  function onQueueDragOver(i: number, e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    e.dataTransfer.dropEffect = "move";
    setDragOver((v) => (v === i ? v : i));
  }

  /** 队列拖动离开：只有真正离开该行（而非移入行内子元素）才取消高亮。 */
  function onQueueDragLeave(i: number, e: React.DragEvent<HTMLDivElement>) {
    const next = e.relatedTarget as Node | null;
    if (next && e.currentTarget.contains(next)) return;
    setDragOver((v) => (v === i ? null : v));
  }

  /** 队列放下：把来源行移动到落点行位置（播放跟随由 AppState 处理）。 */
  function onQueueDrop(i: number, e: React.DragEvent<HTMLDivElement>) {
    e.preventDefault();
    if (dragFrom !== null && dragFrom !== i) app.moveQueueItem(dragFrom, i);
    setDragFrom(null);
    setDragOver(null);
  }

  // 关闭态且不在退场动画中才卸载；退场期间保留 DOM 播淡出（pointer-events 已挡交互）
  if (!app.showDetail && !closing) return null;

  return (
    <div className={closing ? "pd-overlay pd-overlay-exit" : "pd-overlay"}>
      {/* 顶栏：收起 + 标题 + 定时关闭 */}
      <div className="pd-top">
        <button className="pd-collapse" title="收起" onClick={collapse}><IconChevronDown size={22} /></button>
        <div className="pd-top-title">正在播放</div>
        <div className="pd-top-right">
          <button
            className={`pd-sleep-btn${sleepOn ? " on" : ""}`}
            title={sleepTitle}
            onClick={() => setSleepOpen(true)}
          >
            <IconClock size={15} />
            {sleepLabel}
          </button>
        </div>
      </div>
      {current ? (
        <div className="pd-body">
          <div className="pd-main">
            {/* 视图切换：封面 / 歌词（无当前曲时禁用） */}
            <div className="pd-view-tabs">
              <button
                type="button"
                className={`pd-view-tab${lyricsTab === "cover" ? " on" : ""}`}
                disabled={!current}
                onClick={() => setLyricsTab("cover")}
              >
                封面
              </button>
              <button
                type="button"
                className={`pd-view-tab${lyricsTab === "lyrics" ? " on" : ""}`}
                disabled={!current}
                onClick={() => setLyricsTab("lyrics")}
              >
                歌词
              </button>
            </div>
            {/* 中部区域：封面大图 或 歌词面板（歌名/进度/控制留在外层不动） */}
            {lyricsTab === "lyrics" && current ? (
              <div className="pd-lyrics-wrap">
                <LyricsPane
                  song={current}
                  positionSec={app.position}
                  playing={app.isPlaying}
                  onSeek={(sec) => app.seek(sec)}
                />
              </div>
            ) : (
              <div className="pd-cover">
                <span className="pd-cover-note"><IconMusicNote size={80} /></span>
                {current.coverUrl ? <img src={absoluteUrl(current.coverUrl)} alt="" onError={hideOnError} /> : null}
              </div>
            )}
            <div className="pd-title-row">
              <div className="pd-song" title={current.title}>{current.title}</div>
              {!!current.vip && <span className="pd-vip">VIP</span>}
              <button
                className={`pd-heart${fav ? " on" : ""}`}
                title={fav ? "取消收藏" : "收藏"}
                onClick={() => app.toggleFavorite(current)}
              >
                <IconHeart size={22} filled={fav} />
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
                  {/* 高潮区间标记段：叠在轨道上的强调色圆头胶囊（对标安卓 PlayerProgress） */}
                  {refrain && (
                    <div
                      className="pd-track-refrain"
                      style={{ left: `${refrain.start * 100}%`, width: `${(refrain.end - refrain.start) * 100}%` }}
                    />
                  )}
                  <div className="pd-track-thumb" style={{ left: `${pct}%` }} />
                </div>
                <span className="pd-time">{duration > 0 ? formatTime(duration) : "--:--"}</span>
              </div>
              {/* 高潮区间小字：区间有效才显示，位置与进度条上的标记段对应 */}
              {refrain && (
                <div className="pd-refrain">
                  高潮 {formatTime(refrain.startMs / 1000)}–{formatTime(refrain.endMs / 1000)}
                </div>
              )}
              <div className="pd-quick">
                <button className="pd-quick-btn" disabled={sharing} onClick={() => { void startShare(); }}>
                  <IconShare size={15} />
                  {sharing ? "分享中…" : "分享"}
                </button>
                <button className="pd-quick-btn" onClick={() => setQualityOpen(true)}>
                  <IconQuality size={15} />
                  {app.activeQuality != null ? labelOfQuality(app.activeQuality) : "音质"}
                </button>
                <button className="pd-quick-btn" onClick={() => setShowQueue((v) => !v)}>
                  <IconQueue size={15} />
                  队列
                </button>
              </div>
              <div className="pd-transport">
                <button
                  className="pd-tbtn"
                  data-mode={app.repeat}
                  title={repeatTitle(app.repeat)}
                  onClick={() => app.cycleRepeat()}
                >
                  <IconRepeat size={20} />
                  {app.repeat === "one" && <span className="pd-repeat-badge">1</span>}
                </button>
                <button className="pd-tbtn" title="上一首" onClick={() => app.prev()}>
                  <IconPrev size={20} />
                </button>
                <button
                  className="pd-play"
                  title={app.isPlaying ? "暂停" : "播放"}
                  onClick={() => app.togglePlay()}
                >
                  {app.isPlaying ? <IconPause size={30} /> : <IconPlay size={30} />}
                </button>
                <button className="pd-tbtn" title="下一首" onClick={() => app.next()}>
                  <IconNext size={20} />
                </button>
              </div>
            </div>
          </div>

          {/* 播放队列面板（整行可拖动排序；当前曲不可删除） */}
          {showQueue && (
            <div className="pd-queue">
              <div className="pd-queue-head">
                <span className="pd-queue-title">播放队列</span>
                <span className="pd-queue-count">{app.queue.length} 首</span>
                {/* 面板自带关闭按钮：窄窗浮层盖住下方区域时也能直接收起 */}
                <button className="pd-queue-close" title="收起队列" onClick={() => setShowQueue(false)}>
                  <IconClose size={15} />
                </button>
              </div>
              <div className="pd-queue-list">
                {app.queue.length === 0 && <div className="pd-queue-empty">队列为空</div>}
                {app.queue.map((s, i) => {
                  const active = i === app.index;
                  const dragging = dragFrom === i;
                  const dropTarget = dragOver === i && dragFrom !== null && dragFrom !== i;
                  return (
                    <div
                      key={`${s.source}:${s.id}:${s.mid ?? ""}`}
                      className={`pd-queue-row${active ? " active" : ""}${dragging ? " dragging" : ""}${dropTarget ? " drop-target" : ""}`}
                      draggable
                      onClick={() => app.playAt(i)}
                      onDragStart={(e) => onQueueDragStart(i, e)}
                      onDragOver={(e) => onQueueDragOver(i, e)}
                      onDragLeave={(e) => onQueueDragLeave(i, e)}
                      onDrop={(e) => onQueueDrop(i, e)}
                      onDragEnd={() => {
                        setDragFrom(null);
                        setDragOver(null);
                      }}
                    >
                      <span className="pd-queue-grip" title="拖动调整顺序">⋮</span>
                      {/* 行封面：序号/播放中标记直接叠在封面角标上，替代原先裸序号列 */}
                      <span className={`pd-queue-cover${active ? " active" : ""}`}>
                        <span className="pd-queue-cover-note"><IconMusicNote size={16} /></span>
                        {s.coverUrl ? <img src={absoluteUrl(s.coverUrl)} alt="" onError={hideOnError} /> : null}
                        <span className="pd-queue-cover-badge">{active ? <IconPlay size={9} /> : i + 1}</span>
                      </span>
                      <span className="pd-queue-meta">
                        <span className="pd-queue-name">{s.title}</span>
                        <span className="pd-queue-artist">{s.artist}</span>
                      </span>
                      {!active && (
                        <button
                          className="pd-queue-del"
                          title="从队列移除"
                          onClick={(e) => {
                            e.stopPropagation();
                            app.removeQueueItem(i);
                          }}
                        >
                          <IconClose size={14} />
                        </button>
                      )}
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

      {/* 音质选择弹窗 */}
      {qualityOpen && current && (
        <div
          className="pd-modal-mask"
          onMouseDown={(e) => { if (e.target === e.currentTarget) setQualityOpen(false); }}
        >
          <div className="pd-modal">
            <div className="pd-modal-title">播放音质</div>
            <div className="pd-modal-song">{current.title} - {current.artist}</div>
            {tiers === null ? (
              <div className="pd-quality-loading">加载中…</div>
            ) : tierErr !== "" ? (
              <div className="pd-quality-error">
                <span>{tierErr}</span>
                <button className="pd-modal-btn" onClick={() => setTiersNonce((n) => n + 1)}>
                  重试
                </button>
              </div>
            ) : tiers.length === 0 ? (
              <div className="pd-quality-loading">暂无可用音质档位</div>
            ) : (
              <div className="pd-quality-list">
                {tiers.map((t) => {
                  const isActive = t.quality === app.activeQuality;
                  const isDefault = t.quality === app.preferredQuality && !isActive;
                  const size = formatBytes(t.size);
                  return (
                    <button
                      key={t.quality}
                      type="button"
                      className={`pd-quality-row${isActive ? " current" : ""}`}
                      onClick={() => pickQuality(t)}
                    >
                      <span className="pd-quality-name">{t.label || labelOfQuality(t.quality)}</span>
                      {isDefault && <span className="pd-quality-default">默认</span>}
                      {isActive && <span className="pd-quality-badge">当前</span>}
                      {size !== null && <span className="pd-quality-size">{size}</span>}
                    </button>
                  );
                })}
              </div>
            )}
            <div className="pd-quality-note">切换音质会按新档位重新取流，播放进度不丢失</div>
          </div>
        </div>
      )}

      {/* 定时关闭弹窗 */}
      {sleepOpen && (
        <div
          className="pd-modal-mask"
          onMouseDown={(e) => { if (e.target === e.currentTarget) setSleepOpen(false); }}
        >
          <div className="pd-modal">
            <div className="pd-modal-title">定时关闭</div>
            {app.sleepPendingStop && (
              <div className="pd-sleep-notice">倒计时已结束，将在本首播完后暂停</div>
            )}
            <div className="pd-sleep-list">
              {SLEEP_MINUTES.map((min) => {
                const selected = sleepRemainMin === min;
                return (
                  <button
                    key={min}
                    type="button"
                    className={`pd-sleep-row${selected ? " selected" : ""}`}
                    onClick={() => pickSleepMinutes(min)}
                  >
                    <span>{min} 分钟</span>
                    {selected && <span className="pd-sleep-check">✓</span>}
                  </button>
                );
              })}
            </div>
            <label className="pd-sleep-wait">
              <input
                type="checkbox"
                checked={app.sleepWaitForSongEnd}
                onChange={() => app.toggleSleepWaitForSongEnd()}
              />
              播完整首歌再停止播放
            </label>
            <button
              type="button"
              className="pd-modal-btn pd-sleep-cancel"
              disabled={app.sleepRemainingSec == null && !app.sleepPendingStop}
              onClick={cancelSleep}
            >
              取消定时
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
