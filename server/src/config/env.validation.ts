/**
 * 环境变量校验。
 *
 * 迁移前这条检查散落在 `auth.ts` 的模块顶层（导入时抛异常），
 * 现在集中到配置加载阶段，启动即失败而不是等到第一次签发令牌。
 */
export function validateEnvironment(config: Record<string, unknown>): Record<string, unknown> {
  const secret = String(config.AUTH_SECRET ?? "");
  if (config.NODE_ENV === "production" && secret.length < 32) {
    throw new Error("生产环境 AUTH_SECRET 至少需要 32 个字符");
  }
  const port = Number(config.PORT ?? 4500);
  if (!Number.isInteger(port) || port <= 0 || port > 65535) {
    throw new Error(`PORT 不合法：${config.PORT}`);
  }
  return config;
}
