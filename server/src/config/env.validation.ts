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
  const imEnabled = String(config.IM_ENABLED ?? "false").toLowerCase() === "true";
  if (imEnabled) {
    validateUrl("IM_INTERNAL_API_BASE_URL", String(config.IM_INTERNAL_API_BASE_URL ?? "http://127.0.0.1:5001"), ["http:", "https:"]);
    validateUrl("IM_EXTERNAL_GATEWAY_URL", String(config.IM_EXTERNAL_GATEWAY_URL ?? "tcp://im.xydaigua.cn:5100"), ["tcp:"]);
    const imLifetime = Number(config.IM_SESSION_LIFETIME_SECONDS ?? 900);
    if (!Number.isInteger(imLifetime) || imLifetime < 60 || imLifetime > 86_400) {
      throw new Error(`IM_SESSION_LIFETIME_SECONDS 必须在 60 至 86400 秒之间：${config.IM_SESSION_LIFETIME_SECONDS}`);
    }
  }

  // 契约验证需要完整走「发码 → 带码注册」链路，但不能依赖真实 SMTP 邮箱。
  // 固定验证码只能在显式的测试进程中启用；生产环境或普通开发进程一律拒绝，
  // 避免把测试便利配置误带成实际的注册后门。
  const verificationTestCode = String(config.EMAIL_VERIFICATION_TEST_CODE ?? "");
  if (verificationTestCode) {
    if (config.NODE_ENV !== "test") {
      throw new Error("EMAIL_VERIFICATION_TEST_CODE 只能在 NODE_ENV=test 时使用");
    }
    if (!/^\d{6}$/.test(verificationTestCode)) {
      throw new Error("EMAIL_VERIFICATION_TEST_CODE 必须是 6 位数字");
    }
  }
  return config;
}

function validateUrl(name: string, value: string, protocols: string[]): void {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`${name} 不是合法 URL：${value}`);
  }
  if (!protocols.includes(parsed.protocol) || !parsed.hostname) {
    throw new Error(`${name} 协议或主机不合法：${value}`);
  }
}
