import { existsSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

/**
 * 从 .env 读取配置并写入 process.env，已存在的环境变量优先。
 * 手写解析而不引入依赖：只支持 `KEY=VALUE`、`#` 注释和可选引号，够用且可读。
 */
function loadEnvFile(path = resolve(process.env.ENV_FILE ?? "./.env")) {
  if (!existsSync(path)) return;
  for (const line of readFileSync(path, "utf8").split(/\r?\n/)) {
    const match = line.trim().match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/);
    if (!match || line.trim().startsWith("#")) continue;
    const value = match[2].replace(/^(['"])(.*)\1$/, "$2");
    if (process.env[match[1]] === undefined) process.env[match[1]] = value;
  }
}

loadEnvFile();

/** 服务运行配置。 */
export const port = Number(process.env.PORT ?? 4500);
export const upstreamBaseUrl = "https://api.vkeys.cn/v2/music/tencent";
export const allowedMediaHosts = new Set(["ws.stream.qqmusic.qq.com", "y.qq.com"]);

/** 发布管理接口的静态令牌，通过请求头 `X-Admin-Token` 校验；未配置则整组管理接口关闭。 */
export const adminToken = process.env.ADMIN_TOKEN ?? "";

/** APK 存放目录，发布时写入、下载时读取。 */
export const apkDirectory = resolve(process.env.APK_DIR ?? "./data/apk");

/** 客户端默认渠道。 */
export const defaultChannel = process.env.DEFAULT_CHANNEL ?? "release";

/**
 * 对外可访问的基地址，用于拼装 APK 下载地址，例如 `https://music.xydaigua.cn`。
 * 留空时按请求头推导，但反向代理未透传 `X-Forwarded-Proto` 时可能推出错误的协议，
 * 生产环境建议显式配置。
 */
export const publicBaseUrl = (process.env.PUBLIC_BASE_URL ?? "").replace(/\/+$/, "");
