import { useEffect, useRef, useState } from "react";
import brandIcon from "../../src-tauri/icons/icon.png";
import { AlbumPage, type AlbumTarget } from "./AlbumPage";
import { ArtistPage, type ArtistTarget } from "./ArtistPage";
import { FavoritesPage } from "./FavoritesPage";
import { PlayerBar } from "./PlayerBar";
import { PlayerDetail } from "./PlayerDetail";
import { PlaylistsPage } from "./PlaylistsPage";
import { RecentPage } from "./RecentPage";
import { SearchPage } from "./SearchPage";

type Page = "search" | "playlists" | "recent" | "favorites" | "artist" | "album";

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
 * 歌手主页 / 专辑页是从搜索页进入的二级页面（同属「搜索」导航项）：不设侧边栏按钮，
 * 由搜索横排或播放详情页的可点歌手名打开并携带目标身份，页内「返回」键回到搜索页；歌手页 ↔ 专辑页可互相跳转。
 */
export function MainScreen({ onLogout }: { onLogout: () => void }) {
  const [page, setPage] = useState<Page>("search");
  // 歌手主页 / 专辑页当前展示的目标：null 表示尚未打开过（页面隐藏为空壳）
  const [artistTarget, setArtistTarget] = useState<ArtistTarget | null>(null);
  const [albumTarget, setAlbumTarget] = useState<AlbumTarget | null>(null);
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

  /** 打开歌手主页：携带搜索横排/专辑页/播放详情页传入的目标身份；已在歌手页时仅换目标不重播动画。 */
  function openArtist(target: ArtistTarget) {
    setArtistTarget(target);
    switchPage("artist");
  }

  /** 打开专辑页：同上。 */
  function openAlbum(target: AlbumTarget) {
    setAlbumTarget(target);
    switchPage("album");
  }

  /** 从歌手/专辑页返回搜索页。 */
  function backToSearch() {
    switchPage("search");
  }

  // 侧边栏高亮：歌手/专辑页是搜索页的二级页面，归入「搜索」项
  const navActive: Page = page === "artist" || page === "album" ? "search" : page;

  return (
    <div className="app">
      <aside className="sidenav">
        <div className="sidenav-brand">
          <img src={brandIcon} alt="桃桃音乐" />
          <span className="word">桃桃音乐</span>
        </div>
        <button
          className={`nav-btn${navActive === "search" ? " on" : ""}`}
          onClick={() => switchPage("search")}
        >
          <NavIcon kind="search" />
          搜索
        </button>
        <button
          className={`nav-btn${navActive === "playlists" ? " on" : ""}`}
          onClick={() => switchPage("playlists")}
        >
          <NavIcon kind="playlists" />
          歌单
        </button>
        <button
          className={`nav-btn${navActive === "recent" ? " on" : ""}`}
          onClick={() => switchPage("recent")}
        >
          <NavIcon kind="recent" />
          最近播放
        </button>
        <button
          className={`nav-btn${navActive === "favorites" ? " on" : ""}`}
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
          <SearchPage onOpenArtist={openArtist} onOpenAlbum={openAlbum} />
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
        {/* 歌手主页 / 专辑页：从搜索页（或互相）进入的二级页面，同样常驻挂载保留状态 */}
        <div
          className={`page${page === "artist" ? " page-enter" : ""}${exiting === "artist" ? " page-exit" : ""}`}
          style={{ display: page === "artist" || exiting === "artist" ? "flex" : "none" }}
        >
          <ArtistPage
            target={artistTarget}
            onOpenArtist={openArtist}
            onOpenAlbum={openAlbum}
            onBack={backToSearch}
          />
        </div>
        <div
          className={`page${page === "album" ? " page-enter" : ""}${exiting === "album" ? " page-exit" : ""}`}
          style={{ display: page === "album" || exiting === "album" ? "flex" : "none" }}
        >
          <AlbumPage target={albumTarget} onOpenArtist={openArtist} onBack={backToSearch} />
        </div>
      </main>
      <PlayerBar />
      <PlayerDetail onOpenArtist={openArtist} />
    </div>
  );
}
