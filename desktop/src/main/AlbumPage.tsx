import { useEffect, useRef, useState } from "react";
import {
  absoluteUrl,
  fetchAlbumDetail,
  fetchAlbumSongs,
  readableError,
  SessionExpired,
  type AlbumDetail,
} from "../api";
import type { ArtistTarget } from "./ArtistPage";
import { PagedSongSection } from "./PagedSongSection";
import { hideOnError } from "./img";
import "./search.css";

/** 专辑页跳转目标：详情未到时用 name/pic/artist（搜索横排或歌手专辑卡传入）先渲染头部骨架。 */
export type AlbumTarget = {
  source: string;
  id: number;
  name?: string;
  pic?: string;
  artist?: string;
  artistId?: number;
};

/**
 * 专辑页：头部（160px 圆角封面 + 专辑名 + 可点歌手名 + 发行日期/歌曲数）+ 长简介 +
 * 分页歌曲列表（播放链路与搜索页一致）。歌手名可点击跳转歌手主页；返回键回搜索页。
 */
export function AlbumPage({
  target,
  onOpenArtist,
  onBack,
}: {
  target: AlbumTarget | null;
  onOpenArtist: (target: ArtistTarget) => void;
  onBack: () => void;
}) {
  // ---- 详情与简介 ----
  const [detail, setDetail] = useState<AlbumDetail | null>(null);
  const [detailError, setDetailError] = useState("");
  const [descExpanded, setDescExpanded] = useState(false);

  // 详情请求代数：切换专辑/重试后旧响应作废
  const detailGenRef = useRef(0);
  // 已加载的专辑身份键：同一专辑重复打开（target 对象变化但键相同）不重拉，保留已加载状态
  const loadedKeyRef = useRef<string | null>(null);

  /** 拉取专辑详情：成功替换骨架信息，失败记录错误并给重试。 */
  function loadDetail(t: AlbumTarget) {
    const gen = ++detailGenRef.current;
    setDetailError("");
    void (async () => {
      try {
        const d = await fetchAlbumDetail(t.id, t.source);
        if (detailGenRef.current !== gen) return;
        setDetail(d);
      } catch (e) {
        if (detailGenRef.current !== gen) return;
        if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一处理
        setDetailError(readableError(e));
      }
    })();
  }

  // 切换专辑：重置详情与简介状态；歌曲列表以 key 重挂载自行重置
  useEffect(() => {
    if (!target) return;
    const current = target; // 收窄成非空常量，供异步调用使用
    const key = `${current.source}:${current.id}`;
    if (loadedKeyRef.current === key) return;
    loadedKeyRef.current = key;
    setDetail(null);
    setDetailError("");
    setDescExpanded(false);
    void loadDetail(current);
    // 只按 target 身份重载：同一专辑的 target 对象变化由 loadedKeyRef 守卫拦截
  }, [target]);

  // ---- 派生展示值：详情未到时回退搜索传入的骨架信息 ----
  if (!target) return null;
  const t = target; // 收窄成非空常量，供闭包使用
  const albumKey = `${t.source}:${t.id}`;
  const name = detail?.name ?? t.name ?? "未知专辑";
  const pic = detail?.pic ?? t.pic;
  const artistName = detail?.artist ?? t.artist;
  const artistId = detail?.artistId ?? t.artistId;
  const subParts: string[] = [];
  if (detail?.showtime) subParts.push(`发行于 ${detail.showtime}`);
  if (detail?.songCount != null) subParts.push(`${detail.songCount} 首`);
  const desc = detail?.desc;

  return (
    <div className="al-root">
      <button type="button" className="detail-back" onClick={onBack}>
        ‹ 返回搜索
      </button>

      {/* 头部：详情未到时先用搜索传入的名字/封面/歌手渲染骨架 */}
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
          {artistName &&
            (artistId ? (
              <button
                type="button"
                className="al-artist-btn"
                title={`查看歌手「${artistName}」`}
                onClick={() => onOpenArtist({ source: t.source, id: artistId, name: artistName })}
              >
                {artistName}
              </button>
            ) : (
              <div className="al-artist">{artistName}</div>
            ))}
          {subParts.length > 0 && <div className="al-sub">{subParts.join(" · ")}</div>}
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

      {/* 长简介：默认收起，点击展开/收起 */}
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

      {/* 歌曲列表：滚动容器在 SongList 内部，播放行为与搜索页完全一致 */}
      <div className="al-songs">
        <PagedSongSection
          key={albumKey}
          resetKey={albumKey}
          fetchPage={(page) => fetchAlbumSongs(t.id, page, t.source)}
          emptyHint="该专辑暂无歌曲"
        />
      </div>
    </div>
  );
}
