import { Injectable } from "@nestjs/common";

/**
 * 管理端请求的额度上限。
 *
 * 默认 60 次 / 15 分钟（按来源地址），与迁移前一致。
 *
 * `ADMIN_RATE_LIMIT` 可以覆盖它，存在的唯一理由是**契约验证脚本**：它要在一次运行里
 * 打上百次管理接口，60 的额度会在中途把它自己限流掉，表现为一批看不懂的 429 ——
 * 而且失败位置取决于脚本里请求的先后顺序，看起来像是业务坏了。
 *
 * **生产环境不要设这个变量**：它调大的正是「拿着会话令牌刷管理接口」的窗口。
 * 解析失败或不是正整数时静默回落到默认值，不因为一个写错的变量把限流关掉。
 */
function adminRequestLimit(): number {
  const raw = Number(process.env.ADMIN_RATE_LIMIT ?? "");
  return Number.isSafeInteger(raw) && raw > 0 ? raw : 60;
}

/**
 * 内存滑动窗口计数器。
 *
 * 状态在进程内，重启即清空，也不跨实例共享 —— 与迁移前完全一致。
 * 不同用途必须使用不同的 key 前缀，避免互相挤占额度。
 */
@Injectable()
export class RateLimitService {
  private readonly attempts = new Map<string, { count: number; resetAt: number }>();
  private readonly adminLimit = adminRequestLimit();

  /**
   * 过期条目清扫的节流时间戳。
   *
   * `attempts` 的条目只在**同一个 key 再次命中**时才被覆盖（惰性重置），本身没有
   * 淘汰逻辑。而 `open-api` 桶按 `sha256(调用方给的 key)` 计数 —— 这是调用方可控的
   * 高基数值，伪造大量不同 key 会留下永不回收的条目，长期运行下 Map 单调增长。
   * 因此每隔一个窗口做一次全量清扫，把 `resetAt` 已过期的条目删掉，兜住内存。
   */
  private lastSweepAt = Date.now();
  private static readonly SWEEP_INTERVAL_MS = 5 * 60_000;

  /**
   * 清掉已过期的计数条目。节流到每 5 分钟最多一次：单次 allow 命中窗口边界时才触发，
   * 且删除操作在 Map 迭代中是安全的（已访问/当前项可删）。
   */
  private sweepExpired(now: number): void {
    if (now - this.lastSweepAt < RateLimitService.SWEEP_INTERVAL_MS) return;
    this.lastSweepAt = now;
    for (const [key, entry] of this.attempts) {
      if (entry.resetAt <= now) this.attempts.delete(key);
    }
  }

  private allow(key: string, limit: number, windowMs: number): boolean {
    const now = Date.now();
    this.sweepExpired(now);
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

  /**
   * 发布管理：按来源地址限流，防止静态令牌被暴力猜测。
   *
   * 额度见 [adminRequestLimit] —— 默认 60 次 / 15 分钟，`ADMIN_RATE_LIMIT` 可覆盖。
   */
  allowAdminRequest(address: string): boolean {
    return this.allow(`admin:${address}`, this.adminLimit, 15 * 60_000);
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

  /**
   * 音源账号的短信验证码：按来源地址 5 次 + 按手机号 3 次 / 15 分钟。
   *
   * 短信是**真实外发的计费资源**，而且这里的手机号由请求体指定 —— 只按地址限流
   * 挡不住「同一个管理员账号被接管后轮着给不同号码发」的刷短信模式。手机号维度
   * 的桶专门保护被填进来的号码不被反复骚扰，两者缺一不可。
   *
   * 不复用 `admin` 桶（60 次 / 15 分钟）：那是为发布管理这类纯内部写操作设的额度，
   * 拿来发短信等于把一个能被滥用的外发动作放进了宽额度里。
   */
  allowMusicSourceSms(address: string, phone: string): boolean {
    if (!this.allow(`music-source-sms-ip:${address}`, 5, 15 * 60_000)) return false;
    return this.allow(`music-source-sms-phone:${phone}`, 3, 15 * 60_000);
  }

  /**
   * 开放搜歌接口：按 API Key 120 次 + 按来源地址 600 次 / 15 分钟。
   *
   * 内层按 key 限制单个第三方调用方的总量；外层按地址兜底，防止伪造大量
   * key 从同一出口扫接口。地址阈值放宽是因为服务端调用方普遍共用 NAT。
   */
  allowOpenApiRequest(keyIdentity: string, address: string): boolean {
    if (!this.allow(`open-api-ip:${address}`, 600, 15 * 60_000)) return false;
    return this.allow(`open-api-key:${keyIdentity || address}`, 120, 15 * 60_000);
  }
}
