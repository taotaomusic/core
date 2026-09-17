import { Injectable } from "@nestjs/common";
import { createHash, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { AppConfigService } from "../config/app-config.service";
import { generateTotpSecret, verifyTotpCode } from "./totp";
import { AdminUsersRepository } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";
import type { AdminActor } from "../common/request.types";

const SESSION_LIFETIME_MS = 24 * 60 * 60 * 1000; // 24小时

/** 2FA 挑战票据的有效期。只够用户从密码框切到验证器，不该更长。 */
const TOTP_CHALLENGE_LIFETIME_MS = 5 * 60 * 1000;

/**
 * 时序均衡用的假盐。
 *
 * 用户名不存在时如果直接返回，就比「存在但密码错」少跑一次 scrypt，
 * 响应时间差出一个数量级，等于把管理员用户名送出去。所以两条路径
 * 都必须付出同样的 scrypt 代价。
 */
const TIMING_DECOY_SALT = "0".repeat(32);

@Injectable()
export class AdminAuthService {
  /**
   * 待验证的 2FA 挑战票据。
   *
   * 存内存而非数据库：它只活 5 分钟、用后即焚，重启即失效正好是期望行为
   * —— 和 [RateLimitService] 一样，不跨实例共享。
   */
  private readonly totpChallenges = new Map<string, { adminId: number; expiresAt: number }>();

  /**
   * 管理端登录失败的账号维度计数与退避。
   *
   * 只按 IP 限流挡不住代理池：攻击者换 IP 就能对已知的 `admin` 账号做无限
   * 口令猜测；对 LDAP 路径更糟，还可能触发企业目录侧的账号锁定策略，把
   * 「猜密码」升级成对目录的拒绝服务。所以再加一层以账号为键的退避。
   *
   * `failures` 与 `lockouts` 必须分开记：前者是「本轮还能试几次」，退避期一过
   * 就清零，否则正常管理员在第一次被锁之后打错一个字就又挨 5 分钟；后者是
   * 「历史上被锁过几轮」，只增不减，用来把退避时长逐轮翻倍。
   *
   * 与 [RateLimitService]、2FA 票据一样是**进程内 Map**：重启即清空、不跨
   * 实例共享。多实例部署需要迁到共享存储，详见 wiki 的「单实例假设」。
   */
  private readonly adminLoginFailures = new Map<string, {
    failures: number;
    lockouts: number;
    lockedUntil: number;
  }>();

  /** 连续失败达到这个次数才开始退避。 */
  private static readonly LOGIN_FAILURE_THRESHOLD = 5;
  /** 首轮退避时长；之后每多一轮翻倍。 */
  private static readonly LOGIN_BASE_LOCK_MS = 5 * 60_000;
  /** 退避时长上限，避免指数增长到事实上永久锁定。 */
  private static readonly LOGIN_MAX_LOCK_MS = 30 * 60_000;

  constructor(
    private readonly config: AppConfigService,
    private readonly adminUsers: AdminUsersRepository,
    private readonly sessions: AdminSessionsRepository,
    private readonly auditLog: AuditLogRepository,
  ) {}

  hashPassword(password: string, salt = randomBytes(16).toString("hex")): { salt: string; hash: string } {
    return { salt, hash: this.scrypt(password, salt).toString("hex") };
  }

  verifyPassword(password: string, salt: string, expected: string): boolean {
    try {
      const actual = this.scrypt(password, salt);
      const target = Buffer.from(expected, "hex");
      return actual.length === target.length && timingSafeEqual(actual, target);
    } catch { return false; }
  }

  /**
   * 在「用户名不存在」的分支上消耗一次等价的计算量，抹平与
   * 「用户存在但密码错」之间的时间差。恒返回 false，方便直接串进
   * `if (!admin || !this.verifyPassword(...))` 那种判断里。
   */
  verifyAgainstNothing(password: string): false {
    this.scrypt(password, TIMING_DECOY_SALT);
    return false;
  }

  async createSession(adminId: number, ip: string, userAgent: string): Promise<string> {
    const token = randomBytes(48).toString("base64url");
    const tokenHash = this.hashToken(token);
    const expiresAt = Date.now() + SESSION_LIFETIME_MS;
    await this.sessions.create(adminId, tokenHash, expiresAt, ip, userAgent);
    return token;
  }

  async validateSession(token: string): Promise<AdminActor | undefined> {
    const session = await this.sessions.findActive(this.hashToken(token));
    if (!session) return undefined;
    const admin = await this.adminUsers.findById(session.admin_id);
    if (!admin || admin.disabled_at) return undefined;
    return {
      id: admin.id,
      username: admin.username,
      role: admin.role,
      display_name: admin.display_name,
      must_change_password: admin.must_change_password,
    };
  }

  async destroySession(token: string): Promise<void> {
    await this.sessions.revoke(this.hashToken(token));
  }

  /**
   * 撤销该管理员的其它会话，保留当前这条。
   *
   * 改密码后把本人也踢下线体验很差（接口返回 204，前端不会跳登录页），
   * 但放着其它设备不管又不安全，所以只留当前会话。
   */
  async revokeOtherSessions(adminId: number, currentToken: string): Promise<void> {
    await this.sessions.revokeAllExcept(adminId, this.hashToken(currentToken));
  }

  // ==================== 登录失败退避 ====================

  /**
   * 账号维度退避的归一化键。
   *
   * 统一转小写并去空白，否则 `Admin` / `admin ` 会被当成两个不同的账号，
   * 各拿一份失败额度，退避形同虚设。
   */
  private loginFailureKey(username: string): string {
    return username.trim().toLowerCase();
  }

  /**
   * 该账号是否仍在退避期内。
   *
   * 退避期一过就**只清 `failures`、保留 `lockouts`**：清掉 failures 让正常
   * 管理员重新拿到完整的尝试额度，保留 lockouts 让反复失败的账号下一轮锁得
   * 更久。反过来（两个都清）会让指数升级永远停在第一档；只清 lockedUntil
   * 不清 failures 则会让「被锁过之后打错一次就再锁 5 分钟」，对正常人也过于苛刻。
   */
  isAdminLoginLocked(username: string): boolean {
    const key = this.loginFailureKey(username);
    const entry = this.adminLoginFailures.get(key);
    if (!entry) return false;
    if (entry.lockedUntil > Date.now()) return true;
    // 注意：`lockedUntil` 为 0 表示「还没到过阈值」，此时**绝不能**删条目 ——
    // 那等于每次登录都把失败计数清零，阈值永远达不到，退避形同虚设。
    if (entry.lockedUntil > 0) {
      entry.lockedUntil = 0;
      entry.failures = 0;
    }
    return false;
  }

  /**
   * 记录一次登录失败，达到阈值后按轮次指数延长退避。
   *
   * 第 1 轮 5 分钟，第 2 轮 10 分钟，第 3 轮 20 分钟，之后封顶 30 分钟。
   */
  recordAdminLoginFailure(username: string): void {
    const key = this.loginFailureKey(username);
    const entry = this.adminLoginFailures.get(key)
      ?? { failures: 0, lockouts: 0, lockedUntil: 0 };
    entry.failures += 1;
    const threshold = AdminAuthService.LOGIN_FAILURE_THRESHOLD;
    if (entry.failures >= threshold) {
      entry.lockouts += 1;
      const lockMs = Math.min(
        AdminAuthService.LOGIN_MAX_LOCK_MS,
        AdminAuthService.LOGIN_BASE_LOCK_MS * 2 ** (entry.lockouts - 1),
      );
      entry.lockedUntil = Date.now() + lockMs;
    }
    this.adminLoginFailures.set(key, entry);
  }

  /** 登录成功后清零该账号的失败计数。 */
  clearAdminLoginFailures(username: string): void {
    this.adminLoginFailures.delete(this.loginFailureKey(username));
  }

  // ==================== 2FA 挑战票据 ====================

  /**
   * 签发一张 2FA 挑战票据。
   *
   * 密码通过但账号开了 TOTP 时走这条路。票据把「第一因子已验证」绑定到一个
   * 短命、一次性的凭据上，第二步必须出示它才能换正式会话 —— 否则任何人只要
   * 知道 admin_id 就能跳过密码直接进第二步猜动态码。
   */
  issueTotpChallenge(adminId: number): string {
    this.pruneExpiredChallenges();
    const token = randomBytes(32).toString("base64url");
    this.totpChallenges.set(token, { adminId, expiresAt: Date.now() + TOTP_CHALLENGE_LIFETIME_MS });
    return token;
  }

  /**
   * 核销挑战票据并返回其绑定的 admin_id。
   *
   * 无论后续动态码对不对，票据都在这里被删除，所以一张票据只能尝试一次
   * —— 这挡住了「拿同一张票反复猜动态码」的路径。
   */
  consumeTotpChallenge(token: string): number | undefined {
    this.pruneExpiredChallenges();
    const challenge = this.totpChallenges.get(token);
    if (!challenge) return undefined;
    this.totpChallenges.delete(token);
    if (challenge.expiresAt <= Date.now()) return undefined;
    return challenge.adminId;
  }

  private pruneExpiredChallenges(): void {
    const now = Date.now();
    for (const [token, challenge] of this.totpChallenges) {
      if (challenge.expiresAt <= now) this.totpChallenges.delete(token);
    }
  }

  /**
   * 生成新的 TOTP 密钥与 otpauth URL。
   *
   * 实现在 `./totp`，不再依赖已停止维护的 `speakeasy`。库里已存的 base32
   * 密钥仍由同一套解码逻辑校验，无需迁移数据。
   */
  generateTotpSecret(username: string): { secret: string; otpauthUrl: string } {
    return generateTotpSecret(this.config.totpIssuer, username);
  }

  verifyTotp(secret: string, token: string): boolean {
    return verifyTotpCode(secret, token);
  }

  async logAction(adminId: number | null, action: string, targetType: string | null, targetId: string | null,
                  detail: string | null, ip: string, userAgent: string): Promise<void> {
    await this.auditLog.log(adminId, action, targetType, targetId, detail, ip, userAgent);
  }

  private hashToken(token: string): string {
    return createHash("sha256").update(token).digest("hex");
  }

  private scrypt(password: string, salt: string): Buffer {
    return scryptSync(password, salt, 64, { N: 65536, r: 8, p: 1, maxmem: 128 * 1024 * 1024 });
  }
}
