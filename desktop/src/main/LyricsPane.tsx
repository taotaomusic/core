import { memo, useCallback, useEffect, useRef, useState, type MutableRefObject } from "react";
import { fetchLyrics, songKeyOf, type Song } from "../api";
import {
  lyricIndexAt,
  parseLyric,
  wordProgress,
  type Lyric,
  type LyricLine,
  type LyricWord,
} from "./lyrics";
import "./lyrics.css";

/** 用户滚轮操作后暂停自动滚动的时长（毫秒）。 */
const SCROLL_PAUSE_MS = 2500;

/**
 * 播放详情页的歌词面板：随歌曲自动拉取歌词并做卡拉OK式高亮。
 * - 逐字歌词（yrc，带字级时间）：当前行拆成单字 span，按字级时间做双色渐变
 *   （珊瑚 = 已唱、灰 = 未唱），播放位置用「锚点插值 + rAF」平滑到帧级；
 * - 行级同步歌词（lrc）：当前行整行珊瑚色加粗、字号略大；
 * - 纯文本（未同步）歌词：整列灰白展示，不高亮、不可点击；
 * - 上述同步歌词点击任意行 seek 到该行起始；当前行变化时平滑滚动到容器垂直居中，
 *   用户滚轮后 2.5 秒内暂停自动滚动，之后恢复；加载中显示「歌词加载中…」，
 *   拉取失败或歌词为空显示「暂无歌词」。
 *
 * 渲染隔离（对标安卓 LyricPanel 只让 CurrentKaraokeLyricRow 读帧级位置的口径）：
 * props.positionSec 来自 timeupdate（约 4Hz），逐字动画需要 60fps。面板本体只在
 * 「当前行下标」变化时重渲染（setState 有 guard）；帧级位置放在 estimateRef 里，
 * 由活动行的 KaraokeLine 子组件自持一个小 rAF 读取 —— 整棵树里只有它每帧重渲染。
 */
