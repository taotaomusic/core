import { useEffect, useRef, useState } from "react";
import {
  absoluteUrl,
  fetchRankingSongs,
  fetchRankings,
  readableError,
  SessionExpired,
  songKeyOf,
  type RankingBrief,
  type RankingGroup,
  type RankingInfo,
  type Song,
} from "../api";
import { hideOnError } from "./img";
import { SongList } from "./SongList";
// 榜单卡与详情头部复用搜索页/专辑页的全局类（sp-section-title / al-head / detail-*），
// 显式导入 search.css 保证样式自足
import "./search.css";

/**
 * 排行榜页：顶层导航页（与搜索页平级），内部两个视图显隐切换（与主界面页面切换同一哲学）：
 * - 目录视图：页头 + 按模块分组的小节（组内榜单卡横向滚动：封面 + 榜单名 + pubStr）；
 * - 详情视图：榜单头部（封面 + 名 + 更新日期 + 共 N 首）+ 歌曲列表（行首排名序号，
 *   分页加载、滚动近底自动翻页；整列入队与搜索页同一播放链路）。
 * 目录数据挂载时拉一次，失败给重试、保留旧数据；详情视图按榜单身份 key 重挂载，切榜整体重置，
 * 歌曲数据未回时先用目录传入的 brief 渲染头部骨架。
 */
