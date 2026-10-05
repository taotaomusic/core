import { Fragment, useEffect, useRef, useState } from "react";
import type { ReactNode, UIEvent } from "react";
import {
  absoluteUrl,
  fetchHotSearches,
  fetchSuggestions,
  readableError,
  searchAlbumsPaged,
  searchArtistsPaged,
  searchLyricsPaged,
  searchPlaylistsPaged,
  searchSongs,
  searchVideosPaged,
  SessionExpired,
  songKeyOf,
  type SearchAlbum,
  type SearchArtist,
  type SearchPlaylist,
  type SearchVideo,
  type Song,
} from "../api";
import type { AlbumTarget } from "./AlbumPage";
import type { ArtistTarget } from "./ArtistPage";
import { useApp } from "../state/AppState";
import { SongList } from "./SongList";
import {
  addHistory,
  clearHistory,
  isPaused,
  loadHistory,
  removeHistory,
  setPaused,
} from "./searchHistory";
import "./search.css";

/**
 * 搜索结果七标签：all=综合（横排区块 + 歌曲列表）、songs=单曲、artists=歌手、
 * albums=专辑、playlists=歌单、videos=视频、lyrics=歌词。
 */
type SearchTab = "all" | "songs" | "artists" | "albums" | "playlists" | "videos" | "lyrics";

/** 数字缩写：过万「x.x万」、过亿「x.x亿」，末位 .0 抹掉；不足一万原样返回。 */
function shortCount(n: number | undefined): string {
  if (n == null || !Number.isFinite(n) || n <= 0) return "";
  const trim = (v: number) => v.toFixed(1).replace(/\.0$/, "");
  if (n >= 100_000_000) return `${trim(n / 100_000_000)}亿`;
  if (n >= 10_000) return `${trim(n / 10_000)}万`;
  return String(n);
}

/** 搜索小图标（放大镜），用于联想列表行首。 */
function SearchIcon() {
  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      aria-hidden="true"
    >
      <circle cx="11" cy="11" r="7" />
      <line x1="16.5" y1="16.5" x2="21" y2="21" />
    </svg>
  );
}

/**
 * 独立搜索页，占满主内容区，对标安卓端 SearchPage。
 * 三态视图：无词未搜索 → 搜索历史 + 热门搜索；有词未搜索 → 联想列表；
 * 已搜索 → 七标签结果页（综合 / 单曲 / 歌手 / 专辑 / 歌单 / 视频 / 歌词）。
 * 七个标签体常驻挂载、显隐切换：综合 = 歌手/专辑横排 + 歌曲列表，单曲 = 仅歌曲列表
 * （同一份数据），歌手/专辑/歌单/视频 = 分页卡片流，歌词 = 分页歌词行列表
 * （均为首次切入才拉第一页，滚动近底自动翻页）。
 * 搜索历史只存本机不上传；会话过期由 AppState 层统一处理。
 * 结果区与歌曲行「⋯」菜单点击后经 onOpenArtist / onOpenAlbum 跳转歌手主页与专辑页。
 * 歌单/视频条目点击只提示不跳转：桌面端尚未接入在线歌单详情与 MV 播放能力。
 */
