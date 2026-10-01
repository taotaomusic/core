import { Reveal } from "./Reveal";

const FEATURES = [
  {
    icon: "☁️",
    title: "云端歌单同步",
    desc: "歌单、收藏、最近播放与播放进度全部上云，换设备登录即接续上次的进度。",
  },
  {
    icon: "🎤",
    title: "逐字歌词",
    desc: "卡拉OK式逐字刷新高亮，播放到哪个字就亮哪个字，跟唱不再抢拍。",
  },
  {
    icon: "🎚️",
    title: "多档音质",
    desc: "从标准到无损多档音质自由切换，按网络与流量情况随手调整。",
  },
  {
    icon: "🔍",
    title: "聚合搜索",
    desc: "一次搜索覆盖多个音源，试听、收藏与下载在同一条结果里完成。",
  },
  {
    icon: "🔗",
    title: "分享试听",
    desc: "任意歌曲生成短链接，对方浏览器打开即听，不需要安装任何客户端。",
  },
  {
    icon: "🔐",
    title: "传输加密",
    desc: "自研传输加密层保护敏感接口，密钥由服务端动态下发，客户端零硬编码。",
  },
];

/** 功能特性：六张卡片铺满两行，进场用 Reveal 淡入。 */
export function Features() {
  return (
    <section id="features" className="section">
      <div className="container">
        <Reveal className="section-head">
          <span className="section-kicker">功能特性</span>
          <h2>听歌这件事，本来就该这么顺</h2>
          <p>客户端与服务端共同打磨的日常体验，而不是功能的简单堆叠。</p>
        </Reveal>
        <div className="feature-grid">
          {FEATURES.map((feature) => (
            <Reveal key={feature.title}>
              <article className="feature-card">
                <span className="feature-icon" aria-hidden="true">
                  {feature.icon}
                </span>
                <h3>{feature.title}</h3>
                <p>{feature.desc}</p>
              </article>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}
