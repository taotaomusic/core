import { useEffect, useRef, useState } from "react";
import brandIcon from "../../src-tauri/icons/icon.png";
import { FavoritesPage } from "./FavoritesPage";
import { PlayerBar } from "./PlayerBar";
import { PlayerDetail } from "./PlayerDetail";
import { PlaylistsPage } from "./PlaylistsPage";
import { RecentPage } from "./RecentPage";
import { SearchPage } from "./SearchPage";

type Page = "search" | "playlists" | "recent" | "favorites";

/** 页面退场动画时长（毫秒），与 App.css 里 .page-exit 的 0.15s 对齐。 */
const PAGE_EXIT_MS = 150;

/** 导航小图标：16px 线性风格，随文字颜色走（currentColor）。 */
function NavIcon({ kind }: { kind: "search" | "playlists" | "recent" | "heart" | "logout" }) {
  const common = {
    width: 16,
    height: 16,
    viewBox: "0 0 24 24",
    fill: "none",
    stroke: "currentColor",
    strokeWidth: 2,
    strokeLinecap: "round" as const,
    strokeLinejoin: "round" as const,
    "aria-hidden": true,
  };
  if (kind === "search") {
    return (
      <svg {...common}>
        <circle cx="11" cy="11" r="7" />
        <line x1="16.5" y1="16.5" x2="21" y2="21" />
      </svg>
    );
  }
  if (kind === "playlists") {
    return (
      <svg {...common}>
        <line x1="9" y1="6" x2="21" y2="6" />
        <line x1="9" y1="12" x2="21" y2="12" />
        <line x1="9" y1="18" x2="21" y2="18" />
        <line x1="4" y1="6" x2="4.01" y2="6" />
        <line x1="4" y1="12" x2="4.01" y2="12" />
        <line x1="4" y1="18" x2="4.01" y2="18" />
      </svg>
    );
  }
  if (kind === "recent") {
    return (
      <svg {...common}>
        <circle cx="12" cy="12" r="9" />
        <polyline points="12 7 12 12 15.5 14" />
      </svg>
    );
  }
  if (kind === "heart") {
    return (
      <svg {...common}>
        <path d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.6l-1-1a5.5 5.5 0 0 0-7.8 7.8l1 1L12 21.2l7.8-7.8 1-1a5.5 5.5 0 0 0 0-7.8z" />
      </svg>
    );
  }
  return (
    <svg {...common}>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <polyline points="16 17 21 12 16 7" />
      <line x1="21" y1="12" x2="9" y2="12" />
    </svg>
  );
}

/**
 * 登录后的主界面：左侧导航（搜索 / 歌单 / 最近播放 / 我的收藏）+ 内容区 + 底部播放条 + 全屏播放详情。
 * 各页面常驻挂载、用 CSS 隐藏切换，保留各自的搜索结果与滚动位置。
 */
export function MainScreen({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("search");
  // 正在退场的页面：切换时旧页短暂保留播退场动画，到点后归位隐藏（页面始终常驻挂载）
  const [exiting, setExiting] = useState<Page | null>(null);
  // 退场计时器句柄：快速连点时先清旧的再设新的，exiting 直接被新值覆盖，不排队
  const exitTimerRef = useRef<number | null>(null);

  // 卸载时清掉退场计时器，避免定时器泄漏
  useEffect(() => {
    return () => {
      if (exitTimerRef.current !== null) window.clearTimeout(exitTimerRef.current);
    };
  }, []);

  /** 切换页面：旧页进入退场动画，新页播进场动画；连点时旧退场被截断、新退场接管。 */
  function switchPage(next: Page) {
    if (next === page) return;
    if (exitTimerRef.current !== null) window.clearTimeout(exitTimerRef.current);
    setExiting(page);
    setPage(next);
    exitTimerRef.current = window.setTimeout(() => {
      exitTimerRef.current = null;
      setExiting(null);
    }, PAGE_EXIT_MS);
  }

  return (
    <div className="app">
      <aside className="sidenav">
        <div className="sidenav-brand">
          <img src={brandIcon} alt="桃桃音乐" />
          <span className="word">桃桃音乐</span>
        </div>
        <button
          className={`nav-btn${page === "search" ? " on" : ""}`}
          onClick={() => switchPage("search")}
        >
          <NavIcon kind="search" />
          搜索
        </button>
        <button
          className={`nav-btn${page === "playlists" ? " on" : ""}`}
          onClick={() => switchPage("playlists")}
        >
          <NavIcon kind="playlists" />
          歌单
        </button>
        <button
          className={`nav-btn${page === "recent" ? " on" : ""}`}
          onClick={() => switchPage("recent")}
        >
          <NavIcon kind="recent" />
          最近播放
        </button>
        <button
          className={`nav-btn${page === "favorites" ? " on" : ""}`}
          onClick={() => switchPage("favorites")}
        >
          <NavIcon kind="heart" />
          我的收藏
        </button>
        <div className="sidenav-spacer" />
        <button className="nav-btn exit" onClick={onLogout}>
          <NavIcon kind="logout" />
          退出登录
        </button>
      </aside>
      <main className="content">
        {/* 各页面常驻挂载：当前页加 page-enter 进场动画，退场页短暂保留加 page-exit，其余隐藏 */}
        <div
          className={`page${page === "search" ? " page-enter" : ""}${exiting === "search" ? " page-exit" : ""}`}
          style={{ display: page === "search" || exiting === "search" ? "flex" : "none" }}
        >
          <SearchPage />
        </div>
        <div
          className={`page${page === "playlists" ? " page-enter" : ""}${exiting === "playlists" ? " page-exit" : ""}`}
          style={{ display: page === "playlists" || exiting === "playlists" ? "flex" : "none" }}
        >
          <PlaylistsPage />
        </div>
        <div
          className={`page${page === "recent" ? " page-enter" : ""}${exiting === "recent" ? " page-exit" : ""}`}
          style={{ display: page === "recent" || exiting === "recent" ? "flex" : "none" }}
        >
          <RecentPage />
        </div>
        <div
          className={`page${page === "favorites" ? " page-enter" : ""}${exiting === "favorites" ? " page-exit" : ""}`}
          style={{ display: page === "favorites" || exiting === "favorites" ? "flex" : "none" }}
        >
          <FavoritesPage />
        </div>
      </main>
      <PlayerBar />
      <PlayerDetail />
    </div>
  );
}
