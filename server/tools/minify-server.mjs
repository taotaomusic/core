// 只压缩 tsc 已经生成的服务端 JavaScript。不能用它替代 tsc：NestJS 依赖
// emitDecoratorMetadata 生成的 design:paramtypes，直接用无类型检查器的打包器会丢失元数据。
import { readdir, readFile, stat, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { minify } from "terser";

const outputDirectory = resolve("dist");

/**
 * `dist` 下的**浏览器产物**目录，必须跳过。
 *
 * - `public`：管理后台，由 vite 自己压缩（含 esbuild + cssMinify）。
 * - `share-player`：Kotlin/Wasm 分享页。这里的 `taotao-share-player.js` 是 wasm 的**胶水**，
 *   必须和 `TaotaoMusic-webApp-wasm-js.wasm` **严格同批**，而且它自带 sourcemap 与
 *   LICENSE 附属文件。被 terser 再压一遍会同时改写内容、让 `.js.map` 对不上，
 *   还平白改变了「浏览器实际拿到的字节」—— 这个模块最怕的就是产物被动过。
 *
 * ⚠️ 为什么之前没暴露：`npm run build` 第一步 `rimraf --glob dist/*` 会把 `dist/share-player`
 * 清掉，等到最后一步 `build:web-player` 才重新复制 —— 压缩时它根本不存在。
 * 但**分步构建**（技能里推荐的那种：`tsc` → `minify` → …）在已构建过的目录上再跑一次时，
 * `dist/share-player` 是存在的，于是被误压。2026-09-21 实际踩到。
 */
const BROWSER_BUNDLE_DIRECTORIES = new Set(["public", "share-player"]);

async function javascriptFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const nested = await Promise.all(entries.map(async (entry) => {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      return BROWSER_BUNDLE_DIRECTORIES.has(entry.name) ? [] : javascriptFiles(path);
    }
    return entry.isFile() && entry.name.endsWith(".js") ? [path] : [];
  }));
  return nested.flat();
}

const files = await javascriptFiles(outputDirectory);
let originalBytes = 0;
let compressedBytes = 0;

for (const file of files) {
  const source = await readFile(file, "utf8");
  originalBytes += Buffer.byteLength(source);
  const result = await minify(source, {
    ecma: 2022,
    module: false,
    compress: { passes: 2 },
    mangle: true,
    // Nest 的依赖注入主要依赖装饰器元数据；仍保留名称，方便生产日志定位到原类和原方法。
    keep_classnames: true,
    keep_fnames: true,
    format: { comments: false },
  });
  if (!result.code) throw new Error(`压缩结果为空：${file}`);
  await writeFile(file, result.code, "utf8");
  compressedBytes += (await stat(file)).size;
}

const savedPercent = originalBytes === 0 ? 0 : Math.round((1 - compressedBytes / originalBytes) * 1_000) / 10;
console.log(`已压缩 ${files.length} 个服务端 JavaScript：${originalBytes} → ${compressedBytes} 字节（减少 ${savedPercent}%）`);
