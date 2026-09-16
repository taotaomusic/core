import { Injectable } from "@nestjs/common";
import { createHash, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import * as speakeasy from "speakeasy";
import { AppConfigService } from "../config/app-config.service";
import { AdminUsersRepository, type AdminCredentials } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";

const SESSION_LIFETIME_MS = 24 * 60 * 60 * 1000; // 24小时

@Injectable()
export class AdminAuthService {
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

  generateTotpSecret(username: string): { secret: string; otpauthUrl: string } {
    const secret = speakeasy.generateSecret({ name: `${this.config.totpIssuer} (${username})`, length: 20 });
    return { secret: secret.base32, otpauthUrl: secret.otpauth_url! };
  }

  verifyTotp(secret: string, token: string): boolean {
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

  async logAction(adminId: number, action: string, targetType: string | null, targetId: string | null,
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