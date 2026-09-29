import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchRecentPlays,
  fetchSongInfos,
  readableError,
  SessionExpired,
  type RecentPlayEntry,
  type Song,
} from "../api";
import { SongList } from "./SongList";
import "./recent.css";

/** 回填完成后的列表项：歌曲元数据 + 参与排序/统计的播放记录字段。 */
type RecentItem = {
  song: Song;
  /** 最后播放时间戳（毫秒），列表按它降序 */
  last: number;
  /** 累计播放次数，统计行求和用 */
  plays: number;
};

/**
 * 最近播放页：/playback/recent 只返回身份与统计，歌曲元数据按 source 分组
 * 调 batch-info 补全（与 AppState.refreshFavorites 同一套双键回填写法）。
 * 未命中元数据的条目直接丢弃，不显示占位行。
 * 服务端另有 DELETE /playback/recent 可清空历史，本期不做 UI 入口。
 */
export function RecentPage() {
  const [songs, setSongs] = useState<Song[]>([]);
  const [totalPlays, setTotalPlays] = useState(0);
  const [loading, setLoading] = useState(true); // 挂载即拉取，初始为加载中
  const [error, setError] = useState("");

  const genRef = useRef(0); // 拉取代数：页面被 CSS 隐藏仍保持挂载，旧响应不得覆盖新结果
  const busyRef = useRef(false); // 拉取进行中：刷新/重试重复点击防抖

  /**
   * 拉取最近播放并回填元数据：
   * 1. fetchRecentPlays(500) 拿身份 + 统计（服务端已按最后播放时间降序）；
   * 2. 按 source 分组，数字 songId 收进 ids、其余视为 mid，每批合计 ≤60 调 batch-info；
   * 3. 返回的歌按 `${source}:${id}` 与 `${source}:${mid}` 双键登记，再回填到条目上。
   * 刷新不清空旧列表（骨架屏只在首屏出现），失败保留旧数据并在页头下显示重试行。
   */
  const load = useCallback(async () => {
    if (busyRef.current) return;
    busyRef.current = true;
    const gen = ++genRef.current;
    setLoading(true);
    setError("");
    try {
      const entries = await fetchRecentPlays(500);
      if (genRef.current !== gen) return;

      // 防御性去重：服务端理论上已按 (source, songId) 聚合，万一重复保留最近播放的一条
      const seen = new Map<string, RecentPlayEntry>();
      for (const e of entries) {
        if (!e.songId) continue;
        const key = `${e.source}:${e.songId}`;
        const prev = seen.get(key);
        if (!prev || e.lastPlayedAt > prev.lastPlayedAt) seen.set(key, e);
      }

      // 按 source 分组：数字 songId 走 ids 参数，其余本身就是 mid（酷我部分歌只有 mid）
      const groups = new Map<string, RecentPlayEntry[]>();
      for (const e of seen.values()) {
        const list = groups.get(e.source);
        if (list) list.push(e);
        else groups.set(e.source, [e]);
      }

      const byKey = new Map<string, Song>();
      for (const [source, list] of groups) {
        const merged: Array<{ kind: "id" | "mid"; value: string }> = [
          ...list
            .filter((e) => /^\d+$/.test(e.songId))
            .map((e) => ({ kind: "id" as const, value: e.songId })),
          ...list
            .filter((e) => !/^\d+$/.test(e.songId))
            .map((e) => ({ kind: "mid" as const, value: e.songId })),
        ];
        // batch-info 单次 ids+mids 合计不得超过 60，超出分批
        for (let start = 0; start < merged.length; start += 60) {
          if (genRef.current !== gen) return; // 已有更新的拉取，放弃过期批次
          const batch = merged.slice(start, start + 60);
          const infos = await fetchSongInfos(
            source,
            batch.filter((b) => b.kind === "id").map((b) => b.value),
            batch.filter((b) => b.kind === "mid").map((b) => b.value),
          );
          for (const info of infos) {
            // 两种身份都登记，兼容服务端按 id 或 mid 返回（与 refreshFavorites 同口径）
            if (info.id > 0) byKey.set(`${source}:${info.id}`, info);
            if (info.mid) byKey.set(`${source}:${info.mid}`, info);
          }
        }
      }

      // 合成列表：未命中元数据的条目丢弃；命中后按最后播放时间降序
      const filled: RecentItem[] = [];
      for (const e of seen.values()) {
        const info = byKey.get(`${e.source}:${e.songId}`);
        if (!info) continue;
        filled.push({ song: { ...info, remoteId: e.songId }, last: e.lastPlayedAt, plays: e.playCount });
      }
      filled.sort((a, b) => (b.last || 0) - (a.last || 0));

      if (genRef.current !== gen) return;
      setSongs(filled.map((f) => f.song));
      // 统计口径：播放次数只对最终展示（命中元数据）的条目求和，与「共 N 首」一致
      setTotalPlays(filled.reduce((sum, f) => sum + (f.plays || 0), 0));
    } catch (e) {
      if (e instanceof SessionExpired) return; // 会话过期由 AppState 层统一登出，这里静默
      if (genRef.current !== gen) return;
      setError(readableError(e));
    } finally {
      if (genRef.current === gen) {
        busyRef.current = false;
        setLoading(false);
      }
    }
  }, []);

  // 挂载时拉取一次；页面被 CSS 隐藏仍保持挂载，切换回来不重复触发
  useEffect(() => {
    void load();
  }, [load]);

  const showError = !loading && error !== "";
  // 错误且没有任何列表数据时，错误重试行整体替代列表（避免空态提示与错误并存）
  const showErrorOnly = showError && songs.length === 0;

  return (
    <div className="rc-root">
      <div className="rc-head">
        <div className="rc-head-text">
          <div className="rc-title">最近播放</div>
          <div className="rc-sub">听满 3 秒的歌曲会自动记录</div>
          {!loading && error === "" && songs.length > 0 && (
            <div className="rc-stats">
              共 {songs.length} 首 · 播放 {totalPlays} 次
            </div>
          )}
        </div>
        <button type="button" className="link" disabled={loading} onClick={() => void load()}>
          刷新
        </button>
      </div>

      {showError && (
        <div className="rc-error">
          <span className="rc-error-msg">{error}</span>
          <button type="button" className="rc-retry" onClick={() => void load()}>
            重试
          </button>
        </div>
      )}

      {/* 错误统一由上方重试行展示；error 固定传空，避免 SongList 底部再重复一行错误 */}
      {!showErrorOnly && (
        <SongList
          songs={songs}
          loading={loading}
          error=""
          emptyHint="还没有播放记录，去搜首歌听听吧"
        />
      )}
    </div>
  );
}
