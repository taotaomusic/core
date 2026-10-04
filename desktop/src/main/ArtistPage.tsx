import { useEffect, useRef, useState } from "react";
import type { UIEvent } from "react";
import {
  absoluteUrl,
  fetchArtistAlbums,
  fetchArtistDetail,
  fetchArtistSongs,
  fetchSimilarArtists,
  readableError,
  SessionExpired,
  type ArtistDetail,
  type SearchAlbum,
  type SearchArtist,
} from "../api";
import type { AlbumTarget } from "./AlbumPage";
import { AlbumCover, ArtistAvatar } from "./SearchPage";
import { PagedSongSection } from "./PagedSongSection";
import "./search.css";

/** 歌手页跳转目标：详情未到时用 name/pic（搜索横排传入）先渲染头部骨架。 */
export type ArtistTarget = {
  source: string;
  id: number;
  name?: string;
  pic?: string;
};

/** 歌手页内嵌标签。 */
type ArtistTab = "songs" | "albums" | "similar";

/**
 * 歌手主页：头部（96px 圆头像 + 名字 + 别名 + 统计）+ 简介 + 内嵌「歌曲 / 专辑 / 相似」标签。
 * 三个标签体常驻挂载、显隐切换（与主界面页面切换同一哲学），歌曲/专辑分页滚动近底自动加载，
 * 专辑与相似首次切到对应标签才拉取；相似歌手点击即切换到该歌手（上层换 target，整页回顶重载）。
 */
export function ArtistPage({
  target,
  onOpenArtist,
  onOpenAlbum,
  onBack,
}: {
  target: ArtistTarget | null;
  onOpenArtist: (target: ArtistTarget) => void;
  onOpenAlbum: (target: AlbumTarget) => void;
  onBack: () => void;
}) {
  // ---- 详情与简介 ----
  const [detail, setDetail] = useState<ArtistDetail | null>(null);
  const [detailError, setDetailError] = useState("");
  const [tab, setTab] = useState<ArtistTab>("songs");
  const [descExpanded, setDescExpanded] = useState(false);

  // 详情请求代数：切换歌手/重试后旧响应作废
  const detailGenRef = useRef(0);
  // 已加载的歌手身份键：同一歌手重复打开（target 对象变化但键相同）不重拉，保留已加载状态
  const loadedKeyRef = useRef<string | null>(null);

  /** 拉取歌手详情：成功替换骨架信息，失败记录错误并给重试。 */
  function loadDetail(t: ArtistTarget) {
    const gen = ++detailGenRef.current;
    setDetailError("");
    void (async () => {
      try {
        const d = await fetchArtistDetail(t.id, t.source);
        if (detailGenRef.current !== gen) return;
        setDetail(d);
      } catch (e) {
        if (detailGenRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
        setDetailError(readableError(e));
      }
    })();
  }

  // 切换歌手：重置详情与标签状态；歌曲/专辑/相似三个标签体以 key 重挂载自行重置
  useEffect(() => {
    if (!target) return;
    const current = target; // 收窄成非空常量，供异步调用使用
    const key = `${current.source}:${current.id}`;
    if (loadedKeyRef.current === key) return;
    loadedKeyRef.current = key;
    setDetail(null);
    setDetailError("");
    setDescExpanded(false);
    setTab("songs");
    void loadDetail(current);
    // 只按 target 身份重载：同一歌手的 target 对象变化由 loadedKeyRef 守卫拦截
  }, [target]);

  // ---- 派生展示值：详情未到时回退搜索传入的骨架信息 ----
  if (!target) return null;
  const t = target; // 收窄成非空常量，供闭包使用
  const artistKey = `${t.source}:${t.id}`;
  const name = detail?.name ?? t.name ?? "未知歌手";
  const pic = detail?.pic ?? t.pic;
  const stats: string[] = [];
  if (detail?.musicCount != null) stats.push(`${formatWan(detail.musicCount)} 首`);
  if (detail?.albumCount != null) stats.push(`${formatWan(detail.albumCount)} 张专辑`);
  if (detail?.fansCount != null) stats.push(`粉丝 ${formatWan(detail.fansCount)}`);
  const desc = detail?.desc;

  return (
    <div className="ar-root">
      <button type="button" className="detail-back" onClick={onBack}>
        ‹ 返回搜索
      </button>

      {/* 头部：详情未到时先用搜索传入的名字/头像渲染骨架 */}
      <div className="ar-head">
        <ArtistHeroAvatar key={`${artistKey}:${pic ?? ""}`} pic={pic} name={name} />
        <div className="ar-info">
          <div className="ar-name" title={name}>
            {name}
          </div>
          {detail?.aliasName && <div className="ar-alias">{detail.aliasName}</div>}
          {stats.length > 0 && <div className="ar-stats">{stats.join(" · ")}</div>}
        </div>
      </div>

      {/* 详情加载失败：给出重试（头部骨架仍保留可看） */}
      {detailError && (
        <div className="detail-center-block tight">
          <p className="detail-center-hint err">{detailError}</p>
          <button type="button" className="detail-retry" onClick={() => loadDetail(t)}>
            重试
          </button>
        </div>
      )}

      {/* 简介：默认收起，点击展开/收起 */}
      {desc && (
        <div className="detail-desc-wrap">
          <div
            className={`detail-desc${descExpanded ? " open" : ""}`}
            onClick={() => setDescExpanded((v) => !v)}
            title={descExpanded ? "点击收起" : "点击展开"}
          >
            {desc}
          </div>
          <button
            type="button"
            className="detail-desc-toggle"
            onClick={() => setDescExpanded((v) => !v)}
          >
            {descExpanded ? "收起" : "展开"}
          </button>
        </div>
      )}

      {/* 内嵌标签切换 */}
      <div className="ar-tabs" role="tablist">
        <button
          type="button"
          role="tab"
          aria-selected={tab === "songs"}
          className={`ar-tab${tab === "songs" ? " on" : ""}`}
          onClick={() => setTab("songs")}
        >
          歌曲
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "albums"}
          className={`ar-tab${tab === "albums" ? " on" : ""}`}
          onClick={() => setTab("albums")}
        >
          专辑
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={tab === "similar"}
          className={`ar-tab${tab === "similar" ? " on" : ""}`}
          onClick={() => setTab("similar")}
        >
          相似
        </button>
      </div>

      {/* 标签体：三个面板常驻挂载，用显隐切换保留各自已加载数据与滚动状态 */}
      <div className="ar-body">
        <div className="ar-pane" style={{ display: tab === "songs" ? "flex" : "none" }}>
          <PagedSongSection
            key={artistKey}
            resetKey={artistKey}
            fetchPage={(page) => fetchArtistSongs(t.id, page, t.source)}
            emptyHint="该歌手暂无歌曲"
          />
        </div>
        <div className="ar-pane" style={{ display: tab === "albums" ? "flex" : "none" }}>
          <ArtistAlbums key={artistKey} target={t} active={tab === "albums"} onOpenAlbum={onOpenAlbum} />
        </div>
        <div className="ar-pane" style={{ display: tab === "similar" ? "flex" : "none" }}>
          <SimilarArtists key={artistKey} target={t} active={tab === "similar"} onOpenArtist={onOpenArtist} />
        </div>
      </div>
    </div>
  );
}

