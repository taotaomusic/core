import { createContext, useCallback, useContext, useEffect, useRef, useState } from "react";
import type { ReactNode, SyntheticEvent } from "react";
import {
  addFavorite,
  favoriteKeyOf,
  fetchFavorites,
  fetchSongInfos,
  readableError,
  removeFavorite,
  resolveLink,
  SessionExpired,
  type Song,
} from "../api";

/** 重复播放模式：off 播完即停；all 列表循环；one 单曲循环。 */
export type RepeatMode = "off" | "all" | "one";

/** useApp 返回的上下文值：播放器 / 收藏 / UI 轻提示三大块。 */
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
 * 全局状态 Provider：渲染唯一的 <audio> 元素，承载播放队列、收藏库和轻提示。
 * 播放失败/收藏失败等会话过期统一经 props.onExpired 上抛给上层登出。
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
      // 切歌先复位进度，随后由音频元素事件写入真实值
      setPosition(0);
      setDuration(0);
      const gen = ++playGenRef.current;
      try {
        const url = await resolveLink(song);
        if (gen !== playGenRef.current) return; // 已有更新的播放请求，丢弃过期结果
        const el = audioRef.current;
        if (!el) return;
        el.src = url;
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

  // ---- 音频元素事件：isPlaying/进度全部以元素事件为准 ----

  const handleTimeUpdate = (e: SyntheticEvent<HTMLAudioElement>) => {
    setPosition(e.currentTarget.currentTime);
  };

  const syncDuration = () => {
    const el = audioRef.current;
    if (!el) return;
    setDuration(Number.isFinite(el.duration) ? el.duration : 0);
  };

  // 播完：one 重播当前；all 到尾回 0 继续；off 最后一首停住，其余进入下一首
  const handleEnded = () => {
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
