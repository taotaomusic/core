import { Injectable } from "@nestjs/common";

/**
 * 内存滑动窗口计数器。
 *
 * 状态在进程内，重启即清空，也不跨实例共享 —— 与迁移前完全一致。
 * 不同用途必须使用不同的 key 前缀，避免互相挤占额度。
 */
@Injectable()
export class RateLimitService {
  private readonly attempts = new Map<string, { count: number; resetAt: number }>();

  private allow(key: string, limit: number, windowMs: number): boolean {
    const now = Date.now();
    const current = this.attempts.get(key);
    if (!current || current.resetAt <= now) {
      this.attempts.set(key, { count: 1, resetAt: now + windowMs });
      return true;
    }
    if (current.count >= limit) return false;
    current.count++;
    return true;
  }

  /** 登录与注册：按来源地址 10 次 / 15 分钟。 */
  allowAuthAttempt(scope: string, address: string): boolean {
    return this.allow(`auth:${scope}:${address}`, 10, 15 * 60_000);
  }

  /**
   * 管理端登录：按来源地址 30 次 / 15 分钟。
   *
   * 比普通登录宽，是因为**管理端登录的主防线已经换成账号维度的退避**
   * （连续失败 5 次即锁，见 [AdminAuthService]）。这里剩下的职责只是给
   * 「同一个出口地址轮着猜很多不同账号」设一个上界，而不是限制单个管理员
   * 的登录次数 —— 办公室、机房普遍共用一个 NAT 出口，按 10 次收紧会让
   * 第二个人登录就被拦下，运维会误判成密码错误。
   */
  allowAdminLoginAttempt(address: string): boolean {
    return this.allow(`auth:admin-login:${address}`, 30, 15 * 60_000);
  }

  /** 验证码邮件是有成本资源，单个来源地址每 15 分钟最多 5 次。 */
  allowEmailVerification(address: string): boolean {
    return this.allow(`email-verification:${address}`, 5, 15 * 60_000);
  }

  /**
   * 客户端引导与安装包下载，两层：
   * 内层按设备号，防止单个客户端异常轮询；外层按来源地址兜底，防止伪造设备号刷接口。
   * 外层阈值放得宽，因为校园网、办公网等 NAT 环境下大量用户共用一个出口地址，
   * 按 IP 收紧会互相挤占。
   */
  allowAppRequest(address: string, deviceId: string): boolean {
    if (!this.allow(`app-ip:${address}`, 900, 15 * 60_000)) return false;
    return this.allow(`app-device:${deviceId || address}`, 60, 15 * 60_000);
  }

  /** 发布管理：按来源地址 60 次 / 15 分钟，防止静态令牌被暴力猜测。 */
  allowAdminRequest(address: string): boolean {
    return this.allow(`admin:${address}`, 60, 15 * 60_000);
  }

  /**
   * 图片生成是计费接口，因此同时按登录用户与来源地址限流。
   * 来源地址阈值较宽，避免同一 NAT 下的正常用户互相挤占。
   */
  allowImageRequest(userId: number, address: string): boolean {
    if (!this.allow(`image-ip:${address}`, 60, 15 * 60_000)) return false;
    return this.allow(`image-user:${userId}`, 10, 15 * 60_000);
  }

  /** 图片任务状态轮询：独立于创建额度，允许客户端约每 3 秒查询一次。 */
  allowImageStatusRequest(userId: number, address: string): boolean {
    if (!this.allow(`image-status-ip:${address}`, 1800, 15 * 60_000)) return false;
    return this.allow(`image-status-user:${userId}`, 300, 15 * 60_000);
  }

  /**
   * IM Token 是可连接凭据，签发频率必须独立限制。
   *
   * 客户端正常在凭据临近过期时续签，15 分钟内 30 次已经远大于正常重连需求；同时保留 IP
   * 维度，避免被盗号帐号单独刷穿悟空 IM 的 Token 管理接口。
   */
  allowImSessionRequest(userId: number, address: string): boolean {
    if (!this.allow(`im-session-ip:${address}`, 180, 15 * 60_000)) return false;
    return this.allow(`im-session-user:${userId}`, 30, 15 * 60_000);
  }

  /**
   * IM 同步会在登录和翻阅历史时调用。单次响应已做严格分页，再用独立桶避免异常客户端
   * 反复拉取悟空 IM；额度仍允许正常滚动翻阅历史。
   */
  allowImSyncRequest(userId: number, address: string): boolean {
    if (!this.allow(`im-sync-ip:${address}`, 1_800, 15 * 60_000)) return false;
    return this.allow(`im-sync-user:${userId}`, 300, 15 * 60_000);
  }
}