/** 数字过万缩写成「4.8 万」，一万整不带小数；万内原样显示。 */
function formatWan(n: number): string {
  if (n < 10000) return String(n);
  const w = n / 10000;
  const s = w >= 100 ? String(Math.round(w)) : String(Math.round(w * 10) / 10);
  return `${s} 万`;
}

/** 歌手页头部大头像：有图用图（加载失败就地回退），无图用主题色圆 + 名字首字符。 */
function ArtistHeroAvatar({ pic, name }: { pic?: string; name: string }) {
  const [failed, setFailed] = useState(false);
  const showImg = !!pic && !failed;
  return (
    <div className="ar-avatar">
      {showImg ? (
        <img src={absoluteUrl(pic)} alt="" onError={() => setFailed(true)} />
      ) : (
        <span className="ar-initial">{name.charAt(0) || "♪"}</span>
      )}
    </div>
  );
}

/**
 * 歌手的专辑标签体：横排卡片（复用搜索页专辑横排的视觉语言）自动换行纵向滚动。
 * 首次激活才拉第一页；滚动近底部自动翻页；切换歌手由 key 重挂载整体重置。
 */
function ArtistAlbums({
  target,
  active,
  onOpenAlbum,
}: {
  target: ArtistTarget;
  active: boolean;
  onOpenAlbum: (target: AlbumTarget) => void;
}) {
  const [albums, setAlbums] = useState<SearchAlbum[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false); // 第一页已尝试过（成功或失败）：防止重复自动加载

  const targetRef = useRef(target); // 最新目标：异步回调里经它调用
  targetRef.current = target;
  const albumsRef = useRef<SearchAlbum[]>([]); // 最新专辑快照：翻页合并去重用
  const pageRef = useRef(0);
  const busyRef = useRef(false);
  const hasMoreRef = useRef(false);
  const genRef = useRef(0);
  const startedRef = useRef(false); // 首次激活已启动过拉取，避免 effect 重入

  /** 拉取一页专辑：首屏整体替换，翻页按 source:id 去重拼接。 */
  function loadPage(page: number) {
    const gen = ++genRef.current;
    const isFirst = page === 1;
    busyRef.current = true;
    if (isFirst) {
      albumsRef.current = [];
      setAlbums([]);
      setError("");
      setLoading(true);
    } else {
      setLoadingMore(true);
    }
    void (async () => {
      try {
        const t = targetRef.current;
        const r = await fetchArtistAlbums(t.id, page, t.source);
        if (genRef.current !== gen) return;
        const seen = new Set(albumsRef.current.map((a) => `${a.source}:${a.id}`));
        const merged = isFirst
          ? r.albums
          : [...albumsRef.current, ...r.albums.filter((a) => !seen.has(`${a.source}:${a.id}`))];
        albumsRef.current = merged;
        setAlbums(merged);
        setTotal(r.total);
        hasMoreRef.current = r.hasMore;
        setHasMore(r.hasMore);
        pageRef.current = page;
        setLoaded(true);
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return;
        setError(readableError(e));
        setLoaded(true); // 失败也算已尝试：自动加载不再重入，改由重试按钮触发
      } finally {
        if (genRef.current === gen) {
          busyRef.current = false;
          if (isFirst) setLoading(false);
          else setLoadingMore(false);
        }
      }
    })();
  }

  // 首次切到本标签时拉第一页（startedRef 防止 active/loading 抖动引发重复自动加载）
  useEffect(() => {
    if (!active || startedRef.current) return;
    startedRef.current = true;
    loadPage(1);
  }, [active]);

  /** 无限滚动：距底不足 300px 且允许加载时翻下一页。 */
  function handleScroll(e: UIEvent<HTMLDivElement>) {
    if (busyRef.current || !hasMoreRef.current || loading || loadingMore) return;
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300) loadPage(pageRef.current + 1);
  }

  return (
    <div className="ar-scroll" onScroll={handleScroll}>
      {loading && <p className="detail-center-hint">正在加载专辑…</p>}
      {!loading && error && albums.length === 0 && (
        <div className="detail-center-block">
          <p className="detail-center-hint err">{error}</p>
          <button type="button" className="detail-retry" onClick={() => loadPage(1)}>
            重试
          </button>
        </div>
      )}
      {!loading && !error && loaded && albums.length === 0 && (
        <p className="detail-center-hint">该歌手暂无专辑</p>
      )}
      {albums.length > 0 && (
        <div className="ar-albums">
          {albums.map((al) => (
            <div
              key={`${al.source}:${al.id}`}
              className="sp-album-item"
              title={`查看专辑「${al.name}」`}
              onClick={() =>
                onOpenAlbum({
                  source: al.source,
                  id: al.id,
                  name: al.name,
                  pic: al.pic,
                  artist: al.artist,
                  artistId: al.artistId,
                })
              }
            >
              <AlbumCover pic={al.pic} />
              <span className="sp-album-name">{al.name}</span>
              {al.artist && <span className="sp-album-artist">{al.artist}</span>}
            </div>
          ))}
        </div>
      )}
      {!loading && error !== "" && albums.length > 0 && (
        <p className="songrow-more-err">{error}（可继续下滑重试）</p>
      )}
      {loadingMore && <p className="songrow-foot">正在加载更多…</p>}
      {!loadingMore && albums.length > 0 && !hasMore && (
        <p className="songrow-foot">
          {total > albums.length
            ? `已显示全部 ${albums.length} 张专辑（共 ${total} 张）`
            : `已显示全部 ${albums.length} 张专辑`}
        </p>
      )}
    </div>
  );
}

