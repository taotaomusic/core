import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Tauri 期望前端 dev server 固定端口；构建产物进 dist/，由 tauri.conf.json 的
// frontendDist 引用。与 webApp/（Kotlin/Wasm 分享播放器）完全独立。
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 5183,
    strictPort: true,
  },
  build: {
    outDir: "dist",
    target: "chrome110",
  },
});