export function SearchPage({
  onOpenArtist,
  onOpenAlbum,
}: {
  onOpenArtist: (target: ArtistTarget) => void;
  onOpenAlbum: (target: AlbumTarget) => void;
}) {
  const { toast } = useApp();
  const [keyword, setKeyword] = useState("");
  const [hasSearched, setHasSearched] = useState(false);
  const [history, setHistory] = useState<string[]>(() => loadHistory());
  const [historyPaused, setHistoryPaused] = useState(() => isPaused());
  const [confirmingClear, setConfirmingClear] = useState(false);
  const [hot, setHot] = useState<string[]>([]);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [songs, setSongs] = useState<Song[]>([]);
  const [artists, setArtists] = useState<SearchArtist[]>([]);
  const [albums, setAlbums] = useState<SearchAlbum[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  // 结果七标签当前项
  const [tab, setTab] = useState<SearchTab>("all");
  // 搜索代次：每次提交搜索 +1，作为歌手/专辑/歌单/视频/歌词分页流的 remount key（新搜索即清空重置）
  const [searchEpoch, setSearchEpoch] = useState(0);

  const inputRef = useRef<HTMLInputElement>(null);
  const genRef = useRef(0); // 搜索代次计数：旧响应不得覆盖新结果
  const pageRef = useRef(1); // 已加载到的页码
  const busyRef = useRef(false); // 首屏或翻页请求进行中，防滚动事件重复触发
  const keywordRef = useRef(""); // 最新关键词，供联想/翻页的异步回调比对
  keywordRef.current = keyword;

  // 进页自动聚焦（上层可能先以 display:none 挂载保持状态，此时不抢焦点）
  useEffect(() => {
    const el = inputRef.current;
    if (el && el.offsetParent !== null) el.focus();
  }, []);

  // 热门搜索进页拉一次；失败静默留空，不打扰用户
  useEffect(() => {
    let alive = true;
    fetchHotSearches(10)
      .then((list) => {
        if (alive) setHot(list.slice(0, 10));
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);

  // 联想词：250ms 取消式防抖；返回后校验「发起时的词 === 当前词」，不一致丢弃
  useEffect(() => {
    const kw = keyword.trim();
    if (hasSearched || !kw) {
      setSuggestions([]);
      return;
    }
    const timer = window.setTimeout(() => {
      fetchSuggestions(kw, 10)
        .then((list) => {
          if (keywordRef.current.trim() !== kw) return;
          setSuggestions(list);
        })
        .catch(() => {
          if (keywordRef.current.trim() === kw) setSuggestions([]);
        });
    }, 250);
    return () => window.clearTimeout(timer);
  }, [keyword, hasSearched]);

  /** 请求一页结果：首屏 loading + 骨架，翻页 loadingMore + 快照拼接防重。 */
  async function runSearch(word: string, page: number, prev: Song[]) {
    const gen = ++genRef.current;
    const isFirst = page === 1;
    busyRef.current = true;
    if (isFirst) {
      setLoading(true);
      setError("");
    } else {
      setLoadingMore(true);
    }
    try {
      const r = await searchSongs(word, page);
      if (genRef.current !== gen) return; // 已有更新的搜索，丢弃旧响应
      // 以请求前结果为快照，新页按列表身份 key 去重后拼接，避免分页重叠出现重复行
      const seen = new Set(prev.map(songKeyOf));
      const merged = isFirst
        ? r.songs
        : [...prev, ...r.songs.filter((s) => !seen.has(songKeyOf(s)))];
      setSongs(merged);
      // 歌手/专辑区块只取第一页（服务端翻页不再下发，第 2 页响应里本来就是空数组）
      if (isFirst) {
        setArtists(r.artists);
        setAlbums(r.albums);
      }
      setTotal(r.total);
      setHasMore(r.hasMore);
      pageRef.current = page;
      if (!isFirst) setError("");
    } catch (e) {
      if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
      if (genRef.current !== gen) return;
      setError(readableError(e));
    } finally {
      if (genRef.current === gen) {
        busyRef.current = false;
        if (isFirst) setLoading(false);
        else setLoadingMore(false);
      }
    }
  }

  /** 提交搜索：写历史（未暂停时）→ 切到结果视图 → 请求第一页。 */
  function submitSearch(rawKw: string) {
    const word = rawKw.trim();
    if (!word) return;
    if (!historyPaused) setHistory(addHistory(word));
    setKeyword(word);
    setHasSearched(true);
    setSuggestions([]);
    setConfirmingClear(false);
    setSongs([]);
    setArtists([]);
    setAlbums([]);
    setHasMore(false);
    setTotal(0);
    setTab("all"); // 新搜索回到「综合」标签
    setSearchEpoch((n) => n + 1); // 递增代次：歌手/专辑卡片流经 key 重挂载整体清空
    void runSearch(word, 1, []);
  }

  /** 无限滚动：SongList 滚动距底不足 300px 时回调，加载下一页。 */
  function loadMore() {
    if (busyRef.current || !hasMore) return;
    void runSearch(keyword, pageRef.current + 1, songs);
  }

  /** 输入框编辑：一旦改动即离开结果视图（回到联想/历史），与安卓一致。 */
  function onKeywordChange(v: string) {
    keywordRef.current = v;
    setKeyword(v);
    if (hasSearched) setHasSearched(false);
  }

  /** 切换「记录搜索历史」开关；暂停时旧历史保留可点但不新增。 */
  function togglePauseRecord() {
    const next = !historyPaused;
    setPaused(next);
    setHistoryPaused(next);
  }

  function doClearHistory() {
    clearHistory();
    setHistory([]);
    setConfirmingClear(false);
  }

  function removeOne(kw: string) {
    setHistory(removeHistory(kw));
  }

  const kwEmpty = keyword.trim() === "";
  const showDiscover = !hasSearched && kwEmpty; // 搜索历史 + 热门搜索
  const showSuggest = !hasSearched && !kwEmpty; // 联想列表

  /** 歌曲行菜单「查看歌手」：歌手头像与歌曲封面不是一回事，pic 不传、由歌手页自行兜底。 */
  function openSongArtist(song: Song) {
    const id = song.artistId;
    if (id == null || !(id > 0)) return;
    onOpenArtist({ source: song.source, id, name: song.artist });
  }

  /** 歌曲行菜单「查看专辑」：专辑封面就是歌曲封面，带上 pic 让专辑页先出骨架。 */
  function openSongAlbum(song: Song) {
    const id = song.albumId;
    if (id == null || !(id > 0)) return;
    onOpenAlbum({
      source: song.source,
      id,
      name: song.album,
      pic: song.coverUrl,
      artist: song.artist,
    });
  }

  // 综合/单曲两个标签共用同一份歌曲列表配置（各自持有一份 SongList 实例，播放/翻页行为一致）
  const songListProps = {
    songs,
    loading,
    error,
    emptyHint: "没有找到相关歌曲，换个关键词再试试",
    hasMore,
    total,
    onLoadMore: loadMore,
    loadingMore,
    onOpenArtist: openSongArtist,
    onOpenAlbum: openSongAlbum,
  };

  return (
    <div className="sp-root">
      {/* 搜索栏 */}
      <div className="sp-searchbar">
        <div className="sp-input-wrap">
          <input
            ref={inputRef}
            value={keyword}
            placeholder="搜索歌曲或歌手"
            onChange={(e) => onKeywordChange(e.target.value)}
            onKeyDown={(e) => {
              // 中文输入法组词期间的回车不触发搜索
              if (e.key === "Enter" && !e.nativeEvent.isComposing) submitSearch(keyword);
            }}
          />
          {keyword !== "" && (
            <button
              type="button"
              className="sp-clear"
              title="清空"
              onClick={() => {
                onKeywordChange("");
                inputRef.current?.focus();
              }}
            >
              ×
            </button>
          )}
        </div>
        <button type="button" className="sp-btn-search" onClick={() => submitSearch(keyword)}>
          搜索
        </button>
      </div>

      <div className="sp-body">
        {showDiscover && (
          <div className="sp-scroll">
            {history.length > 0 && (
              <section className="sp-section">
                <div className="sp-section-head">
                  <span className="sp-section-title">搜索历史</span>
                  <label className="sp-pause">
                    <input type="checkbox" checked={!historyPaused} onChange={togglePauseRecord} />
                    <span>{historyPaused ? "已暂停" : "记录"}</span>
                  </label>
                  <button
                    type="button"
                    className="sp-text-btn"
                    onClick={() => setConfirmingClear(true)}
                  >
                    清空
                  </button>
                </div>
                {confirmingClear ? (
                  <div className="sp-confirm">
                    <span className="sp-confirm-msg">
                      确定要清空全部搜索历史吗？清空后无法恢复。
                    </span>
                    <button type="button" className="sp-confirm-btn ok" onClick={doClearHistory}>
                      确定
                    </button>
                    <button
                      type="button"
                      className="sp-confirm-btn cancel"
                      onClick={() => setConfirmingClear(false)}
                    >
                      取消
                    </button>
                  </div>
                ) : (
                  <div className="sp-chips">
                    {history.map((h) => (
                      <span key={h} className="sp-chip" title={`搜索「${h}」`} onClick={() => submitSearch(h)}>
                        <span className="sp-chip-word">{h}</span>
                        <button
                          type="button"
                          className="sp-chip-x"
                          title="删除该条"
                          onClick={(e) => {
                            e.stopPropagation();
                            removeOne(h);
                          }}
                        >
                          ×
                        </button>
                      </span>
                    ))}
                  </div>
                )}
              </section>
            )}

            {hot.length > 0 && (
              <section className="sp-section">
                <div className="sp-section-head">
                  <span className="sp-section-title">热门搜索</span>
                  <span className="sp-section-sub">实时更新</span>
                </div>
                <div className="sp-hot-grid">
                  {hot.map((w, i) => (
                    <div key={`${i}-${w}`} className="sp-hot-item" onClick={() => submitSearch(w)}>
                      <span className={`sp-hot-rank${i < 3 ? " top" : ""}`}>{i + 1}</span>
                      <span className="sp-hot-word">{w}</span>
                    </div>
                  ))}
                </div>
              </section>
            )}
          </div>
        )}

        {showSuggest && (
          <div className="sp-scroll">
            {suggestions.map((w, i) => (
              <div key={`${i}-${w}`} className="sp-sug-row" onClick={() => submitSearch(w)}>
                <span className="sp-sug-icon">
                  <SearchIcon />
                </span>
                <span className="sp-sug-word">{w}</span>
              </div>
            ))}
          </div>
        )}

        {hasSearched && (
          <>
            {/* 七标签切换：分段控件视觉与歌手页内嵌标签一致；标签多时容器横向滚动 */}
            <div className="sp-tabs" role="tablist">
              <button
                type="button"
                role="tab"
                aria-selected={tab === "all"}
                className={`sp-tab${tab === "all" ? " on" : ""}`}
                onClick={() => setTab("all")}
              >
                综合
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "songs"}
                className={`sp-tab${tab === "songs" ? " on" : ""}`}
                onClick={() => setTab("songs")}
              >
                单曲
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "artists"}
                className={`sp-tab${tab === "artists" ? " on" : ""}`}
                onClick={() => setTab("artists")}
              >
                歌手
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "albums"}
                className={`sp-tab${tab === "albums" ? " on" : ""}`}
                onClick={() => setTab("albums")}
              >
                专辑
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "playlists"}
                className={`sp-tab${tab === "playlists" ? " on" : ""}`}
                onClick={() => setTab("playlists")}
              >
                歌单
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "videos"}
                className={`sp-tab${tab === "videos" ? " on" : ""}`}
                onClick={() => setTab("videos")}
              >
                视频
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={tab === "lyrics"}
                className={`sp-tab${tab === "lyrics" ? " on" : ""}`}
                onClick={() => setTab("lyrics")}
              >
                歌词
              </button>
            </div>

            {/* 综合：歌手/专辑横排 + 歌曲列表（原有布局） */}
            <div className="sp-pane" style={{ display: tab === "all" ? "flex" : "none" }}>
              {(artists.length > 0 || albums.length > 0) && (
                <div className="sp-strips">
                  {artists.length > 0 && <ArtistStrip artists={artists} onOpenArtist={onOpenArtist} />}
                  {albums.length > 0 && <AlbumStrip albums={albums} onOpenAlbum={onOpenAlbum} />}
                </div>
              )}
              <SongList {...songListProps} />
            </div>

            {/* 单曲：仅歌曲列表，同一份 songs 数据 */}
            <div className="sp-pane" style={{ display: tab === "songs" ? "flex" : "none" }}>
              <SongList {...songListProps} />
            </div>

            {/* 歌手：分页卡片流；key 含搜索代次，新搜索即整体重置 */}
            <div className="sp-pane" style={{ display: tab === "artists" ? "flex" : "none" }}>
              <PagedCardFlow<SearchArtist>
                key={`artists-${searchEpoch}`}
                active={tab === "artists"}
                fetchPage={(page) =>
                  searchArtistsPaged(keyword, page).then((r) => ({
                    items: r.artists,
                    total: r.total,
                    hasMore: r.hasMore,
                  }))
                }
                keyOf={(a) => `${a.source}:${a.id}`}
                noun="位歌手"
                loadingHint="正在加载歌手…"
                emptyHint="没有找到相关歌手，换个关键词再试试"
                renderCard={(a) => <ArtistCard artist={a} onOpenArtist={onOpenArtist} />}
              />
            </div>

            {/* 专辑：分页卡片流 */}
            <div className="sp-pane" style={{ display: tab === "albums" ? "flex" : "none" }}>
              <PagedCardFlow<SearchAlbum>
                key={`albums-${searchEpoch}`}
                active={tab === "albums"}
                fetchPage={(page) =>
                  searchAlbumsPaged(keyword, page).then((r) => ({
                    items: r.albums,
                    total: r.total,
                    hasMore: r.hasMore,
                  }))
                }
                keyOf={(al) => `${al.source}:${al.id}`}
                noun="张专辑"
                loadingHint="正在加载专辑…"
                emptyHint="没有找到相关专辑，换个关键词再试试"
                renderCard={(al) => <AlbumCard album={al} onOpenAlbum={onOpenAlbum} />}
              />
            </div>

            {/* 歌单：分页横版卡片纵向列表；桌面端未接入在线歌单详情，点击仅提示 */}
            <div className="sp-pane" style={{ display: tab === "playlists" ? "flex" : "none" }}>
              <PagedCardFlow<SearchPlaylist>
                key={`playlists-${searchEpoch}`}
                active={tab === "playlists"}
                fetchPage={(page) =>
                  searchPlaylistsPaged(keyword, page).then((r) => ({
                    items: r.playlists,
                    total: r.total,
                    hasMore: r.hasMore,
                  }))
                }
                keyOf={(p) => `${p.source}:${p.id}`}
                noun="个歌单"
                loadingHint="正在加载歌单…"
                emptyHint="没有找到相关歌单，换个关键词再试试"
                cardsClassName="sp-pl-list"
                renderCard={(p) => (
                  <PlaylistCard
                    playlist={p}
                    onOpen={() => {
                      // PlaylistsPage 只有用户自己的服务端歌单（fetchPlaylistDetail 不认酷我在线歌单 id），
                      // 在线歌单详情链路未接入前点击只提示不跳转。
                      toast("桌面端暂不支持打开在线歌单");
                    }}
                  />
                )}
              />
            </div>

            {/* 视频：双列 16:9 封面网格；桌面端没有 MV 播放链路，点击仅提示 */}
            <div className="sp-pane" style={{ display: tab === "videos" ? "flex" : "none" }}>
              <PagedCardFlow<SearchVideo>
                key={`videos-${searchEpoch}`}
                active={tab === "videos"}
                fetchPage={(page) =>
                  searchVideosPaged(keyword, page).then((r) => ({
                    items: r.videos,
                    total: r.total,
                    hasMore: r.hasMore,
                  }))
                }
                keyOf={(v) => `${v.source}:${v.id}:${v.vid ?? ""}`}
                noun="个视频"
                loadingHint="正在加载视频…"
                emptyHint="没有找到相关视频，换个关键词再试试"
                cardsClassName="sp-vd-grid"
                renderCard={(v) => (
                  <VideoCard
                    video={v}
                    onOpen={() => {
                      // 点视频 = 播该歌的 MV，但桌面端无 mvSong/playMv 播放能力，先以提示兜底。
                      toast("桌面端暂不支持播放 MV");
                    }}
                  />
                )}
              />
            </div>

            {/* 歌词：分页歌词行列表（SongList 无摘要插槽，按约定不改它，行点击走同一 playList 链路） */}
            <div className="sp-pane" style={{ display: tab === "lyrics" ? "flex" : "none" }}>
              <LyricResultList
                key={`lyrics-${searchEpoch}`}
                active={tab === "lyrics"}
                fetchPage={(page) =>
                  searchLyricsPaged(keyword, page).then((r) => ({
                    items: r.songs,
                    total: r.total,
                    hasMore: r.hasMore,
                  }))
                }
              />
            </div>
          </>
        )}
      </div>
    </div>
  );
}

