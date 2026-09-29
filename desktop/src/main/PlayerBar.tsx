import { useState } from "react";
import { absoluteUrl } from "../api";
import { useApp } from "../state/AppState";
import { hideOnError } from "./img";
import {
  IconClose,
  IconHeart,
  IconMusicNote,
  IconNext,
  IconPause,
  IconPlay,
  IconPrev,
  IconQueue,
  IconRepeat,
} from "./icons";
import "./player.css";

/** 循环模式按钮的悬浮提示文案。 */
function repeatTitle(mode: "off" | "all" | "one"): string {
  if (mode === "one") return "循环：单曲循环";
  if (mode === "all") return "循环：列表循环";
  return "循环：关闭";
}

/**
 * 底部播放条：封面/歌名（点击打开详情页）+ 细进度条 + 传输/循环/收藏/队列控制。
 * 全部状态来自 useApp，自身零 props；无当前歌曲时整体置灰只显示「未在播放」。
 * 按钮统一使用共享线性图标集 icons.tsx，不再使用 unicode 字符。
 */
export function PlayerBar() {
  const app = useApp();
  const [dismissedError, setDismissedError] = useState("");
  const current = app.current;
  const empty = !current;
  // 细进度条为纯展示：duration 未知（0）时保持 0%
  const pct = app.duration > 0 ? Math.min(100, (app.position / app.duration) * 100) : 0;
  // 已关闭过的错误不再显示；出现新错误（文案不同）会再次浮出
  const showError = !!app.playError && app.playError !== dismissedError;

  return (
    <div className={`playerbar${empty ? " is-empty" : ""}`}>
      {showError && (
        <div className="playerbar-error" role="alert">
          <span className="playerbar-error-text">{app.playError}</span>
          <button
            className="playerbar-error-close"
            title="关闭"
            onClick={() => setDismissedError(app.playError)}
          >
            <IconClose size={14} />
          </button>
        </div>
      )}
      <div className="playerbar-cover">
        <span className="playerbar-cover-note"><IconMusicNote size={22} /></span>
        {current?.coverUrl ? <img src={absoluteUrl(current.coverUrl)} alt="" onError={hideOnError} /> : null}
      </div>
      <div
        className="playerbar-meta"
        title={empty ? undefined : "查看播放详情"}
        onClick={() => { if (!empty) app.setShowDetail(true); }}
      >
        <div className="playerbar-name">{current ? current.title : "未在播放"}</div>
        <div className="playerbar-artist">{current ? current.artist : ""}</div>
      </div>
      {current && (
        <div className="playerbar-progress">
          <div className="playerbar-progress-fill" style={{ width: `${pct}%` }} />
        </div>
      )}
      <div className="playerbar-controls">
        <button
          className="playerbar-btn playerbar-repeat"
          data-mode={app.repeat}
          title={repeatTitle(app.repeat)}
          disabled={empty}
          onClick={() => app.cycleRepeat()}
        >
          <IconRepeat size={16} />
          {app.repeat === "one" && <span className="playerbar-repeat-badge">1</span>}
        </button>
        <button className="playerbar-btn" title="上一首" disabled={empty} onClick={() => app.prev()}>
          <IconPrev size={16} />
        </button>
        <button
          className="playerbar-btn playerbar-play"
          title={app.isPlaying ? "暂停" : "播放"}
          disabled={empty}
          onClick={() => app.togglePlay()}
        >
          {app.isPlaying ? <IconPause size={20} /> : <IconPlay size={20} />}
        </button>
        <button className="playerbar-btn" title="下一首" disabled={empty} onClick={() => app.next()}>
          <IconNext size={16} />
        </button>
        <button
          className={`playerbar-btn playerbar-fav${current && app.isFavorite(current) ? " on" : ""}`}
          title="收藏"
          disabled={empty}
          onClick={() => { if (current) app.toggleFavorite(current); }}
        >
          <IconHeart size={16} filled={!!current && app.isFavorite(current)} />
        </button>
        {/* 打开详情页（详情页内自带队列面板，默认展开） */}
        <button className="playerbar-btn" title="播放队列" disabled={empty} onClick={() => app.setShowDetail(true)}>
          <IconQueue size={16} />
        </button>
      </div>
    </div>
  );
}
