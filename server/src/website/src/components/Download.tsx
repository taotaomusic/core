import { Reveal } from "./Reveal";

/** core 仓库的两个滚动 Release tag：APK 与桌面包的资产名带版本号，只能落到 Release 页。 */
const RELEASE_ANDROID = "https://github.com/taotaomusic/core/releases/tag/latest";
const RELEASE_DESKTOP = "https://github.com/taotaomusic/core/releases/tag/desktop-latest";

function AndroidIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M17.6 9.48l1.84-3.18c.16-.31.04-.7-.26-.85-.29-.15-.65-.06-.83.22l-1.88 3.24a11.43 11.43 0 0 0-8.94 0L5.65 5.67c-.19-.29-.58-.38-.87-.2-.28.18-.37.54-.22.83L6.4 9.48A10.81 10.81 0 0 0 1 18h22a10.81 10.81 0 0 0-5.4-8.52M7 15.25a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5m10 0a1.25 1.25 0 1 1 0-2.5 1.25 1.25 0 0 1 0 2.5" />
    </svg>
  );
}

function WindowsIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M3 5.55 10.6 4.5v7.1H3zM11.6 4.35 21 3v8.6h-9.4zM3 12.6h7.6v7.1L3 18.65zM11.6 12.6H21v8.4l-9.4-1.35z" />
    </svg>
  );
}

function WebIcon() {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3c2.8 2.6 4 5.6 4 9s-1.2 6.4-4 9c-2.8-2.6-4-5.6-4-9s1.2-6.4 4-9z" />
    </svg>
  );
}

/** 多端下载：Android 与 Windows 指向 Release，Web 分享播放器说明由 App 内生成。 */
export function Download() {
  return (
    <section id="download" className="section section-alt">
      <div className="container">
        <Reveal className="section-head">
          <span className="section-kicker">多端下载</span>
          <h2>三端覆盖，各取所需</h2>
          <p>所有安装包由统一 CI 构建并签名发布，版本号连续、可回溯。</p>
        </Reveal>
        <div className="download-grid">
          <Reveal>
            <article className="download-card is-primary">
              <AndroidIcon />
              <h3>Android</h3>
              <p>滚动构建的签名 APK，与桌面端功能同源，歌单、收藏与播放进度云端同步。</p>
              <div className="download-meta">APK · GitHub Release 滚动更新</div>
              <a className="btn btn-primary" href={RELEASE_ANDROID}>
                获取 APK
              </a>
            </article>
          </Reveal>
          <Reveal>
            <article className="download-card">
              <WindowsIcon />
              <h3>Windows 桌面版</h3>
              <p>原生窗口体验，应用内自动更新已启用，装一次即可长期自动跟进新版。</p>
              <div className="download-meta">MSI / 安装器 · 支持签名校验的自动更新</div>
              <a className="btn btn-secondary" href={RELEASE_DESKTOP}>
                获取安装包
              </a>
            </article>
          </Reveal>
          <Reveal>
            <article className="download-card">
              <WebIcon />
              <h3>Web 分享播放器</h3>
              <p>在 App 内点「分享」生成短链接，任何现代浏览器打开即可试听，无需登录。</p>
              <div className="download-meta">
                <code>https://你的域名/s/xxxxxxxx</code>
              </div>
              <p className="download-note">入口在 App 内 · 无需下载</p>
            </article>
          </Reveal>
        </div>
      </div>
    </section>
  );
}