export function LyricsPane(props: {
  song: Song;
  positionSec: number;
  playing: boolean;
  onSeek: (sec: number) => void;
}): JSX.Element {
  const { song, positionSec, playing } = props;
  // lyric 为 null 表示加载中；请求失败与无歌词统一用空 lines 表示
  const [lyric, setLyric] = useState<Lyric | null>(null);
  // 当前行下标：-1 表示前奏未开始；未同步歌词恒为 -1
  const [activeIndex, setActiveIndex] = useState(-1);
  const listRef = useRef<HTMLDivElement | null>(null);
  const lineRefs = useRef<Array<HTMLDivElement | null>>([]);
  // 请求代数：换歌后旧请求的返回一律作废，防止歌词串到别的歌上
  const reqGenRef = useRef(0);
  // 用户手动滚轮后的暂停标记 + 对应计时器句柄
  const scrollPausedRef = useRef(false);
  const pauseTimerRef = useRef<number | null>(null);
  // 当前行下标的镜像，供暂停结束的计时器回调读取最新值
  const activeIndexRef = useRef(-1);

  // —— 平滑时钟：锚点插值 ——
  // 锚点 = 最近一次可信的进度样本 { anchorMs, at: 锚定时刻 }。rAF 每帧估算
  //   estimateMs = anchorMs + (playing ? now - at : 0)
  // positionSec 每次更新（约 4Hz）或 playing 翻转都重锚定：估算误差最多积累一个
  // timeupdate 周期就被真实进度校正（漂移自愈），不会持续累积。
  // 暂停 → 恢复：即使浏览器不发新的 timeupdate，playing 翻转本身也会触发重锚定，
  // 估算从最后已知位置继续，不会把暂停时长算进歌曲进度；暂停期间冻结不动。
  const anchorRef = useRef({ anchorMs: 0, at: 0 });
  // 最新帧级估算位置；KaraokeLine 每帧从这里读，父组件重渲染不依赖它
  const estimateRef = useRef(0);
  const playingRef = useRef(playing);
  playingRef.current = playing;
  // onSeek 的 latest 镜像：调用方每次渲染可能传新的箭头函数（PlayerDetail 就是），
  // memo 化的行列表用稳定回调转发，避免因回调身份变化每 250ms 整列表重渲染
  const onSeekRef = useRef(props.onSeek);
  onSeekRef.current = props.onSeek;

  const songKey = songKeyOf(song);

  // 每次 positionSec / playing 变化都重锚定；顺带把 estimateRef 校正到真实进度，
  // 保证 rAF 循环启动前的第一次读取（以及暂停期间的读取）也是准的。
  useEffect(() => {
    anchorRef.current = { anchorMs: positionSec * 1000, at: performance.now() };
    estimateRef.current = positionSec * 1000;
  }, [positionSec, playing]);

  // 歌曲变化（以 songKeyOf 为依赖）时重新拉取歌词；竞态用请求代数 + alive 双重保护
  useEffect(() => {
    const gen = ++reqGenRef.current;
    setLyric(null);
    setActiveIndex(-1);
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

  // 平滑时钟主循环：每帧估算播放位置、探测当前行。只在同步歌词下运行；
  // setState 带下标守卫，行下标不变时 React 直接跳过 —— 行列表只在换句时重渲染，
  // 逐字进度则完全由 KaraokeLine 内部消化（它每帧读 estimateRef 自行刷新）。
  useEffect(() => {
    if (lyric === null || !lyric.synced) return;
    let rafId = 0;
    let lastIndex = -2; // 与任何合法下标（含 -1）都不同的初值，保证首帧必探测一次
    const tick = () => {
      const anchor = anchorRef.current;
      const estimateMs = anchor.anchorMs + (playingRef.current ? performance.now() - anchor.at : 0);
      estimateRef.current = estimateMs;
      const index = lyricIndexAt(lyric.lines, estimateMs);
      if (index !== lastIndex) {
        lastIndex = index;
        setActiveIndex(index);
      }
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [lyric]);

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

  /** 点击行 → seek 到该行起始；走 ref 转发保持回调身份稳定。 */
  const seekToLine = useCallback((line: LyricLine) => {
    onSeekRef.current(line.timeMs / 1000);
  }, []);

  /** 把行 DOM 节点登记进 lineRefs（自动滚动定位用）；身份稳定，供 memo 列表使用。 */
  const registerLine = useCallback((index: number, el: HTMLDivElement | null) => {
    lineRefs.current[index] = el;
  }, []);

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

  // 同步歌词：当前行高亮（带字级时间时逐字卡拉OK），点击任意行跳转到该行起始时间
  return (
    <div className="lyr-root">
      <div className="lyr-list" ref={listRef} onWheel={handleWheel}>
        <LineList
          lines={lyric.lines}
          activeIndex={activeIndex}
          registerLine={registerLine}
          onSeekLine={seekToLine}
          clockRef={estimateRef}
        />
      </div>
    </div>
  );
}

/** 行列表的 props。除 activeIndex 外全部稳定（回调走 ref 转发），memo 才能真正挡住重渲染。 */
type LineListProps = {
  lines: LyricLine[];
  activeIndex: number;
  registerLine: (index: number, el: HTMLDivElement | null) => void;
  onSeekLine: (line: LyricLine) => void;
  /** 帧级播放位置（毫秒），由父级平滑时钟循环持续刷新 */
  clockRef: MutableRefObject<number>;
};

/**
 * 行列表。props.positionSec 以约 4Hz 变化会让父组件频繁重渲染，
 * 但本组件除 activeIndex 外的 props 都稳定，memo 后只有换句时才真正重渲染，
 * 普通行完全不参与帧级更新；活动行带字级时间时交给 KaraokeLine 逐字渲染。
 */
const LineList = memo(function LineList(props: LineListProps): JSX.Element {
  const { lines, activeIndex, registerLine, onSeekLine, clockRef } = props;
  return (
    <>
      {lines.map((line, i) => {
        const active = i === activeIndex;
        const karaoke = active && line.words.length > 0;
        return (
          <div
            key={`${line.timeMs}-${i}`}
            ref={(el) => registerLine(i, el)}
            className={`lyr-line${active ? " active" : ""}`}
            onClick={() => onSeekLine(line)}
          >
            {karaoke ? <KaraokeLine words={line.words} clockRef={clockRef} /> : line.text}
          </div>
        );
      })}
    </>
  );
});

/**
 * 活动行的逐字卡拉OK渲染：整棵歌词树里唯一每帧重渲染的组件。
 *
 * 内部自持一个小 rAF，每帧从 clockRef 读取父级估算的播放位置并更新本地 state；
 * 暂停时位置冻结、值不变，React 对相同 state 自动跳过渲染。
 * 渐变口径对标安卓 KaraokeLine + brushFor：每个字独立成节点、独立渐变，
 * 长音的字平滑推进而不是整字跳变；折行只发生在字与字之间，
 * 不会出现整行共用一个水平分割点导致第二行被错误点亮的问题。
 */
function KaraokeLine(props: { words: LyricWord[]; clockRef: MutableRefObject<number> }): JSX.Element {
  const { words, clockRef } = props;
  const [estimateMs, setEstimateMs] = useState(() => clockRef.current);

  useEffect(() => {
    let rafId = 0;
    const tick = () => {
      setEstimateMs(clockRef.current);
      rafId = requestAnimationFrame(tick);
    };
    rafId = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(rafId);
  }, [clockRef]);

  return (
    <>
      {words.map((word, i) => {
        const progress = wordProgress(word, estimateMs);
        if (progress <= 0) {
          // 未唱：整字灰（.lyr-word 基类的默认色）
          return (
            <span key={i} className="lyr-word">
              {word.text}
            </span>
          );
        }
        if (progress >= 1) {
          // 已唱完：整字珊瑚
          return (
            <span key={i} className="lyr-word lyr-word-sung">
              {word.text}
            </span>
          );
        }
        // 正在唱：双色硬边渐变 + background-clip:text，分界点随进度逐帧推进；
        // 两端纯色单独短路，避免贴边比例算出退化渐变（对应安卓 brushFor 的 EdgeEpsilon 处理）
        const edge = (progress * 100).toFixed(2);
        return (
          <span
            key={i}
            className="lyr-word"
            style={{
              backgroundImage: `linear-gradient(90deg, var(--coral) ${edge}%, var(--lyr-unsung) ${edge}%)`,
              WebkitBackgroundClip: "text",
              backgroundClip: "text",
              color: "transparent",
            }}
          >
            {word.text}
          </span>
        );
      })}
    </>
  );
}
