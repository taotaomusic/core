import { Injectable } from "@nestjs/common";
import { createHash, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import * as speakeasy from "speakeasy";
import { AppConfigService } from "../config/app-config.service";
import { AdminUsersRepository } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";

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

  async validateSession(token: string): Promise<{ id: number; username: string; role: string; display_name: string } | undefined> {
    const session = await this.sessions.findActive(this.hashToken(token));
    if (!session) return undefined;
    const admin = await this.adminUsers.findById(session.admin_id);
    if (!admin || admin.disabled_at) return undefined;
    return { id: admin.id, username: admin.username, role: admin.role, display_name: admin.display_name };
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

  generateTotpSecret(username: string): { secret: string; otpauthUrl: string } {
    const secret = speakeasy.generateSecret({ name: `${this.config.totpIssuer} (${username})`, length: 20 });
    return { secret: secret.base32, otpauthUrl: secret.otpauth_url! };
  }

  verifyTotp(secret: string, token: string): boolean {
    // 先挡掉长度不对的输入，避免把任意字符串喂给校验器。
    if (!/^\d{6}$/.test(token)) return false;
    return speakeasy.totp.verify({ secret, encoding: "base32", token, window: 1 });
  }

  /** 向后兼容：验证 .env 中的 ADMIN_TOKEN */
  verifyLegacyToken(provided: string): boolean {
    const expected = this.config.adminToken;
    if (!expected) return false;
    const a = createHash("sha256").update(provided).digest();
    const b = createHash("sha256").update(expected).digest();
    return timingSafeEqual(a, b);
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
