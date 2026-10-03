import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode, SyntheticEvent } from "react";
import {
  addFavorite,
  createPlaylist,
  favoriteKeyOf,
  fetchFavorites,
  fetchPlaylists,
  fetchSongInfos,
  fetchSongRefrain,
  labelOfQuality,
  readableError,
  removeFavorite,
  reportPlaybackSession,
  resolveLink,
  SessionExpired,
  songIdStringOf,
  songKeyOf,
  type PlaybackSessionReport,
  type PlaylistRecord,
  type Song,
} from "../api";

/** 重复播放模式：off 播完即停；all 列表循环；one 单曲循环。 */
export type RepeatMode = "off" | "all" | "one";

/** useApp 返回的上下文值：播放器 / 队列与插播 / 音质 / 收藏 / 歌单 / 定时关闭 / UI 轻提示。 */
type AppContextValue = {
  // ---- 播放器 ----
  queue: Song[];
  index: number;
  current: Song | null;
  isPlaying: boolean;
  position: number; // 秒
  duration: number; // 秒，未知为 0
  repeat: RepeatMode;
  playError: string;
  playList: (list: Song[], i: number) => void;
  playAt: (i: number) => void;
  togglePlay: () => void;
  next: () => void;
  prev: () => void;
  seek: (sec: number) => void;
  cycleRepeat: () => void;
  // ---- 队列操作（新增） ----
  removeQueueItem: (i: number) => void;
  moveQueueItem: (from: number, to: number) => void;
  // ---- 插播（新增，对标安卓 playNext，纯本地队列操作） ----
  playNext: (song: Song) => void;
  // ---- 音质（新增） ----
  preferredQuality: number;
  setPreferredQuality: (q: number) => void;
  activeQuality: number | null;
  reresolveCurrent: (quality: number) => Promise<void>;
  // ---- 定时关闭（新增） ----
  sleepRemainingSec: number | null;
  sleepWaitForSongEnd: boolean;
  sleepPendingStop: boolean;
  setSleepTimer: (minutes: number | null) => void;
  toggleSleepWaitForSongEnd: () => void;
  // ---- 收藏 ----
  favorites: Set<string>;
  favSongs: Song[];
  favReady: boolean;
  isFavorite: (s: Song) => boolean;
  toggleFavorite: (s: Song) => void;
  refreshFavorites: () => Promise<void>;
  // ---- 歌单（新增） ----
  playlists: PlaylistRecord[]; // 全量歌单列表（不含 songs）
  playlistsReady: boolean; // 首次加载完成
  refreshPlaylists: () => Promise<void>;
  createPlaylistAction: (name: string, description?: string) => Promise<PlaylistRecord | null>;
  // ---- UI ----
  showDetail: boolean;
  setShowDetail: (v: boolean) => void;
  toast: (msg: string) => void;
};

const AppContext = createContext<AppContextValue | null>(null);

/** 消费全局状态；必须在 <AppProvider> 内使用。 */
export function useApp(): AppContextValue {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp 必须在 <AppProvider> 内使用");
  return ctx;
}

// ---- 播放上报（对标安卓 trackPlaybackSession / PlaybackSyncStore，内部实现，不进 useApp 契约） ----

/** 上报用的设备 id 存储键。 */
const DEVICE_ID_KEY = "taotao.deviceId";

/** 播放上报 outbox 的 localStorage 键：先落盘、服务端确认后再删除。 */
const OUTBOX_KEY = "taotao.playbackOutbox";

/**
 * outbox 容量上限，超出丢最旧。正常路径 15 秒一报、服务端确认即删，
 * 200 条只可能在长期离线仍持续听歌的极端场景下填满 —— 纯粹是防止
 * localStorage 无限膨胀的兜底，不是正常流转的一部分。
 */
const OUTBOX_LIMIT = 200;

/** 位置增量的可信上限（毫秒）：超过视为 seek / 换源 / 重播复位，丢弃不计。 */
const MAX_POSITION_DELTA_MS = 2000;

/** 周期快照间隔（毫秒）：每累计听满 15 秒快照上报一次。 */
const PERIODIC_SYNC_MS = 15000;

/**
 * 一次播放会话的本地累计状态。会话生命周期规则（与安卓 shouldReuseSession 对齐）：
 * - 复用（歌没变、没结束过）：暂停/恢复（togglePlay 直操音频元素，不经 playAt）、
 *   seek、音质换源（reresolveCurrent）；
 * - 终结并另起新会话：换了歌（songKey 不同）、自然播完（ended，含单曲循环重播）、
 *   对正在播的同一首歌再次点播放（playAt 的语义是从头播放，即显式重播意图）。
 */
type PlaybackSessionState = {
  sessionId: string; // 每次新会话用 crypto.randomUUID() 生成
  songKey: string; // songKeyOf 口径的歌曲键，用于判断「同一首歌」
  source: string;
  songId: string; // songIdStringOf 口径的远端身份；空串的会话整体跳过不上报
  startedAt: number; // 会话开始的真实墙钟（毫秒）
  listenedMs: number; // 已听时长累计（毫秒），由真实播放位置增量累加
  lastAudioPos: number; // 上次记录的 el.currentTime（毫秒），求位置增量用
};

/** 读取或生成设备 id：localStorage "taotao.deviceId" 缺失时用 crypto.randomUUID 生成并落盘。 */
function ensureDeviceId(): string {
  try {
    const existing = localStorage.getItem(DEVICE_ID_KEY);
    if (existing) return existing;
    const id = crypto.randomUUID();
    localStorage.setItem(DEVICE_ID_KEY, id);
    return id;
  } catch {
    // localStorage 不可用时退化为进程内一次性 id，仅影响上报归属不影响播放
    return crypto.randomUUID();
  }
}

