import type { NextFunction, Request, Response } from "express";
import { accessTokenSubject, dbRoutingContext } from "./db-routing.context";
import type { DbWriteActivityTracker } from "./db-write-activity.tracker";

/** 这些方法语义上一定写库，整个请求固定走主库。 */
const WRITE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * 管理端路径前缀。
 *
 * 管理端会话、审计与 2FA 票据对时序敏感，且管理流量很小，读请求也统一走
 * 主库：管理端用的是不透明会话令牌，`accessTokenSubject` 解析不出用户 ID，
 * 写后立读机制天然覆盖不到它。
 */
const PRIMARY_PATH_PREFIXES = ["/api/v1/admin/", "/api/v1/app/admin/"];

/**
 * 数据库读写路由中间件（入口）。
 *
 * 必须挂得足够早（main.ts 里在一切中间件之前）：全局守卫、限流、业务处理函数
 * 都可能查库，它们拿到的目标池由这里设置的上下文决定。async 生命周期之外没有
 * store，[DatabaseService] 一律按主库处理，因此挂载时机错了也不会读到副本，
 * 只是失去读分担。
 *
 * 注意 `Authorization` 头在加密请求上也是明文可见的（加密中间件只替换请求体），
 * 且本中间件先于加密中间件执行，所以无需关心链路是否加密。
 */
export function createDbRoutingMiddleware(tracker: DbWriteActivityTracker) {
  return (request: Request, _response: Response, next: NextFunction): void => {
    dbRoutingContext.run({ defaultTarget: targetOf(request, tracker) }, next);
  };
}

function targetOf(request: Request, tracker: DbWriteActivityTracker): "primary" | "replica" {
  if (WRITE_METHODS.has(request.method.toUpperCase())) return "primary";
  const path = request.path;
  if (PRIMARY_PATH_PREFIXES.some((prefix) => path.startsWith(prefix))) return "primary";
  // 写后立读：该用户刚刚完成过写操作，窗口期内的读请求也走主库，
  // 避免「写主库、读副本」之间的同步延迟造成前端看到旧数据。
  const subject = accessTokenSubject(request.headers.authorization);
  if (subject !== null && tracker.isActive(subject)) return "primary";
  return "replica";
}
