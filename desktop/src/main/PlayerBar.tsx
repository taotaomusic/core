import { useRef, useState } from "react";
import { absoluteUrl, refrainRangeOf } from "../api";
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
  IconVolume,
} from "./icons";
import "./player.css";

/** 循环模式按钮的悬浮提示文案。 */
function repeatTitle(mode: "off" | "all" | "one"): string {
  if (mode === "one") return "循环：单曲循环";
  if (mode === "all") return "循环：列表循环";
  return "循环：关闭";
}

/**
 * 底部播放条：封面/歌名（点击打开详情页）+ 细进度条 + 传输/循环/收藏/音量/队列控制。
 * 全部状态来自 useApp，自身零 props；无当前歌曲时整体置灰只显示「未在播放」。
 * 按钮统一使用共享线性图标集 icons.tsx，不再使用 unicode 字符。
 */
export function PlayerBar() {
  const app = useApp();
  const [dismissedError, setDismissedError] = useState("");
  // 静音前的音量记忆：点喇叭图标切换静音时用它恢复，避免恢复到固定值
  const volumeBeforeMuteRef = useRef(1);
  const current = app.current;
  const empty = !current;
  // 细进度条为纯展示：duration 未知（0）时保持 0%
  const pct = app.duration > 0 ? Math.min(100, (app.position / app.duration) * 100) : 0;
  // 高潮区间标记：与进度条同用一个真实时长做分母，区间无效（缺值/时长未知）时不渲染
  const refrain = refrainRangeOf(current, app.duration);
  // 已关闭过的错误不再显示；出现新错误（文案不同）会再次浮出
  const showError = !!app.playError && app.playError !== dismissedError;
  // 音量滑杆弹出层：悬浮音量区域时展开
  const [volumeHover, setVolumeHover] = useState(false);

  /** 点喇叭按钮：静音与恢复之间切换。静音前记住原音量，取消静音时恢复它。 */
  function toggleMute() {
    if (app.volume > 0) {
      volumeBeforeMuteRef.current = app.volume;
      app.setVolume(0);
    } else {
      // 恢复音量过低时回退到 30%，避免恢复后仍然几乎无声
      app.setVolume(volumeBeforeMuteRef.current > 0.02 ? volumeBeforeMuteRef.current : 0.3);
    }
  }

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
          {/* 高潮区间标记段：叠在轨道上的强调色圆头胶囊，纯展示（对标安卓迷你条） */}
          {refrain && (
            <div
              className="playerbar-progress-refrain"
              style={{ left: `${refrain.start * 100}%`, width: `${(refrain.end - refrain.start) * 100}%` }}
            />
          )}
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
        {/* 音量：喇叭按钮点击在静音/恢复间切换，悬浮弹出滑杆微调；
            空态下仍可用（音量是设备侧属性，与是否在播无关），故不 disabled */}
        <div
          className="playerbar-volume"
          onMouseEnter={() => setVolumeHover(true)}
          onMouseLeave={() => setVolumeHover(false)}
        >
          <button
            className={`playerbar-btn${app.volume === 0 ? " playerbar-volume-muted" : ""}`}
            title={app.volume === 0 ? "取消静音" : "静音"}
            onClick={toggleMute}
          >
            <IconVolume size={16} muted={app.volume === 0} />
          </button>
          <div className={`playerbar-volume-pop${volumeHover ? " show" : ""}`}>
            <input
              className="playerbar-volume-slider"
              type="range"
              min={0}
              max={100}
              step={1}
              value={Math.round(app.volume * 100)}
              onChange={(e) => app.setVolume(Number(e.currentTarget.value) / 100)}
              aria-label="播放音量"
            />
            <span className="playerbar-volume-num">{Math.round(app.volume * 100)}</span>
          </div>
        </div>
        {/* 打开详情页（详情页内自带队列面板，默认展开） */}
        <button className="playerbar-btn" title="播放队列" disabled={empty} onClick={() => app.setShowDetail(true)}>
          <IconQueue size={16} />
        </button>
      </div>
    </div>
  );
}