/**
 * 为歌曲开新会话。songId 为空串（既无 id 又无 mid）说明服务端无法定位这首歌，
 * 整个会话跳过不上报：返回 null 表示「无会话」，心跳与 flush 都直接忽略。
 * lastAudioPos 取调用时刻音频元素的真实位置，避免把会话开始前的位移误算进收听时长。
 */
function makePlaybackSession(song: Song, el?: HTMLAudioElement | null): PlaybackSessionState | null {
  const songId = songIdStringOf(song);
  if (!songId) return null;
  return {
    sessionId: crypto.randomUUID(),
    songKey: songKeyOf(song),
    source: song.source || "kuwo",
    songId,
    startedAt: Date.now(),
    listenedMs: 0,
    lastAudioPos: el ? Math.round(el.currentTime * 1000) : 0,
  };
}

// ---- outbox（对标安卓 PlaybackSyncStore）：先落盘、服务端确认后才删除 ----

/** 读取 outbox：解析失败或结构不对一律按空处理，不让坏数据卡死上报。 */
function readOutbox(): PlaybackSessionReport[] {
  try {
    const raw = localStorage.getItem(OUTBOX_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (it): it is PlaybackSessionReport => !!it && typeof (it as PlaybackSessionReport).sessionId === "string",
    );
  } catch {
    return [];
  }
}

/** 整体写回 outbox；localStorage 不可用（配额/隐私模式）时放弃持久化。 */
function writeOutbox(list: PlaybackSessionReport[]): void {
  try {
    localStorage.setItem(OUTBOX_KEY, JSON.stringify(list));
  } catch {
    // 写不进去就退化为一次性的直发快照，丢失只影响统计精度不影响播放
  }
}

/**
 * 待报快照入队：同一 sessionId 只保留最新一条快照（服务端按 sessionId 幂等 upsert，
 * 新快照覆盖旧的即可），并把新快照排到队尾视为最新；超出上限丢最旧。
 */
function enqueueOutbox(report: PlaybackSessionReport): void {
  const list = readOutbox().filter((it) => it.sessionId !== report.sessionId);
  list.push(report);
  writeOutbox(list.length > OUTBOX_LIMIT ? list.slice(list.length - OUTBOX_LIMIT) : list);
}

/**
 * 上传成功后的删除：只删除与上传内容完全一致的那条（安卓 outboxVersion
 * compare-and-remove 的简化版）。上传期间同 sessionId 若有更新的快照入队，
 * 内容必然变化（listenedMs / lastPlayedAt 单调前进），会被保留下来等下一轮，
 * 不会把未确认的新数据误删。
 */
function removeUploadedOutboxEntry(uploaded: PlaybackSessionReport): void {
  const uploadedJson = JSON.stringify(uploaded);
  writeOutbox(
    readOutbox().filter(
      (it) => !(it.sessionId === uploaded.sessionId && JSON.stringify(it) === uploadedJson),
    ),
  );
}

/**
 * 全局状态 Provider：渲染唯一的 <audio> 元素，承载播放队列、收藏库、音质偏好、
 * 定时关闭、轻提示与播放上报。播放/收藏/换源等会话过期统一经 props.onExpired 上抛给上层登出。
 */
