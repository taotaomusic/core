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
}