/** 歌手横排区块：卡片复用 ArtistCard（圆形头像 + 名字 +「N 首」）。 */
function ArtistStrip({
  artists,
  onOpenArtist,
}: {
  artists: SearchArtist[];
  onOpenArtist: (target: ArtistTarget) => void;
}) {
  return (
    <section className="sp-section">
      <div className="sp-section-head">
        <span className="sp-section-title">歌手</span>
      </div>
      <div className="sp-hscroll">
        {artists.map((a) => (
          <ArtistCard key={`${a.source}:${a.id}`} artist={a} onOpenArtist={onOpenArtist} />
        ))}
      </div>
    </section>
  );
}

/** 专辑横排区块：卡片复用 AlbumCard（圆角封面 + 专辑名 + 歌手名小字）。 */
function AlbumStrip({
  albums,
  onOpenAlbum,
}: {
  albums: SearchAlbum[];
  onOpenAlbum: (target: AlbumTarget) => void;
}) {
  return (
    <section className="sp-section">
      <div className="sp-section-head">
        <span className="sp-section-title">专辑</span>
      </div>
      <div className="sp-hscroll">
        {albums.map((al) => (
          <AlbumCard key={`${al.source}:${al.id}`} album={al} onOpenAlbum={onOpenAlbum} />
        ))}
      </div>
    </section>
  );
}

