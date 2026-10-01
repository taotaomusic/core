import { Download } from "./components/Download";
import { Features } from "./components/Features";
import { Footer } from "./components/Footer";
import { Hero } from "./components/Hero";
import { Nav } from "./components/Nav";
import { SelfHost } from "./components/SelfHost";

/**
 * 官网单页骨架：导航 + 四个区块 + 页脚。
 * 页面之间全部用锚点跳转，没有路由，也没有对后端接口的任何请求，
 * 因此 CSP 可以收到 connect-src 'self' 且不影响任何功能。
 */
export function App() {
  return (
    <>
      <Nav />
      <main>
        <Hero />
        <Features />
        <Download />
        <SelfHost />
      </main>
      <Footer />
    </>
  );
}
