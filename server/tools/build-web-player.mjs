import { cp, rm, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const serverDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const projectDirectory = resolve(serverDirectory, "..");
const gradleWrapper = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const gradleArguments = [":webApp:wasmJsBrowserDistribution", "--no-daemon"];

// 仓库拆分后，server 可能作为独立仓库存在（GitHub hdppppppp/music-server），
// 上级目录不再有 Gradle 工程，Kotlin/Wasm 分享播放器无从构建。
// 检测到上游工程（settings.gradle.kts 与 wrapper）缺失时跳过这一步，
// dist/share-player 由部署侧用主仓库的产物另行提供；主仓库 monorepo 里行为与原来完全一致。
const upstreamProject = resolve(projectDirectory, "settings.gradle.kts");
const upstreamWrapper = resolve(projectDirectory, gradleWrapper);
if (!existsSync(upstreamProject) || !existsSync(upstreamWrapper)) {
  console.warn(
    "上级目录未找到 Gradle 工程（settings.gradle.kts / wrapper），跳过 Kotlin/Wasm 分享播放器构建；" +
      "dist/share-player 需要由主仓库构建后另行提供。",
  );
  process.exit(0);
}
const command = process.platform === "win32" ? (process.env.ComSpec || "cmd.exe") : gradleWrapper;
const commandArguments = process.platform === "win32"
  ? ["/d", "/s", "/c", gradleWrapper, ...gradleArguments]
  : gradleArguments;
const result = spawnSync(
  command,
  commandArguments,
  {
    cwd: projectDirectory,
    stdio: "inherit",
  },
);
if (result.status !== 0) process.exit(result.status ?? 1);

const source = resolve(projectDirectory, "webApp", "build", "dist", "wasmJs", "productionExecutable");
const destination = resolve(serverDirectory, "dist", "share-player");
await stat(resolve(source, "index.html"));
await rm(destination, { recursive: true, force: true });
await cp(source, destination, { recursive: true });
console.log(`Kotlin/Wasm 分享播放器已复制到 ${destination}`);