/**
 * 歌手卡片：圆形头像 + 名字（单行省略）+「N 首」；点击跳转歌手主页。
 * 横排区块（综合标签）与歌手标签卡片流共用同一视觉。
 */
function ArtistCard({
  artist,
  onOpenArtist,
}: {
  artist: SearchArtist;
  onOpenArtist: (target: ArtistTarget) => void;
}) {
  return (
    <div
      className="sp-artist-item"
      title={`查看歌手「${artist.name}」`}
      onClick={() =>
        onOpenArtist({ source: artist.source, id: artist.id, name: artist.name, pic: artist.pic })
      }
    >
      <ArtistAvatar pic={artist.pic} name={artist.name} />
      <div className="sp-artist-meta">
        <span className="sp-artist-name">{artist.name}</span>
        {artist.songCount != null && <span className="sp-artist-sub">{artist.songCount} 首</span>}
      </div>
    </div>
  );
}

/**
 * 专辑卡片：圆角封面 + 专辑名（最多两行省略）+ 歌手名小字；点击跳转专辑页。
 * 横排区块（综合标签）与专辑标签卡片流共用同一视觉。
 */
function AlbumCard({
  album,
  onOpenAlbum,
}: {
  album: SearchAlbum;
  onOpenAlbum: (target: AlbumTarget) => void;
}) {
  return (
    <div
      className="sp-album-item"
      title={`查看专辑「${album.name}」`}
      onClick={() =>
        onOpenAlbum({
          source: album.source,
          id: album.id,
          name: album.name,
          pic: album.pic,
          artist: album.artist,
          artistId: album.artistId,
        })
      }
    >
      <AlbumCover pic={album.pic} />
      <span className="sp-album-name">{album.name}</span>
      {album.artist && <span className="sp-album-artist">{album.artist}</span>}
    </div>
  );
}

