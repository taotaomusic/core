import { useEffect, useRef, useState } from "react";
import { fetchLyrics, songKeyOf, type Song } from "../api";
import { lyricIndexAt, parseLyric, type Lyric } from "./lyrics";
import "./lyrics.css";

/** 用户滚轮操作后暂停自动滚动的时长（毫秒）。 */
const SCROLL_PAUSE_MS = 2500;

/**
 * 播放详情页的歌词面板：随歌曲自动拉取歌词并做行级高亮。
 * - 同步歌词：当前行珊瑚色加粗、字号略大，点击任意行 seek 到该行起始；
 *   当前行变化时平滑滚动到容器垂直居中，用户滚轮后 2.5 秒内暂停自动滚动，之后恢复。
 * - 纯文本（未同步）歌词：整列灰白展示，不高亮、不可点击。
 * - 加载中显示「歌词加载中…」；拉取失败或歌词为空显示「暂无歌词」。
 */
export function LyricsPane(props: {
  song: Song;
  positionSec: number;
  onSeek: (sec: number) => void;
}): JSX.Element {
  const { song, positionSec, onSeek } = props;
  // lyric 为 null 表示加载中；请求失败与无歌词统一用空 lines 表示
  const [lyric, setLyric] = useState<Lyric | null>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
  const lineRefs = useRef<Array<HTMLDivElement | null>>([]);
  // 请求代数：换歌后旧请求的返回一律作废，防止歌词串到别的歌上
  const reqGenRef = useRef(0);
  // 用户手动滚轮后的暂停标记 + 对应计时器句柄
  const scrollPausedRef = useRef(false);
  const pauseTimerRef = useRef<number | null>(null);
  // 当前行下标的镜像，供暂停结束的计时器回调读取最新值
  const activeIndexRef = useRef(-1);

  const songKey = songKeyOf(song);

  // 歌曲变化（以 songKeyOf 为依赖）时重新拉取歌词；竞态用请求代数 + alive 双重保护
  useEffect(() => {
    const gen = ++reqGenRef.current;
    setLyric(null);
    // 换歌后恢复自动滚动
    scrollPausedRef.current = false;
    if (pauseTimerRef.current !== null) {
      window.clearTimeout(pauseTimerRef.current);
      pauseTimerRef.current = null;
    }
    let alive = true;
    fetchLyrics(song)
      .then(({ lrc, yrc }) => {
        if (!alive || reqGenRef.current !== gen) return;
        setLyric(parseLyric(lrc, yrc));
      })
      .catch(() => {
        // 网络失败等一律按「暂无歌词」处理，不细分错误类型
        if (!alive || reqGenRef.current !== gen) return;
        setLyric({ lines: [], synced: false });
      });
    return () => {
      alive = false;
    };
    // song 是 songKey 对应的那次渲染的对象；key 不变就不重复拉取
  }, [songKey]);

  // 当前行下标：-1 表示前奏未开始；未同步歌词恒为 -1
  const activeIndex = lyric !== null && lyric.synced ? lyricIndexAt(lyric.lines, positionSec * 1000) : -1;

  /** 把指定行平滑滚动到滚动容器的垂直居中位置。 */
  function scrollToActive(index: number) {
    if (index < 0) return;
    const container = listRef.current;
    const line = lineRefs.current[index];
    if (!container || !line) return;
    const lineRect = line.getBoundingClientRect();
    const boxRect = container.getBoundingClientRect();
    const top =
      container.scrollTop + lineRect.top - boxRect.top - boxRect.height / 2 + lineRect.height / 2;
    container.scrollTo({ top, behavior: "smooth" });
  }

  // 当前行变化（或歌词刚加载完成）时平滑滚动到容器垂直居中；用户刚滚过则跳过
  useEffect(() => {
    activeIndexRef.current = activeIndex;
    if (scrollPausedRef.current) return;
    scrollToActive(activeIndex);
    // lyric 入参是为了在歌词加载完成时也滚动一次（当前行可能没变）
  }, [activeIndex, lyric]);

  /** 用户滚轮：暂停自动滚动 2.5 秒（重复滚动顺延），到点后回到当前行并恢复。 */
  function handleWheel() {
    scrollPausedRef.current = true;
    if (pauseTimerRef.current !== null) window.clearTimeout(pauseTimerRef.current);
    pauseTimerRef.current = window.setTimeout(() => {
      pauseTimerRef.current = null;
      scrollPausedRef.current = false;
      scrollToActive(activeIndexRef.current);
    }, SCROLL_PAUSE_MS);
  }

  // 卸载时清掉暂停计时器
  useEffect(
    () => () => {
      if (pauseTimerRef.current !== null) window.clearTimeout(pauseTimerRef.current);
    },
    [],
  );

  // 加载中 / 无歌词（含拉取失败）：居中灰字提示
  if (lyric === null || lyric.lines.length === 0) {
    return (
      <div className="lyr-root">
        <div className="lyr-empty">{lyric === null ? "歌词加载中…" : "暂无歌词"}</div>
      </div>
    );
  }

  // 纯文本歌词：只分行展示，无高亮、不可点击 seek
  if (!lyric.synced) {
    return (
      <div className="lyr-root">
        <div className="lyr-list">
          {lyric.lines.map((line, i) => (
            <div key={i} className="lyr-plain-line">
              {line.text}
            </div>
          ))}
        </div>
      </div>
    );
  }

  // 同步歌词：当前行高亮，点击任意行跳转到该行起始时间
  return (
    <div className="lyr-root">
      <div className="lyr-list" ref={listRef} onWheel={handleWheel}>
        {lyric.lines.map((line, i) => (
          <div
            key={`${line.timeMs}-${i}`}
            ref={(el) => {
              lineRefs.current[i] = el;
            }}
            className={`lyr-line${i === activeIndex ? " active" : ""}`}
            onClick={() => onSeek(line.timeMs / 1000)}
          >
            {line.text}
          </div>
        ))}
      </div>
    </div>
  );
}
