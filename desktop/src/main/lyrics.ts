/**
 * LRC / YRC 歌词解析（纯函数，无 React 依赖）。
 *
 * 语义移植自 shared 的 LyricParser（com/taotao/music/model/Lyric.kt）：
 * yrc 的字级时间组保留为 LyricWord，供 LyricsPane 做逐字卡拉OK渐变；
 * lrc 没有字级时间，行文本取该行所有时间戳间隙文本拼接，words 恒为空。
 */

/** 一个逐字单元：text 可能是一个汉字，也可能是一个英文单词。 */
export type LyricWord = { text: string; startMs: number; endMs: number };

/** 一行歌词。words 为空表示没有字级时间轴，界面只能整行高亮。 */
export type LyricLine = { timeMs: number; text: string; words: LyricWord[] };
export type Lyric = { lines: LyricLine[]; synced: boolean };

/** 空歌词：lines 为空时界面显示「暂无歌词」。 */
const EMPTY_LYRIC: Lyric = { lines: [], synced: false };

/** `[mm:ss.xx]`、`[mm:ss.xxx]`、`[mm:ss]`，也兼容用冒号分隔毫秒的 `[mm:ss:xx]`。 */
const TIMESTAMP = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;

/** `[ti:标题]` 这类元信息标签，纯文本回退时要去掉。 */
const METADATA = /\[[a-zA-Z]+:[^\]]*\]/g;

/** `[offset:-500]` 表示整体提前 500 毫秒，正数为延后；对行时间统一平移。 */
const OFFSET = /\[offset:\s*([+-]?\d+)\s*\]/i;

/** YRC 行头：`[行起始ms,行时长ms]`，行起始时间取第一个时间组的第一个数字。 */
const YRC_HEADER = /^\[(\d+),(\d+)\]/;

/**
 * YRC 的字级时间组：`(起始ms,时长ms)`，尾部可能还带一个用途不明的数字。
 *
 * 只匹配时间组本身，文本取相邻两个时间组之间的内容 —— 不能用
 * 「文本 + 时间组」的写法去捕获文本：文本捕获组必须排除 `(` 才不会吞掉
 * 时间组的左括号，于是歌词里字面的 `(` 永远匹配不上而被丢掉。
 */
const YRC_TIMING = /\((\d+),(\d+)(?:,\d+)?\)/g;

/**
 * 解析歌词：yrc 非空且解析出至少一行就优先用它（自带行时间轴与字级时间），
 * 否则退回 lrc；两者都解析不出时返回空歌词（synced=false）。
 */
export function parseLyric(lrc: string, yrc: string): Lyric {
  const fromYrc = parseYrc(yrc);
  if (fromYrc.lines.length > 0) return fromYrc;
  return parseLrc(lrc);
}

/**
 * 解析 YRC 逐字歌词：行起始时间 = 行头第一个时间组 + offset；
 * 行文本 = 所有时间组间隙文本拼接（字面括号因此天然保留）；
 * 每个时间组生成一个 LyricWord（起始夹 0，结束 = 起始 + 时长）。
 */
function parseYrc(raw: string): Lyric {
  if (!raw || raw.trim() === "") return EMPTY_LYRIC;
  // yrc 同样可能带 offset 标签，行时间和字时间都要跟着平移。
  const offsetMs = offsetOf(raw);
  const lines: LyricLine[] = [];
  for (const rawLine of raw.split(/\r\n|\r|\n/)) {
    const header = YRC_HEADER.exec(rawLine);
    if (!header) continue;
    const body = rawLine.slice(header[0].length);
    // 每个时间组前面的那段文字就是它对应的字；间隙为空的组没有可唱的内容，跳过
    // （与安卓 wordsOf 口径一致，拼接出的行文本与旧版「间隙拼接」完全相同）。
    const words: LyricWord[] = [];
    let text = "";
    let cursor = 0;
    for (const timing of body.matchAll(YRC_TIMING)) {
      const start = timing.index ?? 0;
      const gap = body.slice(cursor, start);
      cursor = start + timing[0].length;
      text += gap;
      if (gap === "") continue;
      // 字级起始时间同样吃 offset，并夹到不早于 0；结束时间 = 起始 + 时长。
      const wordStartMs = Math.max(0, Number(timing[1]) + offsetMs);
      words.push({ text: gap, startMs: wordStartMs, endMs: wordStartMs + Number(timing[2]) });
    }
    text = text.trim();
    // 只有时间没有文字的行是间奏标记，丢弃。
    if (text === "") continue;
    lines.push({ timeMs: Math.max(0, Number(header[1]) + offsetMs), text, words });
  }
  if (lines.length === 0) return EMPTY_LYRIC;
  lines.sort((a, b) => a.timeMs - b.timeMs);
  return { lines, synced: true };
}

