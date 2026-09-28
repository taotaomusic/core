import { useState } from "react";

/** 顶部搜索栏：回车触发搜索。 */
export function SearchBar({ onSearch, onLogout }: { onSearch: (kw: string) => void; onLogout: () => void }) {
  const [query, setQuery] = useState("");
  return (
    <div className="topbar">
      <span className="title">桃桃音乐</span>
      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && query.trim() && onSearch(query.trim())}
        placeholder="搜索歌曲 / 歌手"
      />
      <button className="link" onClick={onLogout}>退出</button>
    </div>
  );
}