/**
 * 相似歌手标签体：头像横排（复用搜索页歌手横排的视觉语言），点击切换到该歌手
 * （上层换 target，整页重载并回顶）。首次激活才拉取；接口一次性返回、无分页。
 */
function SimilarArtists({
  target,
  active,
  onOpenArtist,
}: {
  target: ArtistTarget;
  active: boolean;
  onOpenArtist: (target: ArtistTarget) => void;
}) {
  const [list, setList] = useState<SearchArtist[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);

  const targetRef = useRef(target);
  targetRef.current = target;
  const genRef = useRef(0);
  const startedRef = useRef(false);

  /** 拉取相似歌手：成功替换列表，失败给重试。 */
  function load() {
    const gen = ++genRef.current;
    setError("");
    setLoading(true);
    void (async () => {
      try {
        const t = targetRef.current;
        const artists = await fetchSimilarArtists(t.id, t.source);
        if (genRef.current !== gen) return;
        setList(artists);
        setLoaded(true);
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return;
        setError(readableError(e));
        setLoaded(true);
      } finally {
        if (genRef.current === gen) setLoading(false);
      }
    })();
  }

  // 首次切到本标签时拉取（startedRef 防止重复自动加载）
  useEffect(() => {
    if (!active || startedRef.current) return;
    startedRef.current = true;
    load();
  }, [active]);

  return (
    <div className="ar-scroll">
      {loading && <p className="detail-center-hint">正在加载相似歌手…</p>}
      {!loading && error && (
        <div className="detail-center-block">
          <p className="detail-center-hint err">{error}</p>
          <button type="button" className="detail-retry" onClick={() => load()}>
            重试
          </button>
        </div>
      )}
      {!loading && !error && loaded && list.length === 0 && (
        <p className="detail-center-hint">暂无相似歌手</p>
      )}
      {list.length > 0 && (
        <div className="sp-hscroll">
          {list.map((a) => (
            <div
              key={`${a.source}:${a.id}`}
              className="sp-artist-item"
              title={`查看歌手「${a.name}」`}
              onClick={() => onOpenArtist({ source: a.source, id: a.id, name: a.name, pic: a.pic })}
            >
              <ArtistAvatar pic={a.pic} name={a.name} />
              <div className="sp-artist-meta">
                <span className="sp-artist-name">{a.name}</span>
                {a.songCount != null && <span className="sp-artist-sub">{a.songCount} 首</span>}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
