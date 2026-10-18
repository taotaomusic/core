import { AsyncLocalStorage } from "node:async_hooks";

/**
 * 一次请求内所有数据库操作的默认路由目标。
 *
 * 写请求（POST/PUT/PATCH/DELETE）整体固定 `primary`；读请求默认 `replica`，
 * 由 `createDbRoutingMiddleware` 按「管理端路径」与「写后立读窗口」升级成 `primary`。
 * 单个查询上的显式标记（`QueryOptions.primary`）永远优先于这里的目标。
 */
export type DbRoutingContext = { defaultTarget: "primary" | "replica" };

/**
 * 请求级路由上下文（AsyncLocalStorage）。
 *
 * 无 store 时（启动期、定时任务、运维脚本等非 HTTP 调用）`DatabaseService`
 * 一律按主库处理 —— 这是安全默认：没有上下文就没有写后立读保证，宁可多走主库，
 * 也不能让写后的第一次读落到同步延迟未知的副本上。
 */
export const dbRoutingContext = new AsyncLocalStorage<DbRoutingContext>();

/**
 * 从访问令牌里提取用户 ID，**不做任何签名校验**。
 *
 * 只服务于「写后立读」的路由判断：伪造 sub 最多让这次请求多走一次主库读，
 * 不存在越权面 —— 真正的身份校验仍由 `AccessTokenGuard` 完成，本函数被绕过
 * 不影响它的结论。解析失败（管理端不透明会话令牌、垃圾串、空头）返回 null，
 * 一律按「非写后立读」处理。
 */
export function accessTokenSubject(authorization: string | undefined): number | null {
  const token = (authorization ?? "").replace(/^Bearer\s+/i, "");
  const [payload] = token.split(".");
  if (!payload) return null;
  try {
    const decoded: unknown = JSON.parse(Buffer.from(payload, "base64url").toString("utf8"));
    const sub = (decoded as { sub?: unknown } | null)?.sub;
    return typeof sub === "number" && Number.isSafeInteger(sub) && sub > 0 ? sub : null;
  } catch {
    return null;
  }
}