/** 歌手头像：有图用图（加载失败就地回退），无图直接主题色圆 + 名字首字符。 */
export function ArtistAvatar({ pic, name }: { pic?: string; name: string }) {
  const [failed, setFailed] = useState(false);
  const showImg = !!pic && !failed;
  return (
    <div className="sp-artist-avatar">
      {showImg ? (
        <img
          src={absoluteUrl(pic)}
          alt=""
          onError={() => setFailed(true)}
        />
      ) : (
        <span className="sp-artist-initial">{name.charAt(0) || "♪"}</span>
      )}
    </div>
  );
}

/** 专辑封面：有图用图，加载失败/无图回退主题色块，与歌曲行封面的音符占位同一设计语言。 */
export function AlbumCover({ pic }: { pic?: string }) {
  const [failed, setFailed] = useState(false);
  const showImg = !!pic && !failed;
  return (
    <div className="sp-album-cover">
      {showImg ? (
        <img
          src={absoluteUrl(pic)}
          alt=""
          onError={() => setFailed(true)}
        />
      ) : (
        <span className="note">♪</span>
      )}
    </div>
  );
}

/**
 * 分页卡片流（搜索结果的歌手/专辑标签体共用）：首次激活才拉第一页（未激活不预取），
 * 滚动近底部自动翻页，新页按 keyOf 去重拼接（与歌曲列表翻页同口径）。
 * 新搜索由调用方经 key 重挂载整体重置；fetchPage 经镜像 ref 读取最新闭包，
 * 避免父组件重渲染引发重复加载。
 */
