/**
 * 首屏：左侧文案 + 下载入口，右侧纯 CSS 绘制的播放器卡片（装饰用，对读屏隐藏）。
 * 所有下载链接指向 core 仓库的滚动 Release tag —— 那边的产物文件名带版本号，
 * 静态页面没法预知，只能落到 Release 页让用户点最新资产。
 */
const RELEASE_ANDROID = "https://github.com/taotaomusic/core/releases/tag/latest";
const RELEASE_DESKTOP = "https://github.com/taotaomusic/core/releases/tag/desktop-latest";
const REPO = "https://github.com/taotaomusic/core";

export function Hero() {
  return (
    <section className="hero">
      <div className="container hero-grid">
        <div className="hero-copy">
          <span className="hero-badge">自建音乐服务 · 三端同步</span>
          <h1>
            你的私人曲库，<span className="accent">随处可听</span>
          </h1>
          <p className="hero-sub">
            桃桃音乐是一套可完全自建的跨平台音乐播放服务：Android、Windows、Web 三端一个账号听到底，
            歌单、收藏与播放进度云端同步，还有逐字歌词、多档音质与一键分享试听。
          </p>
          <div className="hero-actions">
            <a className="btn btn-primary" href={RELEASE_ANDROID}>
              下载 Android 版
            </a>
            <a className="btn btn-secondary" href={RELEASE_DESKTOP}>
              下载 Windows 版
            </a>
            <a className="btn btn-ghost" href="#features">
              了解功能 ↓
            </a>
          </div>
          <p className="hero-meta">
            开源仓库{" "}
            <a href={REPO} target="_blank" rel="noreferrer">
              taotaomusic/core
            </a>{" "}
            · 数据完全自持 · 免费使用
          </p>
        </div>

        <div className="hero-visual" aria-hidden="true">
          <div className="player-card">
            <div className="player-art">
              <div className="player-cover">
                <svg viewBox="0 0 24 24" width="58" height="58">
                  <path
                    d="M20 3.5 8.5 6v11.1a3.6 3.6 0 1 0 2 3.2V10.9L18 9v5.6a3.6 3.6 0 1 0 2 3.2z"
                    fill="currentColor"
                  />
                </svg>
              </div>
              <div className="player-vinyl">
                <span className="player-vinyl-label" />
              </div>
            </div>
            <div className="player-info">
              <span className="player-title">桃桃小夜曲</span>
              <span className="player-artist">示例曲目 · 无损</span>
            </div>
            <p className="player-lyric">把喜欢的歌，一遍一遍唱给你听</p>
            <div className="player-progress">
              <span className="player-progress-fill" />
            </div>
            <div className="player-time">
              <span>02:31</span>
              <span>04:05</span>
            </div>
            <div className="player-controls">
              <span className="pc-btn">
                <svg viewBox="0 0 24 24">
                  <path d="M7 6h2.4v12H7zM20 6v12l-9.5-6z" />
                </svg>
              </span>
              <span className="pc-btn pc-main">
                <svg viewBox="0 0 24 24">
                  <path d="M9 6.5v11l9-5.5z" />
                </svg>
              </span>
              <span className="pc-btn">
                <svg viewBox="0 0 24 24">
                  <path d="M14.6 6H17v12h-2.4zM4 6l9.5 6L4 18z" />
                </svg>
              </span>
            </div>
          </div>
          <span className="float-chip chip-1">无损音质</span>
          <span className="float-chip chip-2">逐字歌词</span>
          <span className="float-chip chip-3">云端同步</span>
        </div>
      </div>
    </section>
  );
}
