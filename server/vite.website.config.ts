import { defineConfig } from "vite";

/**
 * 官网（React 单页）的 Vite 配置，与管理后台的 vite.config.ts 相互独立。
 *
 * - base 用相对路径：站点由后端静态托管在根路径 `/`，相对引用让 `/` 与
 *   `/index.html` 两种入口都能正确加载资源，也不与任何挂载路径绑定。
 * - JSX 交给 esbuild 的 automatic runtime 转换，不引入 plugin-react：
 *   官网没有 HMR/局部刷新的诉求，全量刷新足够，少一批 babel 依赖。
 */
export default defineConfig({
  base: "./",
  root: "src/website",
  esbuild: { jsx: "automatic" },
  build: {
    outDir: "../../dist/website",
    emptyOutDir: true,
    minify: "esbuild",
    cssMinify: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        // vendor 分包：react 全家桶与 antd 各自独立成 chunk。官网业务代码很小且
        // 常改，分开后发版时 vendor 的内容哈希不变，浏览器继续吃长缓存，
        // 只需重新下载几十 KB 的业务 chunk；全部打进一个 index 会每次全量拉 600+KB。
        manualChunks(id: string) {
          if (!id.includes("node_modules")) return undefined;
          // Windows 下 id 是反斜杠路径，统一按两种分隔符匹配。
          if (/[\\/]node_modules[\\/](react|react-dom|react-is|scheduler)[\\/]/.test(id)) {
            return "react";
          }
          if (/[\\/]node_modules[\\/](antd|@ant-design|rc-[^\\/]+|dayjs)[\\/]/.test(id)) {
            return "antd";
          }
          return undefined;
        },
      },
    },
  },
  server: {
    // 本地调官网样式的独立端口；纯静态页面，不需要代理后端接口。
    port: 5184,
  },
});