function PagedCardFlow<T>({
  fetchPage,
  active,
  keyOf,
  noun,
  loadingHint,
  emptyHint,
  renderCard,
  cardsClassName = "sp-cards",
}: {
  /** 按页码拉取一页卡片数据（调用方绑定关键词与接口）。 */
  fetchPage: (page: number) => Promise<{ items: T[]; total: number; hasMore: boolean }>;
  /** 本标签是否处于激活态：首次激活才拉第一页。 */
  active: boolean;
  /** 卡片身份键（source:id）：翻页去重与渲染 key。 */
  keyOf: (item: T) => string;
  /** 量词名词（如「位歌手」「张专辑」），用于收口页脚文案。 */
  noun: string;
  /** 首屏加载提示文案。 */
  loadingHint: string;
  /** 空列表提示文案。 */
  emptyHint: string;
  /** 渲染单张卡片（视觉复用横排区块的 sp-artist-item / sp-album-item）。 */
  renderCard: (item: T) => ReactNode;
  /** 卡片容器类名：缺省 sp-cards（换行卡片流）；歌单/视频标签传各自的列表/网格容器。 */
  cardsClassName?: string;
}) {
  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false); // 第一页已尝试过（成功或失败）：防止重复自动加载

  const fetchRef = useRef(fetchPage); // 最新拉取函数：异步回调里经它调用，不闭包捕获旧值
  fetchRef.current = fetchPage;
  const itemsRef = useRef<T[]>([]); // 最新数据快照：翻页合并去重用
  const pageRef = useRef(0); // 已加载到的页码
  const busyRef = useRef(false); // 翻页请求进行中，防滚动事件重复触发
  const hasMoreRef = useRef(false); // hasMore 镜像：滚动闭包读最新值
  const genRef = useRef(0); // 代数计数：重试后旧响应作废
  const startedRef = useRef(false); // 首次激活已启动过拉取，避免 active 抖动引发重复自动加载

  /** 拉取一页：首屏整体替换，翻页按 keyOf 去重拼接。 */
  function loadPage(page: number) {
    const gen = ++genRef.current;
    const isFirst = page === 1;
    busyRef.current = true;
    if (isFirst) {
      itemsRef.current = [];
      setItems([]);
      setError("");
      setLoading(true);
    } else {
      setLoadingMore(true);
    }
    void (async () => {
      try {
        const r = await fetchRef.current(page);
        if (genRef.current !== gen) return; // 已有更新的加载，丢弃旧响应
        // 以请求前结果为快照，新页按身份 key 去重后拼接，避免分页重叠出现重复卡片
        const seen = new Set(itemsRef.current.map(keyOf));
        const merged = isFirst
          ? r.items
          : [...itemsRef.current, ...r.items.filter((it) => !seen.has(keyOf(it)))];
        itemsRef.current = merged;
        setItems(merged);
        setTotal(r.total);
        hasMoreRef.current = r.hasMore;
        setHasMore(r.hasMore);
        pageRef.current = page;
        setLoaded(true);
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
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

  // 卸载时作废在途响应
  useEffect(() => {
    return () => {
      genRef.current++;
    };
  }, []);

  /** 无限滚动：距底不足 300px 且允许加载时翻下一页；并发去抖由 busy 标记保证。 */
  function handleScroll(e: UIEvent<HTMLDivElement>) {
    if (busyRef.current || !hasMoreRef.current || loading || loadingMore) return;
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300) loadPage(pageRef.current + 1);
  }

  return (
    <div className="sp-pane-scroll" onScroll={handleScroll}>
      {loading && <p className="detail-center-hint">{loadingHint}</p>}
      {!loading && error !== "" && items.length === 0 && (
        <div className="detail-center-block">
          <p className="detail-center-hint err">{error}</p>
          <button type="button" className="detail-retry" onClick={() => loadPage(1)}>
            重试
          </button>
        </div>
      )}
      {!loading && error === "" && loaded && items.length === 0 && (
        <p className="detail-center-hint">{emptyHint}</p>
      )}
      {items.length > 0 && (
        <div className={cardsClassName}>
          {items.map((it) => (
            <Fragment key={keyOf(it)}>{renderCard(it)}</Fragment>
          ))}
        </div>
      )}
      {!loading && error !== "" && items.length > 0 && (
        <p className="songrow-more-err">{error}（可继续下滑重试）</p>
      )}
      {loadingMore && <p className="songrow-foot">正在加载更多…</p>}
      {!loadingMore && items.length > 0 && !hasMore && (
        <p className="songrow-foot">
          {total > items.length
            ? `已显示全部 ${items.length} ${noun}（共 ${total} ${noun}）`
            : `已显示全部 ${items.length} ${noun}`}
        </p>
      )}
    </div>
  );
}

/**
 * 歌单卡片：横版行（圆角封面 64px + 名字两行省略 + 「creator · N 首 · 播放 M 次」次要行，
 * 数字过万缩写）。在线歌单详情未接入，点击行为由 onOpen 决定（当前为 toast 提示）。
 */
function PlaylistCard({ playlist, onOpen }: { playlist: SearchPlaylist; onOpen: () => void }) {
  const subs: string[] = [];
  if (playlist.creator) subs.push(playlist.creator);
  if (playlist.trackCount != null) subs.push(`${playlist.trackCount} 首`);
  const plays = shortCount(playlist.playCount);
  if (plays) subs.push(`播放 ${plays} 次`);
  return (
    <div className="sp-pl-item" title={`在线歌单「${playlist.name}」`} onClick={onOpen}>
      <PlaylistCover pic={playlist.pic} />
      <div className="sp-pl-meta">
        <span className="sp-pl-name">{playlist.name}</span>
        {subs.length > 0 && <span className="sp-pl-sub">{subs.join(" · ")}</span>}
      </div>
    </div>
  );
}

/** 歌单封面：有图用图（失败就地回退），无图主题色块 + 音符，与专辑封面同一设计语言。 */
function PlaylistCover({ pic }: { pic?: string }) {
  const [failed, setFailed] = useState(false);
  const showImg = !!pic && !failed;
  return (
    <div className="sp-pl-cover">
      {showImg ? (
        <img src={absoluteUrl(pic)} alt="" onError={() => setFailed(true)} />
      ) : (
        <span className="note">♪</span>
      )}
    </div>
  );
}

/** 视频卡片中心的圆形播放角标。 */
function PlayBadge() {
  return (
    <span className="sp-vd-badge" aria-hidden="true">
      <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
        <path d="M8 5.5v13l11-6.5z" />
      </svg>
    </span>
  );
}

/**
 * 视频卡片：16:9 圆角封面 + 居中播放角标 + mvName（两行省略）+ artist 次要行。
 * 封面优先 mvCover（视频封面），缺失回退歌曲封面 cover。点击行为由 onOpen 决定
 * （桌面端暂无 MV 播放链路，当前为 toast 提示）。
 */
function VideoCard({ video, onOpen }: { video: SearchVideo; onOpen: () => void }) {
  const title = video.mvName || video.name;
  const [failed, setFailed] = useState(false);
  const showImg = !!video.cover && !failed;
  return (
    <div className="sp-vd-item" title={`播放 MV「${title}」`} onClick={onOpen}>
      <div className="sp-vd-cover">
        {showImg ? (
          <img src={absoluteUrl(video.cover)} alt="" onError={() => setFailed(true)} />
        ) : (
          <span className="note">♪</span>
        )}
        <PlayBadge />
      </div>
      <span className="sp-vd-name">{title}</span>
      {video.artist && <span className="sp-vd-artist">{video.artist}</span>}
    </div>
  );
}

/**
 * 歌词标签的分页列表：行结构是「标题 + 歌手·专辑 + 命中歌词摘要」。
 * SongList 没有摘要插槽且其行内含「⋯」菜单/收藏按钮，按约定不改 SongList，
 * 这里用轻量行复刻其播放口径：不可播放只提示，行点击 playList 整列入队。
 * 分页口径与 PagedCardFlow 完全一致：首次激活拉第一页、滚动近底翻页、
 * 按 songKeyOf 去重拼接、genRef 作废旧响应；新搜索由 key 重挂载整体重置。
 */
function LyricResultList({
  fetchPage,
  active,
}: {
  /** 按页码拉取一页歌词命中歌曲（调用方绑定关键词与接口）。 */
  fetchPage: (page: number) => Promise<{ items: Song[]; total: number; hasMore: boolean }>;
  /** 本标签是否处于激活态：首次激活才拉第一页。 */
  active: boolean;
}) {
  const { playList, toast } = useApp();
  const [items, setItems] = useState<Song[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false); // 第一页已尝试过（成功或失败）：防止重复自动加载

  const fetchRef = useRef(fetchPage); // 最新拉取函数：异步回调里经它调用，不闭包捕获旧值
  fetchRef.current = fetchPage;
  const itemsRef = useRef<Song[]>([]); // 最新数据快照：翻页合并去重用
  const pageRef = useRef(0); // 已加载到的页码
  const busyRef = useRef(false); // 翻页请求进行中，防滚动事件重复触发
  const hasMoreRef = useRef(false); // hasMore 镜像：滚动闭包读最新值
  const genRef = useRef(0); // 代数计数：翻页/重试后旧响应作废
  const startedRef = useRef(false); // 首次激活已启动过拉取，避免 active 抖动引发重复自动加载

  /** 拉取一页：首屏整体替换，翻页按 songKeyOf 去重拼接。 */
  function loadPage(page: number) {
    const gen = ++genRef.current;
    const isFirst = page === 1;
    busyRef.current = true;
    if (isFirst) {
      itemsRef.current = [];
      setItems([]);
      setError("");
      setLoading(true);
    } else {
      setLoadingMore(true);
    }
    void (async () => {
      try {
        const r = await fetchRef.current(page);
        if (genRef.current !== gen) return; // 已有更新的加载，丢弃旧响应
        const seen = new Set(itemsRef.current.map(songKeyOf));
        const merged = isFirst
          ? r.items
          : [...itemsRef.current, ...r.items.filter((s) => !seen.has(songKeyOf(s)))];
        itemsRef.current = merged;
        setItems(merged);
        setTotal(r.total);
        hasMoreRef.current = r.hasMore;
        setHasMore(r.hasMore);
        pageRef.current = page;
        setLoaded(true);
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
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

  // 首次切到本标签时拉第一页
  useEffect(() => {
    if (!active || startedRef.current) return;
    startedRef.current = true;
    loadPage(1);
  }, [active]);

  // 卸载时作废在途响应
  useEffect(() => {
    return () => {
      genRef.current++;
    };
  }, []);

  /** 无限滚动：距底不足 300px 且允许加载时翻下一页；并发去抖由 busy 标记保证。 */
  function handleScroll(e: UIEvent<HTMLDivElement>) {
    if (busyRef.current || !hasMoreRef.current || loading || loadingMore) return;
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 300) loadPage(pageRef.current + 1);
  }

  /** 行点击：与 SongList 同一口径——不可播放只提示，否则整列入队从该行播放。 */
  function handleRowClick(i: number, s: Song) {
    if (s.playable === false) {
      toast("该歌曲暂不可播放");
      return;
    }
    playList(items, i);
  }

  return (
    <div className="sp-pane-scroll" onScroll={handleScroll}>
      {loading && <p className="detail-center-hint">正在加载歌词…</p>}
      {!loading && error !== "" && items.length === 0 && (
        <div className="detail-center-block">
          <p className="detail-center-hint err">{error}</p>
          <button type="button" className="detail-retry" onClick={() => loadPage(1)}>
            重试
          </button>
        </div>
      )}
      {!loading && error === "" && loaded && items.length === 0 && (
        <p className="detail-center-hint">没有找到相关歌词，换个关键词再试试</p>
      )}
      {items.length > 0 && (
        <div className="sp-ly-list">
          {items.map((s, i) => (
            <div
              key={songKeyOf(s)}
              className={`sp-ly-row${s.playable === false ? " off" : ""}`}
              onClick={() => handleRowClick(i, s)}
            >
              <div className="sp-ly-title-line">
                <span className="sp-ly-title">{s.title}</span>
                {s.vip ? <span className="vip-badge">VIP</span> : null}
              </div>
              <span className="sp-ly-artist">{s.album ? `${s.artist} · ${s.album}` : s.artist}</span>
              {s.lyricSnippet && <p className="sp-ly-snippet">{s.lyricSnippet}</p>}
            </div>
          ))}
        </div>
      )}
      {!loading && error !== "" && items.length > 0 && (
        <p className="songrow-more-err">{error}（可继续下滑重试）</p>
      )}
      {loadingMore && <p className="songrow-foot">正在加载更多…</p>}
      {!loadingMore && items.length > 0 && !hasMore && (
        <p className="songrow-foot">
          {total > items.length
            ? `已显示全部 ${items.length} 首（共 ${total} 首）`
            : `已显示全部 ${items.length} 首`}
        </p>
      )}
    </div>
  );
}
