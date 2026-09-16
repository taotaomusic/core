import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import AutoImport from "unplugin-auto-import/vite";
import Components from "unplugin-vue-components/vite";
import { ElementPlusResolver } from "unplugin-vue-components/resolvers";
import { resolve } from "path";

export default defineConfig({
  // 生产管理端固定由 Nest 挂在 /admin，构建出的资源不能再从站点根目录请求。
  base: "/admin/",
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      dts: false,
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: false,
    }),
  ],
  root: "src/frontend",
  build: {
    outDir: "../../dist/public",
    emptyOutDir: true,
    minify: "esbuild",
    cssMinify: true,
    sourcemap: false,
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        // 与 Nest 默认 PORT=4500 对齐；本地需要其它端口时通过 VITE_API_PROXY_TARGET 覆盖。
        target: process.env.VITE_API_PROXY_TARGET ?? "http://localhost:4500",
        changeOrigin: true,
      },
    },
  },
});
