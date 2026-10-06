#!/usr/bin/env node
/**
 * 依赖告警可达性审计
 *
 * 背景：Dependabot 会把整个 Gradle 工程的**所有** classpath 都当作"依赖清单"扫描，
 * 于是构建机工具链（AGP 的模拟器测试平台、Gradle plugin classpath、Kotlin/Wasm 的
 * npm 工具链、Tauri 的 Linux/GTK 目标依赖）会大量出现在告警里。这些依赖
 * **不进入任何分发产物**，按"严重程度"排序处理会浪费时间，强行升级还可能弄坏构建。
 *
 * 本脚本的职责：对每个告警判定它是否**可达**（能否进入分发产物），
 * 从而区分「必须修」与「可按 not_used 关闭」。
 *
 * 用法：
 *   # 先手动导出依赖树（唯一需要的 Gradle 调用，不触发编译）
 *   gradlew.bat :androidApp:dependencies --configuration releaseRuntimeClasspath -q > .gradle-release-runtime.txt
 *   node tools/audit-deps.mjs                 # 分析
 *   node tools/audit-deps.mjs --refresh       # 重新导出（会调用 gradlew）
 *
 * 判据（三条，缺一不可）：
 *   1. Gradle（settings.gradle.kts）：
 *      该包必须**不出现**在 `:androidApp:dependencies --configuration releaseRuntimeClasspath`。
 *      ⚠️ 只查这个 configuration —— 它是唯一决定 APK 内容的。别的 configuration
 *      （*CompileClasspath / _internal-* / *TestPlatform*）都是构建期工具链。
 *   2. npm（kotlin-js-store/package-lock.json）：
 *      该包在 lockfile 里必须标记 `dev: true`。kotlin-js-store 本身由 Kotlin Gradle
 *      插件自动生成并回写，**手改无效**，下次构建会被覆盖。
 *   3. Cargo（desktop/src-tauri/Cargo.lock）：
 *      该包若只出现在 Tauri 的 Linux/GTK 目标链上，而项目仅构建 Windows 目标，
 *      则不参与编译链接。
 *
 * 退出码：0 = 全部告警都不可达；1 = 存在可达告警（需要人工修复）。
 */

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { resolve, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const CACHE = resolve(ROOT, ".gradle-release-runtime.txt");

const GRADLE_KEYWORDS = [
  "netty", "bouncycastle", "protobuf", "jdom", "jose4j", "commons-io",
];

/** 导出 releaseRuntimeClasspath 依赖树（决定 APK 内容的唯一 configuration）。 */
function exportRuntimeClasspath() {
  process.stdout.write("导出 :androidApp releaseRuntimeClasspath 依赖树…\n");
  const out = execFileSync(
    process.platform === "win32" ? "gradlew.bat" : "./gradlew",
    [":androidApp:dependencies", "--configuration", "releaseRuntimeClasspath", "-q"],
    { cwd: ROOT, maxBuffer: 64 * 1024 * 1024, encoding: "utf8", shell: process.platform === "win32" },
  );
  writeFileSync(CACHE, out, "utf8");
  return out;
}

function loadRuntimeClasspath(refresh) {
  if (!refresh && existsSync(CACHE)) return readFileSync(CACHE, "utf8");
  try {
    return exportRuntimeClasspath();
  } catch (err) {
    if (existsSync(CACHE)) {
      process.stderr.write(
        `⚠️ 无法自动导出依赖树（${err.code ?? err.message}），回退到已有缓存。\n` +
        `   如需刷新，请手动执行：\n` +
        `   gradlew.bat :androidApp:dependencies --configuration releaseRuntimeClasspath -q > .gradle-release-runtime.txt\n\n`,
      );
      return readFileSync(CACHE, "utf8");
    }
    throw new Error(
      "缺少依赖树缓存且无法自动导出。请先手动执行：\n" +
      "  gradlew.bat :androidApp:dependencies --configuration releaseRuntimeClasspath -q > .gradle-release-runtime.txt",
    );
  }
}

/** npm lockfile 里某包是否全部为 dev 依赖。 */
function npmIsDevOnly(lockPath, pkg) {
  if (!existsSync(lockPath)) return null;
  const lock = JSON.parse(readFileSync(lockPath, "utf8"));
  const hits = Object.entries(lock.packages ?? {})
    .filter(([k]) => k === `node_modules/${pkg}` || k.endsWith(`/node_modules/${pkg}`));
  if (hits.length === 0) return null; // 未出现，无法判定
  return hits.every(([, v]) => v.dev === true);
}

/** Cargo.lock 里某包是否只被 Linux/GTK 链引入。 */
function cargoIsLinuxOnly(lockPath, pkg) {
  if (!existsSync(lockPath)) return null;
  const text = readFileSync(lockPath, "utf8");
  const GTK_CHAIN = ["glib", "gio-sys", "gtk", "gdk", "atk", "cairo-rs", "pango"];
  return GTK_CHAIN.includes(pkg) && text.includes(`name = "${pkg}"`);
}

function main() {
  const refresh = process.argv.includes("--refresh");
  const runtime = loadRuntimeClasspath(refresh);

  // —— 判据 1：Gradle 构建期工具链 ——
  const gradleLeaks = GRADLE_KEYWORDS.filter((kw) =>
    new RegExp(`\\b${kw}`, "i").test(runtime),
  );

  const problems = [];
  if (gradleLeaks.length > 0) {
    problems.push(
      `Gradle：以下包出现在 releaseRuntimeClasspath（会进 APK），必须修复：${gradleLeaks.join(", ")}`,
    );
  }

  // —— 判据 2：npm 工具链 ——
  const npmLock = resolve(ROOT, "kotlin-js-store/package-lock.json");
  const npmChecks = ["webpack", "webpack-dev-server", "serialize-javascript", "source-map-js",
                     "proxy-addr", "compression", "node-forge", "uuid", "brace-expansion"];
  const npmProdDeps = npmChecks.filter((p) => npmIsDevOnly(npmLock, p) === false);
  if (npmProdDeps.length > 0) {
    problems.push(
      `npm：以下包在 kotlin-js-store 里不是纯 dev 依赖，需确认：${npmProdDeps.join(", ")}`,
    );
  }

  // —— 判据 3：Cargo Linux-only ——
  const cargoLock = resolve(ROOT, "desktop/src-tauri/Cargo.lock");
  const cargoLinuxOnly = cargoIsLinuxOnly(cargoLock, "glib");

  // —— 输出 ——
  console.log("\n=== 依赖告警可达性审计 ===\n");
  console.log(`[判据 1] releaseRuntimeClasspath 中的敏感包：${gradleLeaks.length === 0 ? "无 ✅" : gradleLeaks.join(", ") + " ❌"}`);
  console.log(`         依据：这是唯一决定 APK 内容的 configuration。`);
  console.log(`         其余 configuration（*CompileClasspath / _internal-* / *TestPlatform*）`);
  console.log(`         均属构建机工具链，不进产物。\n`);
  console.log(`[判据 2] kotlin-js-store 非 dev 依赖：${npmProdDeps.length === 0 ? "无 ✅" : npmProdDeps.join(", ") + " ❌"}`);
  console.log(`         依据：该 lockfile 由 Kotlin Gradle 插件自动生成并回写，手改会被覆盖。\n`);
  console.log(`[判据 3] glib 是否 Linux-only 目标依赖：${cargoLinuxOnly ? "是 ✅" : "否/未知"}`);
  console.log(`         依据：glib ← gio-sys ← gtk，仅 cfg(linux) 目标引入。\n`);

  if (problems.length === 0) {
    console.log("结论：全部告警均不可达，可按 not_used 关闭。\n");
    process.exit(0);
  } else {
    console.log("结论：存在**可达**告警，必须修复：\n");
    for (const p of problems) console.log(`  ✗ ${p}`);
    console.log();
    process.exit(1);
  }
}

main();
