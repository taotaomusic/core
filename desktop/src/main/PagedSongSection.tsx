import { useEffect, useRef, useState } from "react";
import {
  readableError,
  SessionExpired,
  songKeyOf,
  type PagedSongs,
  type Song,
} from "../api";
import { SongList } from "./SongList";
// 歌曲行样式复用 search.css 的全局类，显式导入保证样式自足
import "./search.css";

/**
 * 分页歌曲区块：歌手页/专辑页共用的「歌曲」标签体。
 * resetKey 变化（切换歌手/专辑）时清空并重拉第一页；滚动近底部自动翻页，
 * 新页按列表身份 key 去重拼接（与搜索页同口径）；播放链路与搜索页一致
 * （SongList 缺省把整列设为队列并从点击行播放）。
 * fetchPage 通过镜像 ref 读取最新闭包，避免父组件重渲染引发重复加载。
 */
export function PagedSongSection({
  fetchPage,
  resetKey,
  emptyHint = "暂无歌曲",
}: {
  /** 按页码拉取一页歌曲（调用方绑定具体歌手/专辑与音源）。 */
  fetchPage: (page: number) => Promise<PagedSongs>;
  /** 目标身份键（source:id）：变化即清空重拉第一页。 */
  resetKey: string;
  /** 空列表提示文案 */
  emptyHint?: string;
}) {
  const [songs, setSongs] = useState<Song[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");

  const fetchRef = useRef(fetchPage); // 最新拉取函数：异步回调里经它调用，不闭包捕获旧值
  fetchRef.current = fetchPage;
  const songsRef = useRef<Song[]>([]); // 最新歌曲快照：翻页合并去重用
  const pageRef = useRef(0); // 已加载到的页码
  const busyRef = useRef(false); // 翻页请求进行中，防滚动事件重复触发
  const hasMoreRef = useRef(false); // hasMore 镜像：loadMore 闭包读最新值
  const genRef = useRef(0); // 代数计数：resetKey 变化/卸载后旧响应作废

  // 切换目标（或首次挂载）：清空列表并拉第一页
  useEffect(() => {
    const gen = ++genRef.current;
    busyRef.current = false;
    pageRef.current = 0;
    songsRef.current = [];
    hasMoreRef.current = false;
    setSongs([]);
    setTotal(0);
    setHasMore(false);
    setError("");
    setLoading(true);
    void (async () => {
      try {
        const r = await fetchRef.current(1);
        if (genRef.current !== gen) return; // 已切换目标或卸载，丢弃旧响应
        songsRef.current = r.songs;
        setSongs(r.songs);
        setTotal(r.total);
        hasMoreRef.current = r.hasMore;
        setHasMore(r.hasMore);
        pageRef.current = 1;
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
        setError(readableError(e));
      } finally {
        if (genRef.current === gen) setLoading(false);
      }
    })();
    return () => {
      genRef.current++; // 卸载时作废在途响应
    };
  }, [resetKey]);

  /** 无限滚动：距底不足 300px 且允许加载时翻下一页；并发去抖由 busy 标记保证。 */
  function loadMore() {
    if (busyRef.current || !hasMoreRef.current) return;
    const gen = genRef.current;
    const page = pageRef.current + 1;
    busyRef.current = true;
    setLoadingMore(true);
    void (async () => {
      try {
        const r = await fetchRef.current(page);
        if (genRef.current !== gen) return;
        // 以已加载结果为快照，新页按列表身份 key 去重后拼接，避免分页重叠出现重复行
        const seen = new Set(songsRef.current.map(songKeyOf));
        const merged = [...songsRef.current, ...r.songs.filter((s) => !seen.has(songKeyOf(s)))];
        songsRef.current = merged;
        setSongs(merged);
        setTotal(r.total);
        hasMoreRef.current = r.hasMore;
        setHasMore(r.hasMore);
        pageRef.current = page;
        setError("");
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return;
        setError(readableError(e));
      } finally {
        if (genRef.current === gen) {
          busyRef.current = false;
          setLoadingMore(false);
        }
      }
    })();
  }

  return (
    <SongList
      songs={songs}
      loading={loading}
      error={error}
      emptyHint={emptyHint}
      hasMore={hasMore}
      total={total}
      onLoadMore={loadMore}
      loadingMore={loadingMore}
    />
  );
}
