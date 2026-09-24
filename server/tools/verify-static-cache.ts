/**
 * 分享页静态资源缓存策略的验证。
 *
 * 起因：2026-09-21 线上报 `LinkError: WebAssembly.instantiate(): Import #93 "js_code"
 * "org.w3c.dom.onended_$external_prop_setter": function import requires a callable`
 * —— 根因是 `/share` 曾整目录 `immutable`，浏览器留下旧 JS、把新 wasm 拉了回来。
 *
 * 这里验两件事（都不需要数据库，也不启动 Nest）：
 * 1. `sharePlayerCacheHeaders` 对各类文件名的判定（直接调真实函数，不是复制一份逻辑）；
 * 2. 用真实函数挂一个 express.static，确认响应头与**条件请求（304）**真的按预期工作。
 *
 * ⚠️ 条件请求必须用原生 `node:http` 发，不要用 `fetch`：实测 undici 的 fetch 在这条路径上
 * 即使 ETag / Last-Modified 完全一致也拿不到 304，会把正确的服务端行为判成失败。
 *
 * 跑法：
 *   cd server && node --require ts-node/register tools/verify-static-cache.ts
 */
import express, { static as expressStatic } from "express";
import { readFileSync } from "node:fs";
import { request as httpRequest } from "node:http";
import { join, resolve } from "node:path";
import type { Response } from "express";
import { rewriteShareIndexHtml, sharePlayerVersion } from "../src/common/share-player-assets";
import { sharePlayerCacheHeaders } from "../src/common/static-cache-policy";

const PORT = 4789;
const sharePlayerDir = resolve(__dirname, "..", "dist", "share-player");

let failed = 0;

function check(name: string, ok: boolean, detail: string): void {
  console.log(`  ${ok ? "✓" : "✗"} ${name}${ok ? "" : `  →  ${detail}`}`);
  if (!ok) failed += 1;
}

/** 用假 Response 收集 setHeader 调用，直接验真实函数。 */
function headersFor(filePath: string): Record<string, string> {
  const captured: Record<string, string> = {};
  const fake = {
    setHeader(key: string, value: string) {
      captured[key.toLowerCase()] = value;
    },
  } as unknown as Response;
  sharePlayerCacheHeaders(fake, filePath);
  return captured;
}

const IMMUTABLE = "public, max-age=31536000, immutable";

function unitChecks(): void {
  console.log("=== 判定规则（直接调用 sharePlayerCacheHeaders）");
  const dir = join(sharePlayerDir, "x");
  const cases: Array<[string, string]> = [
    [join(dir, "taotao-share-player.js"), "no-cache"],
    [join(dir, "TaotaoMusic-webApp-wasm-js.wasm"), "no-cache"],
    [join(dir, "index.html"), "no-cache"],
    [join(dir, "taotao-share-player.js.map"), "no-cache"],
    [join(dir, "dd568dbcd078c0adf7cf.wasm"), IMMUTABLE],
    [join(dir, "app.9f2a1c4b8e7d6f5a3c2b1e0d.js"), IMMUTABLE],
    [join(sharePlayerDir, "composeResources", "taotaomusic.webapp.generated.resources", "font.otf"), IMMUTABLE],
  ];
  for (const [filePath, expected] of cases) {
    const actual = headersFor(filePath)["cache-control"];
    check(`${filePath.slice(sharePlayerDir.length + 1)} → ${expected}`, actual === expected, String(actual));
  }
}

/**
 * 路径版本化：光靠 `cache-control` 治不了本 —— 浏览器里**已经按 immutable 存下的那份
 * 不会自动失效**，用户不硬刷新就一直抛 `LinkError`。把内容指纹写进 URL 路径才能让新旧
 * 资源在地址层面隔开，且用户什么都不用做。
 */
function versionChecks(): string {
  console.log("=== 入口路径版本化");
  const version = sharePlayerVersion(sharePlayerDir);
  check("指纹是 12 位十六进制", /^[0-9a-f]{12}$/.test(version), version);

  const html = readFileSync(join(sharePlayerDir, "index.html"), "utf8");
  const rewritten = rewriteShareIndexHtml(html, version);
  check(
    "index.html 的 <base> 指向版本化路径",
    rewritten.includes(`<base href="/share/v/${version}/">`),
    rewritten.match(/<base[^>]*>/)?.[0] ?? "未找到 base",
  );
  check(
    "入口脚本改成相对路径（绝对路径不受 <base> 影响，拿不到版本号）",
    rewritten.includes('src="taotao-share-player.js"') && !rewritten.includes('src="/share/taotao-share-player.js"'),
    rewritten.match(/<script[^>]*>/)?.[0] ?? "未找到 script",
  );

  // 认不出 <base> 时必须**整体原样返回**：只把脚本改成相对路径、base 却没换成，
  // 页面挂在 /s/<短码> 下会去请求 /s/taotao-share-player.js → 404，比不版本化更糟。
  const unknown = '<html><script src="/share/taotao-share-player.js"></script></html>';
  check(
    "认不出 <base> 时原样返回，绝不半改",
    rewriteShareIndexHtml(unknown, version) === unknown,
    rewriteShareIndexHtml(unknown, version),
  );

  return version;
}

