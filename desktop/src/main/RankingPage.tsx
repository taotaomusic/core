import { useEffect, useRef, useState } from "react";
import {
  absoluteUrl,
  fetchRankingSongs,
  fetchRankings,
  readableError,
  SessionExpired,
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
 * - 详情视图：榜单头部（封面 + 名 + 更新日期）+ 歌曲列表（整列入队，与搜索页同一播放链路）。
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

/**
 * 榜单详情视图：头部（160px 圆角封面 + 榜单名 + 更新日期）+ 歌曲列表。
 * 挂载即拉一次榜单歌曲（固定约 20 首、无分页）；请求未回时用目录传入的 brief 渲染头部骨架，
 * 失败给重试（头部骨架保留可看）；空榜单提示「该榜单暂无歌曲」。
 * 播放链路与搜索页一致（SongList 缺省把整列设为队列并从点击行播放）。
 */
function RankingDetail({ brief, onBack }: { brief: RankingBrief; onBack: () => void }) {
  const [info, setInfo] = useState<RankingInfo | null>(null);
  const [songs, setSongs] = useState<Song[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const genRef = useRef(0); // 拉取代数：重试后旧响应作废

  /** 拉取榜单歌曲：成功替换头部与列表，失败记录错误给重试。 */
  function load() {
    const gen = ++genRef.current;
    setError("");
    setLoading(true);
    void (async () => {
      try {
        const r = await fetchRankingSongs(brief.id);
        if (genRef.current !== gen) return;
        setInfo(r.ranking);
        setSongs(r.songs);
      } catch (e) {
        if (genRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
        setError(readableError(e));
      } finally {
        if (genRef.current === gen) setLoading(false);
      }
    })();
  }

  // 挂载即拉取；卸载时作废在途响应
  useEffect(() => {
    load();
    return () => {
      genRef.current++;
    };
  }, []);

  // ---- 派生展示值：数据未到回退 brief 兜底 ----
  // 更新日期：详情 pub 到了拼「更新于 <日期>」；缺席退回目录的 pubStr（自带「更新」字样，直接展示）
  const name = info?.name ?? brief.name;
  const pic = info?.pic ?? brief.pic;
  const updated = info?.pub ? `更新于 ${info.pub}` : brief.pubStr ?? "";

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
          {updated && <div className="al-sub">{updated}</div>}
        </div>
      </div>

      {/* 歌曲列表：加载中给骨架、失败给重试（错误块替代列表）、空榜单给提示；滚动容器在 SongList 内部 */}
      <div className="rk-songs">
        {(!error || songs.length > 0) && (
          <SongList songs={songs} loading={loading} error={error} emptyHint="该榜单暂无歌曲" />
        )}
        {!loading && error !== "" && songs.length === 0 && (
          <div className="detail-center-block">
            <p className="detail-center-hint err">{error}</p>
            <button type="button" className="detail-retry" onClick={load}>
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