export function AppProvider(props: { children: ReactNode; onExpired: () => void }): JSX.Element {
  const { children, onExpired } = props;

  // 始终指向最新的 onExpired，异步回调里经 ref 调用，避免闭包捕获旧回调
  const onExpiredRef = useRef(onExpired);
  onExpiredRef.current = onExpired;

  // ---- 播放器状态 ----
  const audioRef = useRef<HTMLAudioElement>(null);
  const [queue, setQueue] = useState<Song[]>([]);
  const [index, setIndex] = useState(-1);
  // queue/index 的镜像 ref：ended/next 等异步回调读取，避免闭包取到旧值
  const queueRef = useRef<Song[]>([]);
  const indexRef = useRef(-1);
  // 播放请求代数：快速切歌时只让最后一次 resolveLink 的结果生效
  const playGenRef = useRef(0);
  const [isPlaying, setIsPlaying] = useState(false);
  const [position, setPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [repeat, setRepeat] = useState<RepeatMode>("off");
  const repeatRef = useRef<RepeatMode>("off");
  const [playError, setPlayError] = useState("");

  // ---- 音质状态 ----
  // 默认播放音质档：localStorage "taotao.quality" 存数字字符串，仅接受 0-18 的整数档位，缺省 4
  const [preferredQuality, setPreferredQualityState] = useState<number>(() => {
    try {
      const raw = localStorage.getItem("taotao.quality");
      if (raw !== null) {
        const n = Number(raw);
        if (Number.isInteger(n) && n >= 0 && n <= 18) return n;
      }
    } catch {
      // localStorage 不可用时忽略，使用缺省档位
    }
    return 4;
  });
  // preferredQuality 的镜像 ref：playAt 等闭包里读取最新档位，避免取到过期状态
  const preferredQualityRef = useRef(preferredQuality);
  // 当前曲实际生效档位（resolveLink 返回的 quality）；切歌前为 null
  const [activeQuality, setActiveQuality] = useState<number | null>(null);

  // ---- 定时关闭状态 ----
  // 倒计时剩余秒数；null=未开启，开启期间每秒刷新
  const [sleepRemainingSec, setSleepRemainingSec] = useState<number | null>(null);
  // 「播完整首歌再停止播放」标记：localStorage "taotao.sleepWaitSongEnd" 存 "1"/"0"，持久语义由 UI 决定
  const [sleepWaitForSongEnd, setSleepWaitForSongEnd] = useState<boolean>(() => {
    try {
      return localStorage.getItem("taotao.sleepWaitSongEnd") === "1";
    } catch {
      return false;
    }
  });
  // sleepWaitForSongEnd 的镜像 ref：interval 回调闭包里读取最新标记
  const sleepWaitRef = useRef(sleepWaitForSongEnd);
  // 倒计时已到、正在等当前曲自然播完的等待态；跨手动切歌存活
  const [sleepPendingStop, setSleepPendingStop] = useState(false);
  // 定时关闭的秒级 interval 句柄与截止时间戳（按时间戳算剩余秒数，避免累计漂移）
  const sleepTimerRef = useRef<number | null>(null);
  const sleepDeadlineRef = useRef(0);

  // ---- 收藏状态 ----
  const [favorites, setFavorites] = useState<Set<string>>(new Set());
  const favoritesRef = useRef<Set<string>>(favorites);
  const [favSongs, setFavSongs] = useState<Song[]>([]);
  const favSongsRef = useRef<Song[]>(favSongs);
  const [favReady, setFavReady] = useState(false);

  // ---- 歌单状态 ----
  // 全量歌单列表（不含 songs）；歌单详情由歌单页自行拉取
  const [playlists, setPlaylists] = useState<PlaylistRecord[]>([]);
  // 首次拉取完成标记：歌单页据此区分「空列表」和「还没加载」
  const [playlistsReady, setPlaylistsReady] = useState(false);

  // ---- UI 状态 ----
  const [showDetail, setShowDetail] = useState(false);
  const [toastMsg, setToastMsg] = useState("");
  const toastTimerRef = useRef<number | null>(null);

  // ---- 播放上报（内部实现，不进 useApp 契约） ----
  // 设备 id：localStorage 缺失时生成一次并落盘，服务端用于区分播放来源
  const deviceIdRef = useRef(ensureDeviceId());
  // 当前播放会话；null = 无会话（未开播 / 刚终结 / 歌曲无远端身份不上报）
  const sessionRef = useRef<PlaybackSessionState | null>(null);
  // isPlaying 的镜像 ref：位置增量结算等闭包里读取真实播放态，避免取到过期 state
  const isPlayingRef = useRef(false);
  // outbox 在途标志：同一时刻只允许一个重试批在发送，防止并发重复上传同一批
  const outboxSyncingRef = useRef(false);

  const current = index >= 0 && index < queue.length ? queue[index] : null;

  const toast = useCallback((msg: string) => {
    setToastMsg(msg);
    if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    toastTimerRef.current = window.setTimeout(() => setToastMsg(""), 2600);
  }, []);

  /** 收藏状态与镜像 ref 一起更新，保证异步回调里读到的是最新值。 */
  function applyFavState(keys: Set<string>, songs: Song[]) {
    favoritesRef.current = keys;
    setFavorites(keys);
    favSongsRef.current = songs;
    setFavSongs(songs);
  }

  /**
   * outbox 重试批：把待报快照逐条发往服务端，成功一条删一条，直到发空或遇到首个失败。
   * 任何失败（网络 / 409 / 刷新令牌失败等）都静默保留条目等待下一轮 —— 后台同步语义，
   * 不提示、不打断播放，SessionExpired 也不据此登出（与安卓一致）。
   * 遇失败即整批中止：典型原因是网络不可达，余下条目大概率同样失败，留待下轮重试，
   * 避免离线期间每轮打满一整批注定失败的请求。
   */
  const syncOutbox = useCallback(async () => {
    if (outboxSyncingRef.current) return;
    outboxSyncingRef.current = true;
    try {
      for (;;) {
        const report = readOutbox()[0];
        if (!report) break;
        try {
          await reportPlaybackSession(report);
        } catch {
          // 静默：失败不提示、不影响播放，条目留在 outbox 等重试
          break;
        }
        removeUploadedOutboxEntry(report);
      }
    } finally {
      outboxSyncingRef.current = false;
    }
  }, []);

  /**
   * 快照式上报当前会话：先把快照同步写入 outbox（localStorage 落盘，进程被杀也能补传），
   * 再异步发送，服务端确认成功后才从 outbox 删除 —— 与安卓 PlaybackSyncStore 的
   * persistTerminalSnapshot → syncPendingPlayback 同构。
   * opts.completed=true 时按「完整听完」口径上报并终结会话（置空 ref），
   * 防止其后的暂停/切歌快照把同 sessionId 的 completed 覆盖回去。
   */
  const flushSession = useCallback((opts?: { completed?: boolean }) => {
    const session = sessionRef.current;
    if (opts?.completed) sessionRef.current = null;
    // 无会话或一秒都没听：没有可报的数据（服务端 3 秒阈值以下的也不进最近播放）
    if (!session || session.listenedMs <= 0) return;
    const report: PlaybackSessionReport = {
      sessionId: session.sessionId,
      deviceId: deviceIdRef.current,
      source: session.source,
      songId: session.songId,
      startedAt: session.startedAt,
      // 真实墙钟：快照落盘时刻（startedAt 也是创建时的 Date.now()）。
      // lastPlayedAt >= startedAt、两值不超 now+5min 等服务端校验天然满足
      lastPlayedAt: Date.now(),
      listenedMs: session.listenedMs,
    };
    if (opts?.completed) {
      report.completed = true;
      // durationSeconds 只在自然播完（ended）时带：取音频元素当前时长并取整
      const el = audioRef.current;
      const dur = el ? el.duration : NaN;
      report.durationSeconds = Number.isFinite(dur) ? Math.floor(dur) : null;
    }
    // 先落盘再发送：请求发不出去或进程随即被杀，下次挂载/重试也能补传
    enqueueOutbox(report);
    void syncOutbox();
  }, [syncOutbox]);

  /**
   * 位置增量结算：读音频元素的真实播放位置，与上次记录位置求差后计入会话。
   * 仅当会话在播（isPlaying）且 0 < delta <= 2000ms 时计入 listenedMs；
   * delta 为负或超过 2 秒视为 seek / 换源 / 重播复位，丢弃但更新记录位置。
   * 暂停与缓冲期间位置不前进，天然不累计。timeupdate 与 1 秒心跳共用本函数，
   * 重复调用只按真实位置差结算一次，不会重复计费。
   * 每跨过一个 15 秒整倍数边界触发一次周期快照（每个区间恰好触发一次）。
   */
  const accruePlayback = useCallback(() => {
    const session = sessionRef.current;
    const el = audioRef.current;
    if (!session || !el || !el.src) return;
    const posMs = Math.round(el.currentTime * 1000);
    const delta = posMs - session.lastAudioPos;
    session.lastAudioPos = posMs;
    if (!isPlayingRef.current || delta <= 0 || delta > MAX_POSITION_DELTA_MS) return;
    const before = session.listenedMs;
    session.listenedMs += delta;
    if (Math.floor(before / PERIODIC_SYNC_MS) < Math.floor(session.listenedMs / PERIODIC_SYNC_MS)) {
      flushSession();
    }
  }, [flushSession]);

  // 上报心跳：每秒结算一次真实位置增量（timeupdate 缺席或被节流时兜底），
  // 15 秒周期快照由 accruePlayback 内部的边界检测触发；不再有时钟盲加
  useEffect(() => {
    const timer = window.setInterval(() => {
      accruePlayback();
    }, 1000);
    // 组件卸载时清掉心跳 interval，避免定时器泄漏
    return () => window.clearInterval(timer);
  }, [accruePlayback]);

  // outbox 重试时机之三：挂载时立即补传上次遗留的待报快照，之后每 60 秒低频扫一次；
  // 时机之一（flush 落盘时顺带触发）在 flushSession 里，同一时刻只有一个在途批
  useEffect(() => {
    void syncOutbox();
    const timer = window.setInterval(() => {
      void syncOutbox();
    }, 60000);
    return () => window.clearInterval(timer);
  }, [syncOutbox]);

  // 页面卸载前把当前会话快照同步写入 outbox：localStorage 是同步写，卸载前必然落盘，
  // 发送交给下次挂载 / 定时重试补传（比原先 best-effort 的卸载期 fetch 可靠得多）
  useEffect(() => {
    const handleBeforeUnload = () => {
      try {
        flushSession();
      } catch {
        // 卸载路径吞掉一切异常
      }
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, [flushSession]);

  /**
   * 播放开始时后台兜底补拉当前曲的高潮区间并回写队列（对标安卓入队补齐，只补当前一首）：
   * 歌单/最近播放/收藏入队的歌来自服务端快照，快照不带 refrain 两个字段；搜索结果自带无需补。
   * 回写只改这首歌的 refrain 字段，不动其他状态；失败静默（下次播放同歌再试）。
   * 桌面端没有本地文件播放，安卓按 file: 地址排除本地文件的分支在这里天然不成立。
   */
  const ensureCurrentRefrain = useCallback((song: Song, i: number) => {
    // 无远端身份（既无 id 又无 mid）无法调 info 接口；区间已齐全也无需补
    if (!(song.id > 0) && !song.mid) return;
    if (song.refrainStartMs != null && song.refrainEndMs != null) return;
    const key = songKeyOf(song);
    void fetchSongRefrain(song)
      .then((refrain) => {
        // 回写前确认队列第 i 首仍是同一首歌：快速切歌/整列换队列后定位不到就放弃，
        // 不按远端身份猜测位置，避免把区间写到另一首同键歌曲上
        const list = queueRef.current;
        if (i < 0 || i >= list.length || songKeyOf(list[i]) !== key) return;
        // 服务端没给区间（如非酷我源）就不写，保持「键不存在」的原样
        if (refrain.refrainStartMs == null && refrain.refrainEndMs == null) return;
        const next = list.slice();
        next[i] = { ...next[i], refrainStartMs: refrain.refrainStartMs, refrainEndMs: refrain.refrainEndMs };
        queueRef.current = next;
        setQueue(next);
      })
      .catch(() => {
        // 静默：补拉失败不影响播放
      });
  }, []);

  const playAt = useCallback(
    async (i: number) => {
      const list = queueRef.current;
      if (i < 0 || i >= list.length) return;
      const song = list[i];
      // 播放开始即后台补拉当前曲缺失的高潮区间（不阻塞、不等待，与 resolveLink 并行）
      ensureCurrentRefrain(song, i);
      // 换歌/重播前先把旧会话快照落 outbox 并异步发送。此时 el.src 还没换、
      // 旧曲仍在播：若 resolveLink 失败，会话未终结，心跳会继续累计。
      flushSession();
      indexRef.current = i;
      setIndex(i);
      setPlayError("");
      // 切歌时实际生效档位先归空，resolveLink 成功后再写入
      setActiveQuality(null);
      // 切歌先复位进度，随后由音频元素事件写入真实值
      setPosition(0);
      setDuration(0);
      const gen = ++playGenRef.current;
      try {
        const link = await resolveLink(song, preferredQualityRef.current);
        if (gen !== playGenRef.current) return; // 已有更新的播放请求，丢弃过期结果
        setActiveQuality(link.quality);
        const el = audioRef.current;
        if (!el) return;
        el.src = link.url;
        await el.play();
        // 播放真正开始后才开会话：resolveLink 失败或被更新的请求取代时不动会话。
        // playAt 的语义是「从头播放这首歌」：即使同 key（对当前曲再次点播放）也是
        // 显式重播意图，旧会话终结、另起新会话（暂停/恢复走 togglePlay 不经这里）。
        // 开新会话前补一次快照，把旧会话在 resolveLink 期间多累计的部分带上。
        flushSession();
        sessionRef.current = makePlaybackSession(song, el);
      } catch (e) {
        if (gen !== playGenRef.current) return;
        if (e instanceof SessionExpired) {
          onExpiredRef.current();
          return;
        }
        // 快速切歌/暂停引发的中断不算播放错误
        if (e instanceof DOMException && e.name === "AbortError") return;
        const msg = readableError(e);
        setPlayError(msg);
        toast(msg);
      }
    },
    [toast, flushSession, ensureCurrentRefrain],
  );

  /** 整列设为队列并从第 i 首开始播放。 */
  const playList = useCallback(
    (list: Song[], i: number) => {
      queueRef.current = list;
      setQueue(list);
      void playAt(i);
    },
    [playAt],
  );

  // ---- 队列操作 ----

  /** 删除队列第 i 项：越界直接忽略；当前播放中的曲不可删，toast 提示后不动。 */
  const removeQueueItem = useCallback(
    (i: number) => {
      const list = queueRef.current;
      if (i < 0 || i >= list.length) return;
      if (i === indexRef.current) {
        toast("当前播放的歌曲不能移除");
        return;
      }
      const next = list.slice();
      next.splice(i, 1);
      queueRef.current = next;
      setQueue(next);
      // 删除点在播放中曲之前：current 的下标左移一位，保证仍指向同一首歌
      const oldIndex = indexRef.current;
      if (i < oldIndex) {
        indexRef.current = oldIndex - 1;
        setIndex(oldIndex - 1);
      }
    },
    [toast],
  );

  /**
   * 把队列第 from 项移动到 to：越界或相等直接忽略。
   * 播放中曲的下标跟随算法，保证 current 始终是同一首歌：
   * 移的就是当前曲（from===index）→ 下标跟随到 to；
   * from 在当前曲之前且插入点不低于当前曲（from<index && to>=index）→ 当前曲左移一位；
   * from 在当前曲之后且插入点不超过当前曲（from>index && to<=index）→ 当前曲右移一位。
   */
  const moveQueueItem = useCallback((from: number, to: number) => {
    const list = queueRef.current;
    if (from < 0 || from >= list.length) return;
    if (to < 0 || to >= list.length) return;
    if (from === to) return;
    const moved = list[from];
    const next = list.slice();
    next.splice(from, 1);
    next.splice(to, 0, moved);
    queueRef.current = next;
    setQueue(next);
    const cur = indexRef.current;
    let target = cur;
    if (from === cur) {
      target = to;
    } else if (from < cur && to >= cur) {
      target = cur - 1;
    } else if (from > cur && to <= cur) {
      target = cur + 1;
    }
    if (target !== cur) {
      indexRef.current = target;
      setIndex(target);
    }
  }, []);

  /**
   * 插播：对标安卓 playNext 的纯本地队列操作，不改变当前播放。
   * 队列为空或无当前曲 → 等价 playList([song], 0) 直接开播；否则插到当前曲
   * 后面（index+1），同 key 的旧位置先移除保持唯一（当前播放中的那首除外，
   * 与 removeQueueItem 的「播放中不可删」口径一致），插完轻提示。
   */
  const playNext = useCallback(
    (song: Song) => {
      const list = queueRef.current;
      const cur = indexRef.current;
      if (list.length === 0 || cur < 0 || cur >= list.length) {
        playList([song], 0);
        return;
      }
      const key = songKeyOf(song);
      const kept: Song[] = [];
      // 插入点默认在当前曲后；每移除一个位于当前曲之前的同 key 旧位置，插入点左移一位
      let insertAt = cur + 1;
      for (let i = 0; i < list.length; i++) {
        const it = list[i];
        if (i !== cur && songKeyOf(it) === key) {
          if (i < cur) insertAt -= 1;
          continue;
        }
        kept.push(it);
      }
      kept.splice(insertAt, 0, song);
      queueRef.current = kept;
      setQueue(kept);
      toast("已添加到下一首播放");
    },
    [playList, toast],
  );

  // next/prev 的回绕规则只认 all 循环：到尾/在 0 时其余模式保持不动
  const next = useCallback(() => {
    const list = queueRef.current;
    const i = indexRef.current;
    if (list.length === 0 || i < 0) return;
    if (i < list.length - 1) {
      void playAt(i + 1);
    } else if (repeatRef.current === "all") {
      void playAt(0);
    }
  }, [playAt]);

  const prev = useCallback(() => {
    const list = queueRef.current;
    const i = indexRef.current;
    if (list.length === 0 || i < 0) return;
    if (i > 0) {
      void playAt(i - 1);
    } else if (repeatRef.current === "all") {
      void playAt(list.length - 1);
    }
  }, [playAt]);

  const togglePlay = useCallback(() => {
    const el = audioRef.current;
    if (!el || !el.src) return;
    if (el.paused) {
      void el.play().catch(() => {});
    } else {
      el.pause();
    }
  }, []);

  const seek = useCallback((sec: number) => {
    const el = audioRef.current;
    if (!el || !el.src) return;
    el.currentTime = sec;
    setPosition(sec);
  }, []);

  /** off → all → one → off */
  const cycleRepeat = useCallback(() => {
    const order: RepeatMode[] = ["off", "all", "one"];
    const nextMode = order[(order.indexOf(repeatRef.current) + 1) % order.length];
    repeatRef.current = nextMode;
    setRepeat(nextMode);
  }, []);

  // ---- 音质 ----

  /**
   * 用传入档位重取当前曲直链并续播：换 src 后先恢复进度再 play，保持 position 不中断。
   * 无当前曲或无音源时只把传入档位落盘为偏好，不发声。
   * 同样计入播放请求代数：与 playAt 快速并发时只有最后一次请求的结果生效。
   */
  const reresolveCurrent = useCallback(
    async (quality: number) => {
      const list = queueRef.current;
      const i = indexRef.current;
      const song = i >= 0 && i < list.length ? list[i] : null;
      const el = audioRef.current;
      if (!song || !el || !el.src) {
        // 没有可续播的音源：只更新偏好档位（持久化 + state + ref），不动播放
        preferredQualityRef.current = quality;
        setPreferredQualityState(quality);
        try {
          localStorage.setItem("taotao.quality", String(quality));
        } catch {
          // localStorage 不可用时忽略
        }
        return;
      }
      // 换源前记下当前进度，新直链就绪后恢复，听感不中断
      const pos = el.currentTime;
      const gen = ++playGenRef.current;
      try {
        const link = await resolveLink(song, quality);
        if (gen !== playGenRef.current) return; // 已有更新的播放请求，丢弃过期结果
        setActiveQuality(link.quality);
        if (link.fallback) {
          // 服务端沿阶梯降级：实际档位低于请求时明确告知
          toast(`该档位不可用，已降级到 ${labelOfQuality(link.quality)}`);
        }
        // 换源续播：先换 src，再恢复进度，最后继续播放
        el.src = link.url;
        el.currentTime = pos;
        setPosition(pos);
        await el.play();
        // 音质换源仍是同一首歌：复用会话、累计不清零（暂停/恢复/seek/换源都不换会话）；
        // 仅当无会话（如刚播完被终结）或键不符时补开新会话。lastAudioPos 取恢复后的
        // 真实位置，换 src 引起的位置跳变不会误算进收听时长
        const key = songKeyOf(song);
        if (!sessionRef.current || sessionRef.current.songKey !== key) {
          sessionRef.current = makePlaybackSession(song, el);
        }
      } catch (e) {
        if (gen !== playGenRef.current) return;
        if (e instanceof SessionExpired) {
          onExpiredRef.current();
          return;
        }
        // 快速切歌引发的中断不算错误
        if (e instanceof DOMException && e.name === "AbortError") return;
        toast(readableError(e));
      }
    },
    [toast],
  );

  /** 设置默认音质档：持久化 + 更新 state；正在播放（有音源）时立即按新档位重取直链续播。 */
  const setPreferredQuality = useCallback(
    (q: number) => {
      preferredQualityRef.current = q;
      setPreferredQualityState(q);
      try {
        localStorage.setItem("taotao.quality", String(q));
      } catch {
        // localStorage 不可用时忽略
      }
      const el = audioRef.current;
      if (el && el.src) {
        void reresolveCurrent(q);
      }
    },
    [reresolveCurrent],
  );

  // ---- 定时关闭 ----

  /** 清掉定时关闭的秒级 interval：重复设置、取消、到期与组件卸载时都要调用。 */
  const clearSleepInterval = useCallback(() => {
    if (sleepTimerRef.current !== null) {
      window.clearInterval(sleepTimerRef.current);
      sleepTimerRef.current = null;
    }
  }, []);

  /** 倒计时到期动作：只执行一次（进入前 interval 已清理）。 */
  const handleSleepExpiry = useCallback(() => {
    const el = audioRef.current;
    if (sleepWaitRef.current && el && el.src && !el.paused) {
      // 开了「播完整首再停」且音频在播：转入等待态，等本曲自然播完时由 handleEnded 停止
      setSleepPendingStop(true);
      setSleepRemainingSec(null);
      toast("定时时间到，将在本首播完后暂停");
      return;
    }
    // 否则立即暂停（无音源时 pause 为空操作，仅给出提示）
    if (el && el.src) el.pause();
    setSleepRemainingSec(null);
    toast("定时关闭：已暂停播放");
  }, [toast]);

  /**
   * 设置定时关闭：minutes>0 启动倒计时（清掉旧 interval 与「播完再停」等待态）；
   * null 取消全部（含等待态）。interval 句柄与截止时间戳都存 ref，回调里按
   * 截止时间戳计算剩余秒数，不随定时器累计漂移。
   */
  const setSleepTimer = useCallback(
    (minutes: number | null) => {
      clearSleepInterval();
      if (minutes === null || minutes <= 0) {
        // 取消：倒计时与等待态一并清掉
        setSleepRemainingSec(null);
        setSleepPendingStop(false);
        return;
      }
      // 重新启动：旧的等待态作废
      setSleepPendingStop(false);
      const deadline = Date.now() + minutes * 60000;
      sleepDeadlineRef.current = deadline;
      setSleepRemainingSec(Math.max(0, Math.ceil((deadline - Date.now()) / 1000)));
      sleepTimerRef.current = window.setInterval(() => {
        const remain = Math.max(0, Math.ceil((sleepDeadlineRef.current - Date.now()) / 1000));
        setSleepRemainingSec(remain);
        if (remain <= 0) {
          // 到期只触发一次：先清 interval，再处理到期动作
          clearSleepInterval();
          handleSleepExpiry();
        }
      }, 1000);
    },
    [clearSleepInterval, handleSleepExpiry],
  );

  /** 切换「播完整首歌再停止播放」标记并写回 localStorage。 */
  const toggleSleepWaitForSongEnd = useCallback(() => {
    const next = !sleepWaitRef.current;
    sleepWaitRef.current = next;
    setSleepWaitForSongEnd(next);
    try {
      localStorage.setItem("taotao.sleepWaitSongEnd", next ? "1" : "0");
    } catch {
      // localStorage 不可用时忽略
    }
  }, []);

  const isFavorite = useCallback((s: Song) => favorites.has(favoriteKeyOf(s)), [favorites]);

  /** 收藏/取消收藏：先乐观更新本地，请求失败则回滚并轻提示。 */
  const toggleFavorite = useCallback(
    async (s: Song) => {
      // 收藏依赖远端身份：id>0 或 mid 非空，缺了就无法调用后端
      if (!(s.id > 0) && !s.mid) {
        toast("该歌曲缺少可收藏的远端身份");
        return;
      }
      const key = favoriteKeyOf(s);
      const songId = s.id > 0 ? String(s.id) : (s.mid as string);
      const wasFav = favoritesRef.current.has(key);
      // 操作前快照，失败时整体回滚
      const prevKeys = favoritesRef.current;
      const prevSongs = favSongsRef.current;
      if (wasFav) {
        const nextKeys = new Set(prevKeys);
        nextKeys.delete(key);
        applyFavState(nextKeys, prevSongs.filter((it) => favoriteKeyOf(it) !== key));
      } else {
        const nextKeys = new Set(prevKeys);
        nextKeys.add(key);
        // 插入收藏库头部；若同键已存在先去掉，避免重复
        const rest = prevSongs.filter((it) => favoriteKeyOf(it) !== key);
        applyFavState(nextKeys, [s, ...rest]);
      }
      try {
        if (wasFav) {
          await removeFavorite(s.source, songId);
        } else {
          await addFavorite(s.source, songId);
        }
      } catch (e) {
        applyFavState(prevKeys, prevSongs);
        if (e instanceof SessionExpired) {
          onExpiredRef.current();
          return;
        }
        toast(readableError(e));
      }
    },
    [toast],
  );

  /** 拉取收藏库：先以空标题占位，再按 source 分组批量补全元数据。 */
  const refreshFavorites = useCallback(async () => {
    try {
      const records = await fetchFavorites();
      // favorites Set 以 /favorites 返回为准；key 口径与 favoriteKeyOf 一致
      const keys = new Set(records.map((r) => `${r.source}:${r.songId}`));
      // songId 纯数字视为远端 id，否则本身就是 mid（酷我部分歌只有 mid）
      const placeholders: Song[] = records.map((r) =>
        /^\d+$/.test(r.songId)
          ? { id: Number(r.songId), title: "", artist: "", source: r.source }
          : { id: 0, title: "", artist: "", source: r.source, mid: r.songId },
      );
      const byKey = new Map<string, Song>();
      const groups = new Map<string, Song[]>();
      for (const p of placeholders) {
        const list = groups.get(p.source);
        if (list) list.push(p);
        else groups.set(p.source, [p]);
      }
      for (const [source, list] of groups) {
        const ids = list.filter((p) => p.id > 0).map((p) => String(p.id));
        const mids = list.filter((p) => p.mid).map((p) => p.mid as string);
        // batch-info 上限 60：ids+mids 合计超出则分批
        const merged: Array<{ kind: "id" | "mid"; value: string }> = [
          ...ids.map((value) => ({ kind: "id" as const, value })),
          ...mids.map((value) => ({ kind: "mid" as const, value })),
        ];
        for (let start = 0; start < merged.length; start += 60) {
          const batch = merged.slice(start, start + 60);
          const bIds = batch.filter((b) => b.kind === "id").map((b) => b.value);
          const bMids = batch.filter((b) => b.kind === "mid").map((b) => b.value);
          const infos = await fetchSongInfos(source, bIds, bMids);
          for (const info of infos) {
            // 两种身份都登记，兼容服务端按 id 或 mid 返回
            if (info.id > 0) byKey.set(`${source}:${info.id}`, info);
            if (info.mid) byKey.set(`${source}:${info.mid}`, info);
          }
        }
      }
      // 命中元数据的占位歌曲替换为完整信息，未命中的保留占位
      const filled = placeholders.map((p) => byKey.get(favoriteKeyOf(p)) ?? p);
      applyFavState(keys, filled);
    } catch (e) {
      if (e instanceof SessionExpired) {
        onExpiredRef.current();
        return;
      }
      // 拉取失败不阻塞界面，仅轻提示
      toast(readableError(e));
    } finally {
      setFavReady(true);
    }
  }, [toast]);

  /** 拉取全量歌单列表（不含 songs）：失败轻提示，完成后置 playlistsReady。 */
  const refreshPlaylists = useCallback(async () => {
    try {
      const records = await fetchPlaylists();
      setPlaylists(records);
    } catch (e) {
      if (e instanceof SessionExpired) {
        onExpiredRef.current();
        return;
      }
      // 拉取失败不阻塞界面，仅轻提示
      toast(readableError(e));
    } finally {
      setPlaylistsReady(true);
    }
  }, [toast]);

  /**
   * 新建歌单：成功后把记录插入本地列表头部（与服务端「按更新时间降序」的展示
   * 口径一致）并返回；失败轻提示并返回 null，SessionExpired 走 onExpired 登出。
   */
  const createPlaylistAction = useCallback(
    async (name: string, description?: string): Promise<PlaylistRecord | null> => {
      try {
        const record = await createPlaylist(name, description ?? "");
        setPlaylists((prev) => [record, ...prev]);
        return record;
      } catch (e) {
        if (e instanceof SessionExpired) {
          onExpiredRef.current();
          return null;
        }
        toast(readableError(e));
        return null;
      }
    },
    [toast],
  );

  // 挂载后拉取一次收藏库
  useEffect(() => {
    void refreshFavorites();
  }, [refreshFavorites]);

  // 挂载后拉取一次歌单列表
  useEffect(() => {
    void refreshPlaylists();
  }, [refreshPlaylists]);

  // 组件卸载时清掉定时关闭的 interval，避免定时器泄漏
  useEffect(() => {
    return () => {
      if (sleepTimerRef.current !== null) window.clearInterval(sleepTimerRef.current);
    };
  }, []);

  // ---- 音频元素事件：isPlaying/进度全部以元素事件为准 ----

  const handleTimeUpdate = (e: SyntheticEvent<HTMLAudioElement>) => {
    // 先结算真实位置增量（跨 15 秒边界时会触发周期快照），再更新界面进度
    accruePlayback();
    setPosition(e.currentTarget.currentTime);
  };

  const syncDuration = () => {
    const el = audioRef.current;
    if (!el) return;
    setDuration(Number.isFinite(el.duration) ? el.duration : 0);
  };

  // 播完：定时关闭「播完再停」等待态优先于 repeat（单曲循环也不续播）；
  // one 重播当前；all 到尾回 0 继续；off 最后一首停住，其余进入下一首
  const handleEnded = () => {
    // 自然播完：先按完成口径 flush（completed=true + durationSeconds 取整）并终结会话，
    // 防止其后的暂停/切歌快照把同 sessionId 的 completed 覆盖回去
    flushSession({ completed: true });
    // sleepPendingStop 跨手动切歌存活：playAt 不清除它，下一次自然播完时仍会在这里停住
    if (sleepPendingStop) {
      const el = audioRef.current;
      if (el) {
        el.pause();
        el.currentTime = 0;
      }
      setSleepPendingStop(false);
      toast("定时关闭：已暂停播放");
      return;
    }
    if (repeat === "one") {
      // 单曲循环重播：旧会话已终结，为重播单独开新会话（重播算一次独立播放）；
      // lastAudioPos 先取复位前的末尾位置，随后复位到 0 的负向跳变会被增量规则丢弃
      const replay = queueRef.current[indexRef.current];
      const el = audioRef.current;
      if (replay) sessionRef.current = makePlaybackSession(replay, el);
      if (el) {
        el.currentTime = 0;
        void el.play().catch(() => {});
      }
      return;
    }
    next();
  };

  // 音频元素自身加载失败（直链失效等）：只在确有音源时写入错误，避免空元素噪声
  const handleAudioError = () => {
    const el = audioRef.current;
    if (!el || !el.src) return;
    setPlayError("音频加载失败，请尝试重新播放");
  };

  const value: AppContextValue = {
    queue,
    index,
    current,
    isPlaying,
    position,
    duration,
    repeat,
    playError,
    playList,
    playAt,
    togglePlay,
    next,
    prev,
    seek,
    cycleRepeat,
    removeQueueItem,
    moveQueueItem,
    playNext,
    preferredQuality,
    setPreferredQuality,
    activeQuality,
    reresolveCurrent,
    sleepRemainingSec,
    sleepWaitForSongEnd,
    sleepPendingStop,
    setSleepTimer,
    toggleSleepWaitForSongEnd,
    favorites,
    favSongs,
    favReady,
    isFavorite,
    toggleFavorite,
    refreshFavorites,
    playlists,
    playlistsReady,
    refreshPlaylists,
    createPlaylistAction,
    showDetail,
    setShowDetail,
    toast,
  };

  return (
    <AppContext.Provider value={value}>
      {children}
      {toastMsg ? <div className="snackbar">{toastMsg}</div> : null}
      <audio
        ref={audioRef}
        hidden
        onTimeUpdate={handleTimeUpdate}
        onDurationChange={syncDuration}
        onLoadedMetadata={syncDuration}
        onPlay={() => {
          isPlayingRef.current = true;
          setIsPlaying(true);
        }}
        onPause={() => {
          isPlayingRef.current = false;
          setIsPlaying(false);
          // 暂停即快照上报一次（先落 outbox 再异步发）；切歌换 src 引发的 pause
          // 也会走到这里，服务端按 sessionId 幂等 upsert，重复快照无害
          flushSession();
        }}
        onEnded={handleEnded}
        onError={handleAudioError}
      />
    </AppContext.Provider>
  );
}
