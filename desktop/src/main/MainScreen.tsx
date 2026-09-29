import { useState } from "react";
import { FavoritesPage } from "./FavoritesPage";
import { PlayerBar } from "./PlayerBar";
import { PlayerDetail } from "./PlayerDetail";
import { SearchPage } from "./SearchPage";

type Page = "search" | "favorites";

/**
 * 登录后的主界面：左侧导航（搜索 / 我的收藏）+ 内容区 + 底部播放条 + 全屏播放详情。
 * 两个页面常驻挂载、用 CSS 隐藏切换，保留各自的搜索结果与滚动位置。
 */
export function MainScreen({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("search");
  return (
    <div className="app">
      <aside className="sidenav">
        <div className="sidenav-brand">♪ 桃桃音乐</div>
        <button
          className={`nav-btn${page === "search" ? " on" : ""}`}
          onClick={() => setPage("search")}
        >
          搜索
        </button>
        <button
          className={`nav-btn${page === "favorites" ? " on" : ""}`}
          onClick={() => setPage("favorites")}
        >
          我的收藏
        </button>
        <div className="sidenav-spacer" />
        <button className="nav-btn exit" onClick={onLogout}>
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
