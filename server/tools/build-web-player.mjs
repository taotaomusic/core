import { cp, rm, stat } from "node:fs/promises";
import { resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const serverDirectory = resolve(fileURLToPath(new URL("..", import.meta.url)));
const projectDirectory = resolve(serverDirectory, "..");
const gradleWrapper = process.platform === "win32" ? "gradlew.bat" : "./gradlew";
const gradleArguments = [":webApp:wasmJsBrowserDistribution", "--no-daemon"];
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
