import { Logo } from "./Logo";

/** 页脚：品牌语 + 三条链接（GitHub / 管理入口 / 服务状态），均为站内可达的真实地址。 */
export function Footer() {
  return (
    <footer className="site-footer">
      <div className="container footer-inner">
        <div className="footer-brand">
          <Logo size={22} />
          <span>桃桃音乐 · 让喜欢的歌随时在耳边</span>
        </div>
        <nav className="footer-links" aria-label="页脚链接">
          <a href="https://github.com/taotaomusic/core" target="_blank" rel="noreferrer">
            GitHub
          </a>
          <a href="/admin">管理入口</a>
          <a href="/health">服务状态</a>
        </nav>
        <span className="footer-copy">© 2026 桃桃音乐</span>
      </div>
    </footer>
  );
}
