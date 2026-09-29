import { useEffect, useRef, useState } from "react";
import {
  fetchHotSearches,
  fetchSuggestions,
  readableError,
  searchSongs,
  SessionExpired,
  songKeyOf,
  type Song,
} from "../api";
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
 * 已搜索 → 结果列表（骨架屏 / 错误 / 空态 / 无限滚动）。
 * 搜索历史只存本机不上传；会话过期由 AppState 层统一处理。
 */
export function SearchPage() {
  const [keyword, setKeyword] = useState("");
  const [hasSearched, setHasSearched] = useState(false);
  const [history, setHistory] = useState<string[]>(() => loadHistory());
  const [historyPaused, setHistoryPaused] = useState(() => isPaused());
  const [confirmingClear, setConfirmingClear] = useState(false);
  const [hot, setHot] = useState<string[]>([]);
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const [songs, setSongs] = useState<Song[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");

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
    setHasMore(false);
    setTotal(0);
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
          <SongList
            songs={songs}
            loading={loading}
            error={error}
            emptyHint="没有找到相关歌曲，换个关键词再试试"
            hasMore={hasMore}
            total={total}
            onLoadMore={loadMore}
            loadingMore={loadingMore}
          />
        )}
      </div>
    </div>
  );
}
