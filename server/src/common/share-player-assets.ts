import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * 分享播放器入口资源的「路径版本化」。
 *
 * ## 为什么需要它
 *
 * 分享页的入口是**两个不带内容哈希的固定名文件**：
 * `taotao-share-player.js`（JS 胶水）与 `TaotaoMusic-webApp-wasm-js.wasm`（应用 wasm）。
 * JS 胶水必须提供 wasm 要 import 的那批 `js_code` 实现，**两者必须严格同批**。
 *
 * 固定名 + 任何形式的缓存 = 迟早混批。实测出现过两次，方向还是相反的：
 *
 * - 「旧 JS + 新 wasm」：浏览器留下 540 KB 的 JS、把 8 MB 的 wasm 淘汰后重取；
 * - 「旧 wasm + 新 JS」：JS 重取成新版（不再导出 `onended`），而 wasm 仍是旧版（仍需要它）。
 *
 * 两种都直接抛：
 *
 * ```
 * LinkError: WebAssembly.instantiate(): Import #93 "js_code"
 * "org.w3c.dom.onended_$external_prop_setter": function import requires a callable
 * ```
 *
 * **只靠 `cache-control` 治不了本**：把入口文件改成 `no-cache` 只能保证「以后」不再混，
 * 但浏览器里**已经按 `immutable` 存下的那份不会自动失效** —— 用户在 `max-age` 到期前
 * 根本不会去问服务器，必须手动硬刷新才能恢复。
 *
 * 所以再加一层：**把内容指纹写进 URL 路径**（`/share/v/<指纹>/…`）。内容一变 URL 就变，
 * 浏览器手上不可能存在这个 URL 的旧缓存，新旧资源在**地址层面**就被隔开了。
 * 页面里的 `<base>` 也指向版本化路径，于是 JS、wasm、skiko、字体等**所有相对引用**
 * 自动跟着版本走，不需要逐个改写文件名。
 *
 * 纯函数抽出来是为了能在不启动服务的情况下断言，见 `tools/verify-static-cache.ts`。
 */

/** 参与指纹计算的两个固定名入口文件。其余资源要么已带内容哈希，要么由 `<base>` 带版本。 */
const ENTRY_FILES = ["taotao-share-player.js", "TaotaoMusic-webApp-wasm-js.wasm"] as const;

/**
 * 计算分享播放器入口资源的版本指纹。
 *
 * 直接对文件内容取 sha256（而不是用 mtime / size）—— 这样「重新构建但产物没变」不会
 * 白白让全量用户重下 11 MB，而「产物变了」一定换指纹。启动时只读一次。
 */
export function sharePlayerVersion(directory: string): string {
  const hash = createHash("sha256");
  for (const name of ENTRY_FILES) {
    hash.update(readFileSync(join(directory, name)));
  }
  return hash.digest("hex").slice(0, 12);
}

/**
 * 把 `index.html` 的 `<base>` 指到版本化路径，并让入口脚本改走相对路径。
 *
 * 两步必须**同时成立**：`<base>` 换成功之后，`/share/taotao-share-player.js` 这种绝对路径
 * 反而不受 `<base>` 影响、拿不到版本号，所以要把 `/share/` 前缀去掉变成相对路径。
 * 反过来，`<base>` 没换成（构建产物格式变了）却把脚本改成相对路径，页面挂在 `/s/<短码>`
 * 下时会被解析成 `/s/taotao-share-player.js` → 404。
 * **所以认不出来就整体原样返回，绝不半改。**
 */
export function rewriteShareIndexHtml(html: string, version: string): string {
  const basePattern = /(<base\s+href=")\/share\/(")/;
  if (!basePattern.test(html)) return html;
  return html
    .replace(basePattern, `$1/share/v/${version}/$2`)
    .replace(/(<script\s+src=")\/share\//, "$1");
}
