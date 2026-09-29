import { useState } from "react";
import brandIcon from "../../src-tauri/icons/icon.png";
import { FavoritesPage } from "./FavoritesPage";
import { PlayerBar } from "./PlayerBar";
import { PlayerDetail } from "./PlayerDetail";
import { SearchPage } from "./SearchPage";

type Page = "search" | "favorites";

/** 导航小图标：16px 线性风格，随文字颜色走（currentColor）。 */
function NavIcon({ kind }: { kind: "search" | "heart" | "logout" }) {
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
 * 登录后的主界面：左侧导航（搜索 / 我的收藏）+ 内容区 + 底部播放条 + 全屏播放详情。
 * 两个页面常驻挂载、用 CSS 隐藏切换，保留各自的搜索结果与滚动位置。
 */
export function MainScreen({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("search");
  return (
    <div className="app">
      <aside className="sidenav">
        <div className="sidenav-brand">
          <img src={brandIcon} alt="桃桃音乐" />
          <span className="word">桃桃音乐</span>
        </div>
        <button
          className={`nav-btn${page === "search" ? " on" : ""}`}
          onClick={() => setPage("search")}
        >
          <NavIcon kind="search" />
          搜索
        </button>
        <button
          className={`nav-btn${page === "favorites" ? " on" : ""}`}
          onClick={() => setPage("favorites")}
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
        <div className="page" style={{ display: page === "search" ? "flex" : "none" }}>
          <SearchPage />
        </div>
        <div className="page" style={{ display: page === "favorites" ? "flex" : "none" }}>
          <FavoritesPage />
        </div>
      </main>
      <PlayerBar />
      <PlayerDetail />
    </div>
  );
}
