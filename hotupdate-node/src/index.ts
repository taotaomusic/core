/**
 * @taotao/hotupdate-node —— 桃桃音乐热更新的 Node 客户端与运维 SDK。
 *
 * 四个入口：
 * - `HotUpdateClient`：调 `/app/bootstrap` 的客户端（自测、机器人、其它 Node 服务）；
 * - `HotUpdateAdmin`：发布/放量/补丁/远程配置的管理端 SDK（运维脚本、CI 步骤）；
 * - `createHotUpdateServer`：零依赖可自建热更新后端（与主服务端同契约的参考实现）；
 * - `bucketOf` / `pickGrayCandidate` / `totp` / `downloadToFile`：灰度分桶、TOTP、
 *   带校验下载的参考实现。
 *
 * 「整套兼容」的锚点在本目录 `src/protocol.ts` 的注释里；改协议要三方同步：
 * Kotlin `hotupdate` 模块、本 SDK、服务端 `server/src/release/`。
 */
export * from "./protocol.js";
export * from "./bucket.js";
export * from "./client.js";
export * from "./download.js";
export * from "./totp.js";
export * from "./admin.js";
export * from "./server.js";