export function RankingPage() {
  // ---- 目录视图状态 ----
  const [groups, setGroups] = useState<RankingGroup[]>([]);
  const [dirLoading, setDirLoading] = useState(true);
  const [dirError, setDirError] = useState("");
  // 当前查看的榜单：null 表示在目录视图；点卡片设置，返回键清空
  const [brief, setBrief] = useState<RankingBrief | null>(null);

  const dirGenRef = useRef(0); // 目录拉取代数：重试后旧响应作废
  const busyRef = useRef(false); // 拉取进行中：重试重复点击防抖

  /** 拉取榜单目录：成功整体替换分组，失败记录错误给重试（旧数据保留不闪空）。 */
  function loadDirectory() {
    if (busyRef.current) return;
    busyRef.current = true;
    const gen = ++dirGenRef.current;
    setDirError("");
    setDirLoading(true);
    void (async () => {
      try {
        const gs = await fetchRankings();
        if (dirGenRef.current !== gen) return;
        setGroups(gs);
      } catch (e) {
        if (dirGenRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
        setDirError(readableError(e));
      } finally {
        if (dirGenRef.current === gen) {
          busyRef.current = false;
          setDirLoading(false);
        }
      }
    })();
  }

  // 挂载即拉目录（页面常驻挂载，切走再切回不重复触发）
  useEffect(() => {
    loadDirectory();
    return () => {
      dirGenRef.current++; // 卸载时作废在途响应
    };
  }, []);

  return (
    <div className="rk-root">
      {/* 目录视图：打开详情时隐藏，分组数据与滚动位置保留 */}
      <div className="rk-pane" style={{ display: brief ? "none" : "flex" }}>
        <div className="rk-head">
          <div className="rk-head-text">
            <div className="rk-title">排行榜</div>
            <div className="rk-sub">官方榜单 · 点击卡片查看完整歌曲列表</div>
          </div>
        </div>
        {/* 目录拉取失败：页头下的重试行（重试成功前保留已加载的旧分组） */}
        {dirError !== "" && (
          <div className="rk-error">
            <span className="rk-error-msg">{dirError}</span>
            <button type="button" className="rk-retry" onClick={loadDirectory}>
              重试
            </button>
          </div>
        )}
        <div className="rk-scroll">
          {dirLoading && groups.length === 0 && <CatalogSkeleton />}
          {!dirLoading && dirError === "" && groups.length === 0 && (
            <p className="hint-center">暂无榜单</p>
          )}
          {groups.map((g, gi) => (
            <div key={`${g.moduleName}:${gi}`} className="rk-section">
              <div className="sp-section-title">{g.moduleName}</div>
              <div className="rk-bangs">
                {g.bangs.map((b) => (
                  <div
                    key={`${b.source}:${b.id}`}
                    className="rk-bang-item"
                    title={`查看榜单「${b.name}」`}
                    onClick={() => setBrief(b)}
                  >
                    <div className="rk-bang-cover">
                      <span className="note">♪</span>
                      {/* 封面地址过 absoluteUrl 归一：相对路径补 API 域名，避免 webview 裂图 */}
                      {b.pic ? <img src={absoluteUrl(b.pic)} alt="" onError={hideOnError} /> : null}
                    </div>
                    <span className="rk-bang-name">{b.name}</span>
                    {b.pubStr && <span className="rk-bang-pub">{b.pubStr}</span>}
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>
      {/* 详情视图：按榜单身份 key 重挂载，换榜整体重置；brief 先兜底头部 */}
      {brief && (
        <RankingDetail key={`${brief.source}:${brief.id}`} brief={brief} onBack={() => setBrief(null)} />
      )}
    </div>
  );
}

/** 详情分页每页条数：服务端缺省 30、上限 100（主流榜单真实条数恒 100，约 4 页拉完）。 */
const DETAIL_PAGE_NUM = 30;

/**
 * 榜单详情视图：头部（160px 圆角封面 + 榜单名 + 更新日期/共 N 首）+ 歌曲列表。
 * 歌曲分页加载：挂载拉第一页，滚动近底部自动翻页，口径与 PagedSongSection 一致
 * （genRef 代数作废旧响应、busy 防滚动重入、新页按歌曲身份 key 去重拼接、hasMore 收口）；
 * 每行行首渲染全局排名序号（前三名金/银/铜，其余次要灰）。
 * 请求未回时用目录传入的 brief 渲染头部骨架，失败给重试（头部骨架保留可看）；
 * 空榜单提示「该榜单暂无歌曲」。
 * 播放链路与搜索页一致：SongList 缺省把**当前已加载的累积列表**整列设为队列并从点击行播放
 * （分页追加场景下队列上下文即累积数组，后续翻页不影响已入队内容）。
 */
function RankingDetail({ brief, onBack }: { brief: RankingBrief; onBack: () => void }) {
  const [info, setInfo] = useState<RankingInfo | null>(null);
  const [songs, setSongs] = useState<Song[]>([]);
  const [total, setTotal] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState("");

  const songsRef = useRef<Song[]>([]); // 已加载累积快照：翻页合并去重用
  const pageRef = useRef(0); // 已加载到的页码
  const busyRef = useRef(false); // 翻页请求进行中，防滚动事件重复触发
  const hasMoreRef = useRef(false); // hasMore 镜像：loadMore 闭包读最新值
  const genRef = useRef(0); // 拉取代数：重试/卸载后旧响应作废

  /** 拉取一页：首屏整体替换，翻页以请求前结果为快照去重拼接（与 PagedSongSection 同口径）。 */
  function loadPage(page: number) {
    const gen = ++genRef.current;
    const isFirst = page === 1;
    busyRef.current = true;
    if (isFirst) {
      songsRef.current = [];
      setSongs([]);
      setError("");
      setLoading(true);
    } else {
      setLoadingMore(true);
    }
    void (async () => {
      try {
        const r = await fetchRankingSongs(brief.id, 4, page, DETAIL_PAGE_NUM);
        if (genRef.current !== gen) return; // 已重试或卸载，丢弃旧响应
        setInfo(r.ranking);
        // 新页按列表身份 key 去重后拼接，避免分页重叠出现重复行
        const seen = new Set(songsRef.current.map(songKeyOf));
        const merged = isFirst
          ? r.songs
          : [...songsRef.current, ...r.songs.filter((s) => !seen.has(songKeyOf(s)))];
        songsRef.current = merged;
        setSongs(merged);
        setTotal(r.total);
        hasMoreRef.current = r.hasMore;
        setHasMore(r.hasMore);
        pageRef.current = page;
        setError("");
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
        setError(readableError(e));
      } finally {
        if (genRef.current === gen) {
          busyRef.current = false;
          if (isFirst) setLoading(false);
          else setLoadingMore(false);
        }
      }
    })();
  }

  // 挂载即拉第一页；卸载时作废在途响应
  useEffect(() => {
    loadPage(1);
    return () => {
      genRef.current++;
    };
  }, []);

  /** 无限滚动（SongList 距底不足 300px 触发）：翻下一页；并发去抖由 busy 标记保证。 */
  function loadMore() {
    if (busyRef.current || !hasMoreRef.current) return;
    loadPage(pageRef.current + 1);
  }

  /**
   * 行首排名序号：服务端条目顺序即排名顺序（第 1 名在最前）；逐页顺序拼接下
   * 累积下标 + 1 就等于 (page-1)*num + 页内 index + 1，即全局名次。
   */
  function rankAt(index: number): number {
    return index + 1;
  }

  // ---- 派生展示值：数据未到回退 brief 兜底 ----
  // 更新日期：详情 pub 到了拼「更新于 <日期>」；缺席退回目录的 pubStr（自带「更新」字样，直接展示）
  const name = info?.name ?? brief.name;
  const pic = info?.pic ?? brief.pic;
  const updated = info?.pub ? `更新于 ${info.pub}` : brief.pubStr ?? "";
  // 头部副行：更新日期 + 「共 N 首」；total 取自分页 meta（真实条数），不是 ranking.total（上游展示值）
  const subBits = [updated, total > 0 ? `共 ${total} 首` : ""].filter((t) => t !== "");

  return (
    <div className="rk-detail">
      <button type="button" className="detail-back" onClick={onBack}>
        ‹ 返回排行榜
      </button>

      {/* 头部：数据未到先用目录传入的名字/封面/更新串渲染骨架（复用专辑页头部样式） */}
      <div className="al-head">
        <div className="al-cover">
          <span className="note">♪</span>
          {/* 封面地址过 absoluteUrl 归一：相对路径补 API 域名，避免 webview 裂图 */}
          {pic ? <img src={absoluteUrl(pic)} alt="" onError={hideOnError} /> : null}
        </div>
        <div className="al-info">
          <div className="al-name" title={name}>
            {name}
          </div>
          {subBits.length > 0 && <div className="al-sub">{subBits.join(" · ")}</div>}
        </div>
      </div>

      {/* 歌曲列表：加载中给骨架、失败给重试（错误块替代列表）、空榜单给提示；
          滚动容器在 SongList 内部，翻页页脚与收口提示也由它按 hasMore/total 渲染 */}
      <div className="rk-songs">
        {(!error || songs.length > 0) && (
          <SongList
            songs={songs}
            loading={loading}
            error={error}
            emptyHint="该榜单暂无歌曲"
            hasMore={hasMore}
            total={total}
            onLoadMore={loadMore}
            loadingMore={loadingMore}
            rankOf={rankAt}
          />
        )}
        {!loading && error !== "" && songs.length === 0 && (
          <div className="detail-center-block">
            <p className="detail-center-hint err">{error}</p>
            <button type="button" className="detail-retry" onClick={() => loadPage(1)}>
              重试
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

/** 目录加载骨架：两组「小节标题条 + 一排卡片」占位，复用搜索骨架的渐变动画（sp-shimmer）。 */
function CatalogSkeleton() {
  return (
    <>
      {Array.from({ length: 2 }, (_, gi) => (
        <div key={gi} className="rk-section">
          <div className="rk-skel-title" />
          <div className="rk-bangs">
            {Array.from({ length: 6 }, (_, bi) => (
              <div key={bi} className="rk-skel-card">
                <div className="rk-skel-cover" />
                <div className="rk-skel-text" />
              </div>
            ))}
          </div>
        </div>
      ))}
    </>
  );
}
