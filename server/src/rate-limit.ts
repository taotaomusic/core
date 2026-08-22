const attempts = new Map<string, { count: number; resetAt: number }>();

/** 通用滑动窗口计数器。不同用途必须使用不同的 key 前缀，避免互相挤占额度。 */
function allow(key: string, limit: number, windowMs: number) {
  const now = Date.now(); const current = attempts.get(key);
  if (!current || current.resetAt <= now) { attempts.set(key, { count: 1, resetAt: now + windowMs }); return true; }
  if (current.count >= limit) return false;
  current.count++; return true;
}

export function allowAuthAttempt(key: string) { return allow(`auth:${key}`, 10, 15 * 60_000); }

/**
 * 客户端配置与更新检查限流，分两层：
 * 内层按设备号，防止单个客户端异常轮询；外层按来源地址兜底，防止伪造设备号刷接口。
 *
 * 必须与登录共用的计数器分开：更新检查是周期性调用，否则正常轮询会烧掉用户的登录额度，
 * 表现成「登录提示请求过于频繁」。外层阈值放得宽，因为校园网、办公网等 NAT 环境下
 * 大量用户共用一个出口地址，按 IP 收紧会互相挤占。
 */
export function allowAppRequest(address: string, deviceId: string) {
  if (!allow(`app-ip:${address}`, 900, 15 * 60_000)) return false;
  return allow(`app-device:${deviceId || address}`, 60, 15 * 60_000);
}

/** 发布管理接口限流，防止静态令牌被暴力猜测。 */
export function allowAdminRequest(key: string) { return allow(`admin:${key}`, 60, 15 * 60_000); }
