import { Injectable } from "@nestjs/common";
import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";
import { AppConfigService } from "../config/app-config.service";
import { ApiErrors } from "../common/api.exception";
import { AdminAuthService } from "./admin-auth.service";
import { type AdminCredentials, AdminUsersRepository } from "./admin-users.repository";

/** GitHub OAuth 授权 / 换 token / 查用户的固定端点。 */
const GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
const GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
const GITHUB_API_BASE = "https://api.github.com";

/**
 * state 的有效期。只够用户在 GitHub 上完成一次授权跳转，不该更长 ——
 * state 是绑定「浏览器里的这一趟」与「回调里的那一趟」的唯一凭证，活的越久
 * 被拦截重放的空间越大。
 */
const STATE_LIFETIME_MS = 10 * 60 * 1000;

/** 授权回调的类型：登录会话还是本人绑定。两种都靠同一套 HMAC 签名带上。 */
type OAuthState = { kind: "login" | "bind"; adminId?: number; nonce: string; exp: number };

/** GitHub `/user` 里我们关心的最小字段。 */
interface GithubUser { id: number; login: string; }

interface GithubTokenResponse {
  access_token?: string;
  error?: string;
  error_description?: string;
}

/**
 * GitHub OAuth 登录与绑定的服务。
 *
 * 它只管「用 GitHub 证明你是某个 GitHub 账号」，不负责建管理员账号：
 * 登录时按 `github_id` 找已绑定的本地管理员，绑定也只操作当前登录账号
 * 自己的 `github_id` 三列。角色、禁用、IP 白名单等一律沿用本地账号的
 * 既有判定，GitHub 只是多一条**进入**的门。
 */
@Injectable()
export class GithubOauthService {
  constructor(
    private readonly config: AppConfigService,
    private readonly auth: AdminAuthService,
    private readonly adminUsers: AdminUsersRepository,
  ) {}

  /** 三个必填字段齐备才算启用，未启用时入口与接口都不该出现。 */
  get configured(): boolean {
    return this.config.isGithubOauthConfigured;
  }

  // ==================== state（HMAC 签名） ====================

  private sign(payload: string): string {
    return createHmac("sha256", this.config.authSecret).update(payload).digest("base64url");
  }

  createState(kind: "login" | "bind", adminId?: number): string {
    const state: OAuthState = {
      kind,
      nonce: randomBytes(16).toString("base64url"),
      exp: Date.now() + STATE_LIFETIME_MS,
    };
    if (kind === "bind") state.adminId = adminId;
    const payload = Buffer.from(JSON.stringify(state), "utf8").toString("base64url");
    return `${payload}.${this.sign(payload)}`;
  }

  /**
   * 校验 state 的签名与有效期，返回还原出的内容。
   *
   * 签名用 `timingSafeEqual` 比较，避免按长短或逐字节短路把签名泄漏出去。
   */
  private verifyState(state: string): OAuthState {
    const dot = state.lastIndexOf(".");
    if (dot <= 0) throw ApiErrors.badRequest(4000, "GitHub 授权 state 无效");
    const payload = state.slice(0, dot);
    const signature = state.slice(dot + 1);
    const expected = Buffer.from(this.sign(payload));
    const actual = Buffer.from(signature);
    if (expected.length !== actual.length || !timingSafeEqual(expected, actual)) {
      throw ApiErrors.badRequest(4000, "GitHub 授权 state 无效");
    }

    let parsed: OAuthState;
    try {
      parsed = JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as OAuthState;
    } catch {
      throw ApiErrors.badRequest(4000, "GitHub 授权 state 无效");
    }
    if (!parsed || (parsed.kind !== "login" && parsed.kind !== "bind") || typeof parsed.exp !== "number"
        || (parsed.kind === "bind" && (!Number.isInteger(parsed.adminId) || (parsed.adminId ?? 0) <= 0))) {
      throw ApiErrors.badRequest(4000, "GitHub 授权 state 无效");
    }
    if (parsed.exp <= Date.now()) throw ApiErrors.badRequest(4000, "GitHub 授权已过期，请重试");
    return parsed;
  }

  // ==================== 授权 URL ====================

  /** 配置了组织限制才要 `read:org`，避免对普通用户多申请一个作用域。 */
  private scope(): string {
    return this.config.githubOauthOrganizations.length > 0 ? "read:user read:org" : "read:user";
  }

  buildAuthorizeUrl(state: string): string {
    const params = new URLSearchParams({
      client_id: this.config.githubOauthClientId,
      redirect_uri: this.config.githubOauthRedirectUri,
      scope: this.scope(),
      state,
    });
    return `${GITHUB_AUTHORIZE_URL}?${params.toString()}`;
  }

  beginLogin(): { url: string } {
    return { url: this.buildAuthorizeUrl(this.createState("login")) };
  }

  beginBind(adminId: number): { url: string } {
    return { url: this.buildAuthorizeUrl(this.createState("bind", adminId)) };
  }

  // ==================== GitHub API ====================

  /** 用授权码换访问令牌。所有网络失败都归成 502，不把细节透给终端用户。 */
  private async exchangeCode(code: string): Promise<string> {
    let response: Response;
    try {
      response = await fetch(GITHUB_TOKEN_URL, {
        method: "POST",
        headers: { "accept": "application/json", "content-type": "application/json" },
        body: JSON.stringify({
          client_id: this.config.githubOauthClientId,
          client_secret: this.config.githubOauthClientSecret,
          code,
          redirect_uri: this.config.githubOauthRedirectUri,
        }),
      });
    } catch {
      throw ApiErrors.upstream("GitHub 授权服务暂时不可用，请稍后重试");
    }
    const body = (await response.json().catch(() => ({}))) as GithubTokenResponse;
    if (!response.ok || !body.access_token) {
      throw ApiErrors.unauthorized(4011, `GitHub 授权失败：${body.error_description || body.error || "无效的授权码"}`);
    }
    return body.access_token;
  }

