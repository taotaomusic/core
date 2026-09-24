import { basename, sep } from "node:path";
import type { Response } from "express";

/**
 * 分享播放器静态资源的缓存策略。
 *
 * ⚠️ **入口文件绝不能吃长缓存。** 分享页的入口是**不带内容哈希的固定名**
 * （`taotao-share-player.js`、`TaotaoMusic-webApp-wasm-js.wasm`），而 JS 胶水必须提供
 * wasm 要 import 的那批 `js_code` 实现 —— 两者必须**严格同批**。一旦被标成 immutable，
 * 浏览器很容易留下小的 JS、把大的 wasm（8 MB）淘汰后重新拉取，于是「旧 JS + 新 wasm」：
 *
 * ```
 * LinkError: WebAssembly.instantiate(): Import #93 "js_code"
 * "org.w3c.dom.onended_$external_prop_setter": function import requires a callable
 * ```
 *
 * 2026-09-21 实际踩过，且**每次发版都会踩**，不是偶发。所以只有真正带内容哈希的文件
 * 才吃 `immutable`，其余一律 `no-cache`（配合 ETag 协商：未变 304，变了立刻换新）。
 *
 * 抽成独立函数是为了能在不启动服务的情况下断言，见 `tools/verify-static-cache.mjs`。
 */
export function sharePlayerCacheHeaders(response: Response, filePath: string): void {
  const name = basename(filePath);
  // 内容哈希形如 `dd568dbcd078c0adf7cf.wasm` —— 哈希在**开头**，前面没有分隔符，
  // 所以不能写成 `[.-][0-9a-f]{16,}`（那样永远匹配不到 webpack 的产物名）。
  const contentHashed = /(?:^|[.-])[0-9a-f]{16,}\./.test(name);
  if (contentHashed || filePath.includes(`${sep}composeResources${sep}`)) {
    response.setHeader("cache-control", "public, max-age=31536000, immutable");
    return;
  }
  response.setHeader("cache-control", "no-cache");
}
