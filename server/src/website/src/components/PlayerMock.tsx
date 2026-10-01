import { useI18n } from "../i18n";

/**
 * 首屏右侧的播放器卡片：纯 CSS 装饰（旋转黑胶、进度条、控制键），
 * 对读屏隐藏；文案随语言切换，漂浮标签只在宽屏显示。
 */
export function PlayerMock() {
  const { dict } = useI18n();

  return (
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
          <span className="player-title">{dict.hero.playerTitle}</span>
          <span className="player-artist">{dict.hero.playerArtist}</span>
        </div>
        <p className="player-lyric">{dict.hero.playerLyric}</p>
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
      <span className="float-chip chip-1">{dict.hero.chipLossless}</span>
      <span className="float-chip chip-2">{dict.hero.chipLyrics}</span>
      <span className="float-chip chip-3">{dict.hero.chipSync}</span>
    </div>
  );
}
