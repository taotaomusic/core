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

  // 数据库从 SQLite 换成 PostgreSQL 后不能再有默认值：路径写错只是建个空文件，
  // 连接串写错却可能连上另一个库。顺手挡住把旧的 DATABASE_PATH 值留在配置里的情况。
  const databaseUrl = String(config.DATABASE_URL ?? "");
  if (!databaseUrl) {
    throw new Error("缺少 DATABASE_URL，格式为 postgres://用户:密码@主机:5432/库名");
  }
  if (!/^postgres(ql)?:\/\//.test(databaseUrl)) {
    throw new Error(`DATABASE_URL 必须是 postgres:// 或 postgresql:// 开头：${databaseUrl}`);
  }
  const smtpPort = Number(config.SMTP_PORT ?? 587);
  if (!Number.isInteger(smtpPort) || smtpPort <= 0 || smtpPort > 65535) {
    throw new Error(`SMTP_PORT 不合法：${config.SMTP_PORT}`);
  }
  return config;
}