/** 解析 LRC 行级歌词：支持一行多时间戳、offset 平移、纯文本回退；没有字级时间，words 恒为空。 */
function parseLrc(raw: string): Lyric {
  if (!raw || raw.trim() === "") return EMPTY_LYRIC;
  const offsetMs = offsetOf(raw);
  const lines: LyricLine[] = [];
  let sawTimestamp = false;

  for (const rawLine of raw.split(/\r\n|\r|\n/)) {
    const stamps = Array.from(rawLine.matchAll(TIMESTAMP));
    if (stamps.length === 0) continue;
    sawTimestamp = true;
    // 文本 = 去掉行内所有时间戳后的剩余内容。
    const text = rawLine.replace(TIMESTAMP, "").trim();
    // 只有时间没有文字的行是间奏标记，保留会在界面上留出空白行。
    if (text === "") continue;
    // 一行可以挂多个时间戳（副歌复用），每个时间戳都生成一行。
    for (const stamp of stamps) {
      lines.push({ timeMs: timeMsOf(stamp, offsetMs), text, words: [] });
    }
  }

  if (!sawTimestamp) {
    // 纯文本歌词：去掉元信息标签后按行保留原序，不做时间同步。
    const plain = raw
      .split(/\r\n|\r|\n/)
      .map((line) => line.replace(METADATA, "").trim())
      .filter((line) => line !== "")
      .map((line) => ({ timeMs: 0, text: line, words: [] as LyricWord[] }));
    return { lines: plain, synced: false };
  }
  lines.sort((a, b) => a.timeMs - b.timeMs);
  return { lines, synced: true };
}

/** 全文查找 [offset:...] 标签；没有或解析不出按 0 处理。 */
function offsetOf(raw: string): number {
  const m = OFFSET.exec(raw);
  return m ? Number(m[1]) : 0;
}

/**
 * 时间戳组换算毫秒：小数位数决定单位 ——
 * 一位是十分之一秒，两位是百分之一秒，三位（及以上取前三位）是毫秒。
 */
function timeMsOf(stamp: RegExpMatchArray, offsetMs: number): number {
  const minutes = Number(stamp[1]) || 0;
  const seconds = Number(stamp[2]) || 0;
  const fraction = stamp[3] ?? "";
  const fractionMs =
    fraction.length === 0
      ? 0
      : fraction.length === 1
        ? Number(fraction) * 100
        : fraction.length === 2
          ? Number(fraction) * 10
          : Number(fraction.slice(0, 3));
  return Math.max(0, minutes * 60_000 + seconds * 1_000 + fractionMs + offsetMs);
}

/**
 * 单个字已唱到的比例（0..1），用于逐字渐变的分界点。
 * 长音的字靠这个比例平滑推进，而不是整字跳变。
 * startMs >= endMs（异常时长，含时长为 0）时不做除法：唱到即算完成。
 */
export function wordProgress(w: LyricWord, positionMs: number): number {
  if (w.startMs >= w.endMs) return positionMs >= w.startMs ? 1 : 0;
  return Math.min(1, Math.max(0, (positionMs - w.startMs) / (w.endMs - w.startMs)));
}

/**
 * 二分查找当前播放进度落在哪一行：返回满足 timeMs <= positionMs 的最大下标；
 * 进度早于第一行或没有行时返回 -1。行需已按 timeMs 升序（parseLyric 的产物保证如此）。
 */
export function lyricIndexAt(lines: LyricLine[], positionMs: number): number {
  let low = 0;
  let high = lines.length - 1;
  let result = -1;
  while (low <= high) {
    const middle = Math.floor((low + high) / 2);
    if (lines[middle].timeMs <= positionMs) {
      result = middle;
      low = middle + 1;
    } else {
      high = middle - 1;
    }
  }
  return result;
}
