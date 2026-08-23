import { Injectable } from "@nestjs/common";
import { createHash, createHmac, randomBytes, scryptSync, timingSafeEqual } from "node:crypto";
import { AppConfigService } from "../config/app-config.service";
import { RefreshTokensRepository } from "./refresh-tokens.repository";
import { UsersRepository, type UserRecord } from "./users.repository";

export type TokenPair = { accessToken: string; refreshToken: string; expiresIn: number };

/**
 * 令牌与密码。
 *
 * 令牌格式刻意保持与迁移前一致，**没有换成 @nestjs/jwt**：
 * 访问令牌是 `base64url(payload).HMAC-SHA256(payload, AUTH_SECRET)`，
 * 换格式会让所有装机客户端手里的令牌立刻失效。
 */
@Injectable()
export class AuthService {
  constructor(
    private readonly config: AppConfigService,
    private readonly users: UsersRepository,
    private readonly refreshTokens: RefreshTokensRepository,
  ) {}

  /** scrypt 参数与迁移前一致，否则已注册用户的密码会全部校验失败。 */
  hashPassword(password: string, salt = randomBytes(16).toString("hex")): { salt: string; hash: string } {
    return { salt, hash: this.scrypt(password, salt).toString("hex") };
  }

  verifyPassword(password: string, salt: string, expected: string): boolean {
    try {
      const actual = this.scrypt(password, salt);
      const target = Buffer.from(expected, "hex");
      return actual.length === target.length && timingSafeEqual(actual, target);
    } catch {
      return false;
    }
  }

  async issueTokens(user: UserRecord): Promise<TokenPair> {
    const now = Math.floor(Date.now() / 1000);
    const payload = Buffer.from(
      JSON.stringify({ sub: user.id, iat: now, exp: now + this.config.accessLifetimeSeconds, typ: "access" }),
    ).toString("base64url");
    const accessToken = `${payload}.${this.sign(payload)}`;
    const refreshToken = randomBytes(48).toString("base64url");
    await this.refreshTokens.save(
      user.id,
      this.hashToken(refreshToken),
      Date.now() + this.config.refreshLifetimeSeconds * 1000,
    );
    return { accessToken, refreshToken, expiresIn: this.config.accessLifetimeSeconds };
  }

  /** 用刷新令牌换取新的令牌对；令牌无效或已过期时返回 undefined。 */
  async rotate(refreshToken: string): Promise<TokenPair | undefined> {
    const record = await this.refreshTokens.consume(this.hashToken(refreshToken));
    const user = record && (await this.users.findById(record.user_id));
    return user ? this.issueTokens(user) : undefined;
  }

  async revokeRefreshToken(refreshToken: string): Promise<void> {
    await this.refreshTokens.revoke(this.hashToken(refreshToken));
  }

  /**
   * 校验访问令牌。返回 undefined 表示无效，调用方必须据此返回 **401**。
   * 客户端只对 401 触发续期重放，返回 403 会让自动续期彻底失效。
   */
  async authenticate(authorization: string | undefined): Promise<UserRecord | undefined> {
    const token = (authorization ?? "").replace(/^Bearer\s+/i, "");
    const [payload, signature] = token.split(".");
    if (!payload || !signature) return undefined;
    const expected = this.sign(payload);
    if (signature.length !== expected.length) return undefined;
    if (!timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) return undefined;

    // try 只包住解码：数据库异常绝不能被吞成 401，那会触发客户端的续期重放；
    // 故障期间应该落到 5xx，客户端才会保留令牌稍后重试。
    let data: { sub: number; exp: number; typ: string } | null;
    try {
      data = JSON.parse(Buffer.from(payload, "base64url").toString());
    } catch {
      return undefined;
    }
    if (!data || data.typ !== "access" || data.exp <= Date.now() / 1000) return undefined;
    return this.users.findById(data.sub);
  }

  /** 请求体字段可能是任意类型，统一收敛成字符串再校验，与迁移前的 usernameOf/passwordOf 一致。 */
  textOf(value: unknown, trim = false): string {
    if (typeof value !== "string") return "";
    return trim ? value.trim() : value;
  }

  private scrypt(password: string, salt: string): Buffer {
    return scryptSync(password, salt, 64, { N: 65536, r: 8, p: 1, maxmem: 128 * 1024 * 1024 });
  }

  private sign(payload: string): string {
    return createHmac("sha256", this.config.authSecret).update(payload).digest("base64url");
  }

  private hashToken(token: string): string {
    return createHash("sha256").update(token).digest("hex");
  }
}
