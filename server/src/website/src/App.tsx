import { useEffect } from "react";
import { ConfigProvider, theme as antdTheme } from "antd";
import { Download } from "./components/Download";
import { Features } from "./components/Features";
import { Footer } from "./components/Footer";
import { Hero } from "./components/Hero";
import { Nav } from "./components/Nav";
import { SelfHost } from "./components/SelfHost";
import { useTheme } from "./hooks/useTheme";
import { antdLocale, I18nProvider, useI18n } from "./i18n";
import { antdTokens } from "./theme";

/**
 * 官网单页骨架：导航 + 四个区块 + 页脚，锚点跳转，无路由、无后端请求。
 *
 * 组件库 antd：ConfigProvider 按主题挂 default/dark 算法、按语言挂 locale；
 * 自定义 CSS 只负责品牌视觉（光晕/播放器卡片/导航）。
 */
function ThemedSite() {
  // 主题状态只在这里持有一份（useTheme 内部是独立 state，多处调用会各持一份、
  // 互不同步 —— 之前 Nav 里再调一次导致 antd 算法冻结在初始主题，切换后出现
  // 「半暗半亮」的脏状态），开关通过 props 传给 Nav。
  const { light, toggle } = useTheme();
  const { lang } = useI18n();

  // 首屏入场动画只播一次：挂上 data-entered 后不再重播（切主题/语言不闪动画）。
  useEffect(() => {
    const timer = window.setTimeout(() => {
      document.documentElement.dataset.entered = "1";
    }, 1800);
    return () => window.clearTimeout(timer);
  }, []);

  return (
    <ConfigProvider
      locale={antdLocale(lang)}
      theme={{
        algorithm: light ? antdTheme.defaultAlgorithm : antdTheme.darkAlgorithm,
        token: antdTokens(light),
      }}
    >
      <Nav themeLight={light} onToggleTheme={toggle} />
      <main>
        <Hero />
        <Features />
        <Download />
        <SelfHost />
      </main>
      <Footer />
    </ConfigProvider>
  );
}

export function App() {
  return (
    <I18nProvider>
      <ThemedSite />
    </I18nProvider>
  );
}