  /** 拿访问令牌到 /user 取账号的稳定 ID 与 login。 */
  private async fetchUser(accessToken: string): Promise<GithubUser> {
    let response: Response;
    try {
      response = await fetch(`${GITHUB_API_BASE}/user`, {
        headers: {
          "authorization": `Bearer ${accessToken}`,
          "accept": "application/vnd.github+json",
          "x-github-api-version": "2022-11-28",
          "user-agent": "taotao-music-admin",
        },
      });
    } catch {
      throw ApiErrors.upstream("GitHub 授权服务暂时不可用，请稍后重试");
    }
    if (!response.ok) throw ApiErrors.unauthorized(4011, "获取 GitHub 用户信息失败");
    const user = (await response.json()) as { id: number; login: string };
    if (!user || typeof user.id !== "number" || !user.login) {
      throw ApiErrors.unauthorized(4011, "GitHub 用户信息无效");
    }
    return { id: user.id, login: user.login };
  }

  /** 配置了组织限制时，校验该账号是否属于某个允许的组织。 */
  private async assertOrgMembership(accessToken: string, login: string): Promise<void> {
    const wanted = this.config.githubOauthOrganizations;
    if (wanted.length === 0) return;
    let response: Response;
    try {
      response = await fetch(`${GITHUB_API_BASE}/user/orgs?per_page=100`, {
        headers: {
          "authorization": `Bearer ${accessToken}`,
          "accept": "application/vnd.github+json",
          "x-github-api-version": "2022-11-28",
          "user-agent": "taotao-music-admin",
        },
      });
    } catch {
      throw ApiErrors.upstream("GitHub 授权服务暂时不可用，请稍后重试");
    }
    if (!response.ok) throw ApiErrors.unauthorized(4011, "获取 GitHub 组织成员信息失败");
    const orgs = (await response.json()) as Array<{ login: string }>;
    const memberOf = new Set(orgs.map((org) => org.login.toLowerCase()));
    if (!wanted.some((org) => memberOf.has(org))) {
      throw ApiErrors.forbidden(4030, `GitHub 账号 ${login} 不属于允许登录的组织`);
    }
  }

  // ==================== 业务流 ====================

  /**
   * 回调统一入口：校验 state、换令牌、取用户、查组织，然后按 state 的类型
   * 走「登录签发会话」或「绑定当前账号」两条路。
   */
  async handleCallback(
    code: string,
    state: string,
    ip: string,
    userAgent: string,
  ): Promise<{ kind: "login"; token: string } | { kind: "bind"; githubLogin: string }> {
    const parsed = this.verifyState(state);
    const accessToken = await this.exchangeCode(code);
    const user = await this.fetchUser(accessToken);
    await this.assertOrgMembership(accessToken, user.login);

    if (parsed.kind === "login") {
      const token = await this.completeLogin(String(user.id), ip, userAgent);
      return { kind: "login", token };
    }
    const githubLogin = await this.completeBind(parsed.adminId!, user, ip, userAgent);
    return { kind: "bind", githubLogin };
  }

  /** GitHub 登录：找到绑定账号并直接签发会话，不再叠加本地 TOTP。 */
  private async completeLogin(githubId: string, ip: string, userAgent: string): Promise<string> {
    const admin = await this.adminUsers.findByGithubId(githubId);
    if (!admin) throw ApiErrors.unauthorized(4011, "该 GitHub 账号尚未绑定任何管理员");
    if (admin.disabled_at) throw ApiErrors.forbidden(4030, "该管理员账号已被禁用");
    await this.auth.assertIpAllowed(admin.id, ip);
    const token = await this.auth.createSession(admin.id, ip, userAgent);
    await this.adminUsers.updateLastLogin(admin.id, ip);
    await this.auth.logAction(admin.id, "auth.login_github", "admin_user", String(admin.id), null, ip, userAgent);
    return token;
  }

  /** 绑定：一个 GitHub 账号至多绑一个管理员，撞唯一约束转成 4091。 */
  private async completeBind(adminId: number, user: GithubUser, ip: string, userAgent: string): Promise<string> {
    const admin = await this.adminUsers.findById(adminId);
    if (!admin || admin.disabled_at) throw ApiErrors.forbidden(4030, "管理员账号不可用");
    await this.adminUsers.bindGithub(adminId, String(user.id), user.login);
    await this.auth.logAction(adminId, "auth.github_bind", "admin_user", String(adminId),
      JSON.stringify({ github_login: user.login }), ip, userAgent);
    return user.login;
  }

  /** 绑定 / 解绑前的口令确认：LDAP 接管的账号没有本地口令，会话即足够。 */
  async verifyBindPassword(adminId: number, password: string): Promise<AdminCredentials> {
    const admin = await this.adminUsers.findCredentialsById(adminId);
    if (!admin || admin.disabled_at) throw ApiErrors.unauthorized(4013, "管理员认证失败");
    if (admin.auth_source === "ldap") return admin;
    if (!this.auth.verifyPassword(password, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "当前密码错误");
    }
    return admin;
  }

  async unbind(adminId: number): Promise<void> {
    await this.adminUsers.unbindGithub(adminId);
  }
}