type RawResponse = { status: number; headers: Record<string, string | string[] | undefined> };

function rawGet(path: string, headers: Record<string, string> = {}): Promise<RawResponse> {
  return new Promise((done, fail) => {
    const request = httpRequest({ host: "127.0.0.1", port: PORT, path, headers }, (response) => {
      // 只关心响应头，body 直接丢掉（wasm 有 8 MB，没必要读完）。
      response.resume();
      response.once("end", () => done({ status: response.statusCode ?? 0, headers: response.headers }));
    });
    request.once("error", fail);
    request.end();
  });
}

const headerOf = (response: RawResponse, key: string): string => String(response.headers[key] ?? "");

async function integrationChecks(version: string): Promise<void> {
  console.log("=== 真实 express.static（同一份 setHeaders）");
  const instance = express();
  instance.use(
    `/share/v/${version}`,
    expressStatic(sharePlayerDir, {
      etag: true,
      lastModified: true,
      setHeaders: (response: Response) => response.setHeader("cache-control", IMMUTABLE),
    }),
  );
  instance.use(
    expressStatic(sharePlayerDir, {
      etag: true,
      lastModified: true,
      setHeaders: sharePlayerCacheHeaders,
    }),
  );
  const server = instance.listen(PORT, "127.0.0.1");
  await new Promise<void>((done) => server.once("listening", () => done()));

  try {
    const entry = await rawGet("/taotao-share-player.js");
    check(
      "入口 JS 响应 200 且 cache-control 是 no-cache",
      entry.status === 200 && headerOf(entry, "cache-control") === "no-cache",
      `${entry.status} ${headerOf(entry, "cache-control")}`,
    );

    const etag = headerOf(entry, "etag");
    const lastModified = headerOf(entry, "last-modified");
    check("入口 JS 带 ETag（协商的前提）", etag.length > 0, etag);

    const byEtag = await rawGet("/taotao-share-player.js", { "if-none-match": etag });
    check(
      "带 If-None-Match 复访返回 304（未变不重下）",
      byEtag.status === 304,
      `实际 ${byEtag.status}；etag=${etag}`,
    );

    const byDate = await rawGet("/taotao-share-player.js", { "if-modified-since": lastModified });
    check(
      "带 If-Modified-Since 复访返回 304",
      byDate.status === 304,
      `实际 ${byDate.status}；last-modified=${lastModified}`,
    );

    const appWasm = await rawGet("/TaotaoMusic-webApp-wasm-js.wasm");
    check(
      "固定名 wasm 也是 no-cache（必须与 JS 同批）",
      headerOf(appWasm, "cache-control") === "no-cache",
      headerOf(appWasm, "cache-control"),
    );

    const hashedWasm = await rawGet("/dd568dbcd078c0adf7cf.wasm");
    check(
      "内容哈希 wasm 仍吃长缓存",
      headerOf(hashedWasm, "cache-control") === IMMUTABLE,
      headerOf(hashedWasm, "cache-control"),
    );

    // 版本化路径下的资源可以放心长缓存：路径自带内容指纹，内容变了路径就变了。
    const versionedJs = await rawGet(`/share/v/${version}/taotao-share-player.js`);
    check(
      "版本化路径下入口 JS 吃长缓存",
      versionedJs.status === 200 && headerOf(versionedJs, "cache-control") === IMMUTABLE,
      `${versionedJs.status} ${headerOf(versionedJs, "cache-control")}`,
    );

    const versionedWasm = await rawGet(`/share/v/${version}/TaotaoMusic-webApp-wasm-js.wasm`);
    check(
      "版本化路径下应用 wasm 也能取到（与 JS 同一路径前缀 → 必然同批）",
      versionedWasm.status === 200 && headerOf(versionedWasm, "cache-control") === IMMUTABLE,
      `${versionedWasm.status} ${headerOf(versionedWasm, "cache-control")}`,
    );
  } finally {
    await new Promise<void>((done) => server.close(() => done()));
  }
}

async function main(): Promise<void> {
  unitChecks();
  const version = versionChecks();
  await integrationChecks(version);
  console.log(`\n${failed === 0 ? "全部通过" : `失败 ${failed} 项`}`);
  process.exit(failed === 0 ? 0 : 1);
}

void main();
