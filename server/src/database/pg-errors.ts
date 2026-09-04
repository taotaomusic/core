/**
 * PostgreSQL 错误码映射。
 *
 * SQLite 时代很多"先查再写"的竞态窗口极窄（同步 API、单进程），换成连接池后
 * 变成可稳定触发的路径，唯一约束冲突必须显式接住并翻译成业务码 ——
 * 否则会被全局过滤器归成 502，而契约要求的是 409。
 */

/** 唯一约束冲突。 */
const UNIQUE_VIOLATION = "23505";

export function isUniqueViolation(error: unknown): boolean {
  return typeof error === "object" && error !== null && (error as { code?: string }).code === UNIQUE_VIOLATION;
}
