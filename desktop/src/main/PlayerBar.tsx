import type { RefObject } from "react";
import type { Song } from "../api";
import { hideOnError } from "./img";

/** 底部播放条：封面/曲名 + 上一首/下一首 + 原生音频控件；播完自动下一首。 */
export function PlayerBar({
  current,
  audioRef,
  hasPrev,
  hasNext,
  onPrev,
  onNext,
}: {
  current: Song | null;
  audioRef: RefObject<HTMLAudioElement>;
  hasPrev: boolean;
  hasNext: boolean;
  onPrev: () => void;
  onNext: () => void;
}) {
  return (
    <div className="player">
      <img className="cover" src={current?.coverUrl || ""} alt="" onError={hideOnError} />
      <div className="meta">
        <div className="name">{current?.title || "未在播放"}</div>
        <div className="artist">{current?.artist || ""}</div>
      </div>
      <button className="pbtn" disabled={!hasPrev} onClick={onPrev} title="上一首">⏮</button>
      <button className="pbtn" disabled={!hasNext} onClick={onNext} title="下一首">⏭</button>
      <audio ref={audioRef} controls autoPlay onEnded={() => hasNext && onNext()} />
    </div>
  );
}
