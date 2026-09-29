import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode, SyntheticEvent } from "react";
import {
  addFavorite,
  favoriteKeyOf,
  fetchFavorites,
  fetchSongInfos,
  labelOfQuality,
  readableError,
  removeFavorite,
  resolveLink,
  SessionExpired,
  type Song,
} from "../api";

/** 重复播放模式：off 播完即停；all 列表循环；one 单曲循环。 */
export type RepeatMode = "off" | "all" | "one";

/** useApp 返回的上下文值：播放器 / 队列操作 / 音质 / 收藏 / 定时关闭 / UI 轻提示。 */
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

/**
 * 全局状态 Provider：渲染唯一的 <audio> 元素，承载播放队列、收藏库、音质偏好、
 * 定时关闭和轻提示。播放/收藏/换源等会话过期统一经 props.onExpired 上抛给上层登出。
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

  // ---- UI 状态 ----
  const [showDetail, setShowDetail] = useState(false);
  const [toastMsg, setToastMsg] = useState("");
  const toastTimerRef = useRef<number | null>(null);

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

  const playAt = useCallback(
    async (i: number) => {
      const list = queueRef.current;
      if (i < 0 || i >= list.length) return;
      const song = list[i];
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
    [toast],
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

  // 挂载后拉取一次收藏库
  useEffect(() => {
    void refreshFavorites();
  }, [refreshFavorites]);

  // 组件卸载时清掉定时关闭的 interval，避免定时器泄漏
  useEffect(() => {
    return () => {
      if (sleepTimerRef.current !== null) window.clearInterval(sleepTimerRef.current);
    };
  }, []);

  // ---- 音频元素事件：isPlaying/进度全部以元素事件为准 ----

  const handleTimeUpdate = (e: SyntheticEvent<HTMLAudioElement>) => {
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
      const el = audioRef.current;
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
        onPlay={() => setIsPlaying(true)}
        onPause={() => setIsPlaying(false)}
        onEnded={handleEnded}
        onError={handleAudioError}
      />
    </AppContext.Provider>
  );
}
