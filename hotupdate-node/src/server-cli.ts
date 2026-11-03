/**
 * 零依赖自建热更新服务入口：`npm start` 或
 * `node dist/server-cli.js --port 4730 --token <管理令牌> --data <目录> [--persist <快照.json>]`
 *
 * 起来之后：
 * - 客户端/SDK 把 endpoint 指到 http://127.0.0.1:<port> 即可完成 bootstrap/下载；
 * - 管理接口带 `Authorization: Bearer <token>`，形状见 README「自建服务」一节。
 */
import { createHotUpdateServer } from "./server.js";

function argOf(name: string): string | undefined {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 ? process.argv[index + 1] : undefined;
}

const port = Number(argOf("port") ?? 4730);
const token = argOf("token");
const dataDir = argOf("data");
const persistPath = argOf("persist");

const server = createHotUpdateServer({ port, adminToken: token, dataDir, persistPath });
await server.listen();
console.log(`[hotupdate-server] 监听 http://0.0.0.0:${server.port}`);
console.log(`[hotupdate-server] 管理接口 ${token ? "已启用（Bearer 令牌鉴权）" : "未启用（未传 --token）"}`);
console.log(`[hotupdate-server] 数据目录 ${dataDir ?? "<cwd>/.hotupdate-data"}`);
