import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Patch, Post, Query, Req,
} from "@nestjs/common";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import {
  auditActorId, forwardedClientAddress, type AdminActor, type AdminAuthenticatedRequest,
} from "../common/request.types";
import { AdminAuthService } from "./admin-auth.service";
import { AdminGuarded } from "./admin-guarded.decorator";
import { AllowPendingPasswordChange } from "./allow-pending-password.decorator";
import { RequireRole } from "./roles.decorator";
import { ADMIN_ROLES, PRIVILEGED_READ_ROLES } from "./admin-roles";
import { AdminUsersRepository, type AdminUserRecord } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";
import { LdapService } from "../ldap/ldap.service";

/** 本地创建的管理员用户名。允许点、下划线、连字符，避免出现难排查的怪名字。 */
const USERNAME_PATTERN = /^[A-Za-z0-9._-]{3,64}$/;

@Public()
@Controller("admin/auth")
export class AdminAuthController {
  constructor(
    private readonly auth: AdminAuthService,
    private readonly adminUsers: AdminUsersRepository,
    private readonly sessions: AdminSessionsRepository,
    private readonly auditLog: AuditLogRepository,
    private readonly ldap: LdapService,
  ) {}

  // ==================== 登录 ====================

  /**
   * 第一步：校验密码。
   *
   * 顺序是「先 LDAP，未命中再回落本地密码」。回落是刻意保留的 break-glass 通道：
   * LDAP 配置错误或目录不可达时，本地超管账号仍能进后台救场；但**已被目录接管
   * 的账号**在目录不可达时不允许回落，否则「目录判定已停用」在目录抖动时失效。
   *
   * 开了 TOTP 的账号这里只签发一张挑战票据，不直接发会话。
   */
  @Public()
  @RateLimit("auth:admin-login")
  @Post("login")
  @HttpCode(HttpStatus.OK)
  async login(@Body() body: Record<string, unknown>, @Req() req: Request) {
    const username = String(body?.username ?? "").trim();
    const password = String(body?.password ?? "");
    const ip = this.extractIp(req);
    const userAgent = req.headers["user-agent"] ?? "";

    if (!username || !password) throw ApiErrors.unauthorized(4011, "用户名或密码错误");

    // 账号维度退避放在最前：LDAP 与本地两条路径都会被覆盖。只按 IP 限流挡不住
    // 代理池，而且 LDAP 路径被无限猜还会触发企业目录侧的账号锁定策略。
    // 用 4291 而不是 4290：与来源地址限流区分开，否则运维无法判断该换 IP 还是该等锁。
    if (this.auth.isAdminLoginLocked(username)) {
      throw ApiErrors.accountLocked();
    }

    const ldapResult = await this.ldap.authenticate(username, password);
    if (ldapResult.outcome === "denied") {
      this.auth.recordAdminLoginFailure(username);
      throw ApiErrors.unauthorized(4011, "用户名或密码错误");
    }
    if (ldapResult.outcome === "unavailable") {
      // 目录已用用户 DN 绑定成功、只是后续同步失败。身份已经确认过了，
      // 回落本地口令会让目录停用的账号借机登入，因此这里直接拒绝。
      throw ApiErrors.upstream("目录认证服务暂时不可用，请稍后重试");
    }
    if (ldapResult.outcome === "skipped" && this.ldap.isConfigured) {
      // 目录已配置却给不出结论（不可达，或目录里查无此人）。若该账号已被
      // 目录接管，就拒绝本地口令回落 —— 这正是「离职账号靠旧哈希登录」的入口。
      const local = await this.adminUsers.findByUsername(username);
      if (local?.auth_source === "ldap") {
        throw ApiErrors.serviceUnavailable(5031, "目录服务不可用，该账号不支持本地密码登录");
      }
    }

    let admin: AdminActor;
    let viaLdap = false;
    if (ldapResult.outcome === "success") {
      admin = ldapResult.admin;
      viaLdap = true;
    } else {
      const credentials = await this.adminUsers.findByUsername(username);
      // 用户名不存在时也跑一次等价 scrypt，否则响应时间会泄漏账号是否存在。
      if (!credentials) {
        this.auth.verifyAgainstNothing(password);
        this.auth.recordAdminLoginFailure(username);
        throw ApiErrors.unauthorized(4011, "用户名或密码错误");
      }
      if (!this.auth.verifyPassword(password, credentials.password_salt, credentials.password_hash)) {
        this.auth.recordAdminLoginFailure(username);
        throw ApiErrors.unauthorized(4011, "用户名或密码错误");
      }
      admin = {
        id: credentials.id,
        username: credentials.username,
        role: credentials.role,
        display_name: credentials.display_name,
        must_change_password: credentials.must_change_password,
      };
    }

    // 口令已验证通过，清掉该账号的失败计数，避免正常用户被历史失败拖累。
    this.auth.clearAdminLoginFailures(username);

    await this.assertIpAllowed(admin.id, ip);

    // 2FA：本地账号开了 TOTP 就只发挑战票据，正式会话留给 totp-verify。
    // LDAP 目录已经承担了第二因子的职责，不再叠加本地 TOTP。
    if (!viaLdap) {
      const credentials = await this.adminUsers.findCredentialsById(admin.id);
      if (credentials?.totp_enabled) {
        return {
          requires_totp: true,
          temp_token: this.auth.issueTotpChallenge(admin.id),
          admin_id: admin.id,
        };
      }
    }

    const sessionToken = await this.auth.createSession(admin.id, ip, userAgent);
    await this.adminUsers.updateLastLogin(admin.id, ip);
    await this.auditLog.log(admin.id, viaLdap ? "auth.login_ldap" : "auth.login",
      "admin_user", String(admin.id), null, ip, userAgent);

    return {
      token: sessionToken,
      admin: this.publicAdmin(admin),
    };
  }

  /**
   * 第二步：核销 TOTP 动态码。
   *
   * 必须同时出示第一步签发的 `temp_token`。没有它的话，任何知道 `admin_id`
   * 的人都能跳过密码直接进这一步猜 6 位码 —— 那等于 2FA 形同虚设。
   * 票据一次性使用，同一张票无法反复试码。
   */
  @Public()
  @RateLimit("auth:admin-totp")
  @Post("totp-verify")
  @HttpCode(HttpStatus.OK)
  async totpVerify(@Body() body: Record<string, unknown>, @Req() req: Request) {
    const tempToken = String(body?.temp_token ?? "");
    const token = String(body?.token ?? "");
    const ip = this.extractIp(req);
    const userAgent = req.headers["user-agent"] ?? "";

    const challengeAdminId = tempToken ? this.auth.consumeTotpChallenge(tempToken) : undefined;
    if (challengeAdminId === undefined) {
      throw ApiErrors.unauthorized(4011, "验证已过期，请重新登录");
    }

    // 传了 admin_id 就要求它与票据绑定的一致，防止拿别人的票据试探。
    const claimedAdminId = body?.admin_id === undefined ? undefined : Number(body.admin_id);
    if (claimedAdminId !== undefined && claimedAdminId !== challengeAdminId) {
      throw ApiErrors.unauthorized(4011, "验证失败");
    }

    const admin = await this.adminUsers.findCredentialsById(challengeAdminId);
    // 禁用、删号、或没开 TOTP 的账号都不能靠这张票拿到会话。
    if (!admin || admin.disabled_at || !admin.totp_enabled || !admin.totp_secret) {
      throw ApiErrors.unauthorized(4011, "验证失败");
    }
    if (!this.auth.verifyTotp(admin.totp_secret, token)) {
      throw ApiErrors.unauthorized(4011, "动态码错误");
    }

    await this.assertIpAllowed(admin.id, ip);

    const sessionToken = await this.auth.createSession(admin.id, ip, userAgent);
    await this.adminUsers.updateLastLogin(admin.id, ip);
    await this.auditLog.log(admin.id, "auth.login_totp", "admin_user", String(admin.id), null, ip, userAgent);

    return {
      token: sessionToken,
      admin: { id: admin.id, username: admin.username, display_name: admin.display_name, role: admin.role },
    };
  }

  @Post("logout")
  @HttpCode(HttpStatus.NO_CONTENT)
  async logout(@Req() req: Request): Promise<void> {
    const authHeader = String(req.headers["authorization"] ?? "");
    if (authHeader.startsWith("Bearer ")) {
      await this.auth.destroySession(authHeader.slice(7));
    }
  }

  // 改密前仍放行：前端要靠它判断该不该显示强制改密页。
  @AllowPendingPasswordChange()
  @AdminGuarded()
  @Get("me")
  async me(@Req() req: AdminAuthenticatedRequest) {
    return this.publicAdmin(this.requireActor(req));
  }

  // ==================== 本人密码与 2FA ====================

  // 改密前仍放行：这是唯一能解除强制改密状态的接口，拦掉它会把管理员锁死在后台外。
  @AllowPendingPasswordChange()
  @AdminGuarded()
  @RateLimit("auth:admin-password")
  @Post("change-password")
  @HttpCode(HttpStatus.NO_CONTENT)
  async changePassword(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const actor = this.requireActor(req);
    const oldPassword = String(body?.oldPassword ?? "");
    const newPassword = String(body?.newPassword ?? "");
    if (newPassword.length < 8) throw ApiErrors.badRequest(4000, "新密码至少8位");
    if (newPassword === oldPassword) throw ApiErrors.badRequest(4000, "新密码不能与当前密码相同");

    const admin = await this.adminUsers.findCredentialsById(actor.id);
    if (!admin || !this.auth.verifyPassword(oldPassword, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "当前密码错误");
    }
    const { hash, salt } = this.auth.hashPassword(newPassword);
    await this.adminUsers.updatePassword(admin.id, hash, salt);

    // 只留当前会话，其它设备下线；不把发起改密的这台一起踢掉。
    const authHeader = String(req.headers["authorization"] ?? "");
    if (authHeader.startsWith("Bearer ")) {
      await this.auth.revokeOtherSessions(admin.id, authHeader.slice(7));
    } else {
      await this.sessions.revokeAllForAdmin(admin.id);
    }

    await this.auditLog.log(admin.id, "auth.change_password", "admin_user", String(admin.id), null,
      this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  @AdminGuarded()
  @RateLimit("auth:admin-totp")
  @Post("totp-enable")
  @HttpCode(HttpStatus.OK)
  async enableTotp(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const actor = this.requireActor(req);
    const password = String(body?.password ?? "");
    const admin = await this.adminUsers.findCredentialsById(actor.id);
    if (!admin || !this.auth.verifyPassword(password, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "密码错误");
    }
    const { secret, otpauthUrl } = this.auth.generateTotpSecret(admin.username);
    // 先存密钥但保持未启用，等 totp-confirm 校验通过后才真正生效。
    await this.adminUsers.setTotpSecret(admin.id, secret, false);
    await this.auditLog.log(admin.id, "auth.totp_enable_requested", "admin_user", String(admin.id), null,
      this.extractIp(req), req.headers["user-agent"] ?? "");
    return { secret, otpauth_url: otpauthUrl };
  }

  @AdminGuarded()
  @RateLimit("auth:admin-totp")
  @Post("totp-confirm")
  @HttpCode(HttpStatus.NO_CONTENT)
  async confirmTotp(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const actor = this.requireActor(req);
    const token = String(body?.token ?? "");
    const admin = await this.adminUsers.findCredentialsById(actor.id);
    if (!admin || !admin.totp_secret) throw ApiErrors.badRequest(4000, "请先生成密钥");
    if (!this.auth.verifyTotp(admin.totp_secret, token)) {
      throw ApiErrors.badRequest(4000, "动态码错误");
    }
    await this.adminUsers.setTotpSecret(admin.id, admin.totp_secret, true);
    await this.auditLog.log(admin.id, "auth.totp_enabled", "admin_user", String(admin.id), null,
      this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  @AdminGuarded()
  @RateLimit("auth:admin-totp")
  @Post("totp-disable")
  @HttpCode(HttpStatus.NO_CONTENT)
  async disableTotp(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const actor = this.requireActor(req);
    const password = String(body?.password ?? "");
    const admin = await this.adminUsers.findCredentialsById(actor.id);
    if (!admin || !this.auth.verifyPassword(password, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "密码错误");
    }
    await this.adminUsers.setTotpSecret(admin.id, null, false);
    await this.auditLog.log(admin.id, "auth.totp_disabled", "admin_user", String(admin.id), null,
      this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  // ==================== 管理员 CRUD ====================

  /**
   * 管理员列表。
   *
   * admin 角色也需要它 —— 审计日志页要用它把 admin_id 映射成用户名。所以
   * 对非超管做字段裁剪：`ip_whitelist` 和 `last_login_ip` 属于网络访问控制
   * 信息，只给超管看。
   */
  @AdminGuarded()
  @Get("users")
  @RequireRole(...PRIVILEGED_READ_ROLES)
  async listAdmins(@Req() req: AdminAuthenticatedRequest) {
    const actor = this.requireActor(req);
    const isSuperAdmin = actor.role === "super_admin";
    const rows = await this.adminUsers.list();
    return rows.map((row) => (isSuperAdmin ? row : this.trimAdminRow(row)));
  }

  @AdminGuarded()
  @Post("users")
  @RequireRole("super_admin")
  @HttpCode(HttpStatus.CREATED)
  async createAdmin(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const actor = this.requireActor(req);
    const username = String(body?.username ?? "").trim();
    const password = String(body?.password ?? "");
    const displayName = String(body?.displayName ?? body?.display_name ?? "").trim() || username;
    const email = this.normalizeEmail(body?.email);
    const role = String(body?.role ?? "viewer");

    if (!USERNAME_PATTERN.test(username)) {
      throw ApiErrors.badRequest(4000, "用户名只能是 3-64 位的字母、数字、点、下划线或连字符");
    }
    if (password.length < 8) throw ApiErrors.badRequest(4000, "密码至少8位");
    if (!ADMIN_ROLES.includes(role)) throw ApiErrors.badRequest(4000, "角色不合法");

    const { hash, salt } = this.auth.hashPassword(password);
    const created = await this.adminUsers.create(username, hash, salt, displayName, role,
      auditActorId(actor), email);
    await this.auditLog.log(auditActorId(actor), "admin.create", "admin_user", String(created.id),
      JSON.stringify({ username, role }), this.extractIp(req), req.headers["user-agent"] ?? "");
    return created;
  }

  /** 编辑管理员。这是部分更新，用 PATCH —— 前端也按 PATCH 发。 */
  @AdminGuarded()
  @Patch("users/:id")
  @RequireRole("super_admin")
  async updateAdmin(@Req() req: AdminAuthenticatedRequest, @Param("id") id: string,
                    @Body() body: Record<string, unknown>) {
    const actor = this.requireActor(req);
    const targetId = this.parseId(id);
    const target = await this.adminUsers.findById(targetId);
    if (!target) throw ApiErrors.notFound(4044, "管理员不存在");

    const touchesRole = body?.role !== undefined;
    const touchesDisabled = body?.disabled !== undefined;
    const touchesProfile = body?.display_name !== undefined || body?.email !== undefined;
    if (!touchesRole && !touchesDisabled && !touchesProfile) {
      throw ApiErrors.badRequest(4000, "没有需要更新的字段");
    }

    const nextRole = touchesRole ? String(body.role) : target.role;
    if (touchesRole && !ADMIN_ROLES.includes(nextRole)) {
      throw ApiErrors.badRequest(4000, "角色不合法");
    }
    const nextDisabled = touchesDisabled ? Boolean(body.disabled) : Boolean(target.disabled_at);

    // 降级或禁用自己会当场把自己锁在门外，直接拒绝。
    if (targetId === actor.id && (nextRole !== target.role || nextDisabled)) {
      throw ApiErrors.badRequest(4000, "不能降级或禁用当前登录的账号");
    }
    // 最后一个可用超管一旦降级/禁用，后台就没人能再创建管理员了。
    if (target.role === "super_admin" && !target.disabled_at
        && (nextRole !== "super_admin" || nextDisabled)) {
      await this.assertNotLastSuperAdmin("降级或禁用");
    }

    if (touchesRole) await this.adminUsers.setRole(targetId, nextRole);
    if (touchesDisabled) await this.adminUsers.setDisabled(targetId, nextDisabled);
    if (touchesProfile) {
      const displayName = body?.display_name === undefined
        ? undefined
        : String(body.display_name).trim() || target.username;
      const email = body?.email === undefined ? undefined : this.normalizeEmail(body.email);
      await this.adminUsers.updateProfile(targetId, { displayName, email });
    }

    await this.auditLog.log(auditActorId(actor), "admin.update", "admin_user", String(targetId),
      JSON.stringify({ role: touchesRole ? nextRole : undefined, disabled: touchesDisabled ? nextDisabled : undefined }),
      this.extractIp(req), req.headers["user-agent"] ?? "");
    return this.adminUsers.findById(targetId);
  }

  @AdminGuarded()
  @Delete("users/:id")
  @RequireRole("super_admin")
  @HttpCode(HttpStatus.NO_CONTENT)
  async deleteAdmin(@Req() req: AdminAuthenticatedRequest, @Param("id") id: string): Promise<void> {
    const actor = this.requireActor(req);
    const targetId = this.parseId(id);
    if (targetId === actor.id) throw ApiErrors.badRequest(4000, "不能删除自己");

    const target = await this.adminUsers.findById(targetId);
    if (!target) throw ApiErrors.notFound(4044, "管理员不存在");
    if (target.role === "super_admin" && !target.disabled_at) {
      await this.assertNotLastSuperAdmin("删除");
    }

    await this.adminUsers.delete(targetId);
    await this.auditLog.log(auditActorId(actor), "admin.delete", "admin_user", String(targetId),
      JSON.stringify({ username: target.username }), this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  // ==================== 审计日志 ====================

  @AdminGuarded()
  @Get("audit-log")
  @RequireRole(...PRIVILEGED_READ_ROLES)
  async listAuditLog(@Query("adminId") adminId?: string,
                     @Query("action") action?: string, @Query("limit") limit?: string,
                     @Query("offset") offset?: string) {
    const parsedAdminId = adminId === undefined || adminId === "" ? undefined : Number(adminId);
    if (parsedAdminId !== undefined && (!Number.isInteger(parsedAdminId) || parsedAdminId <= 0)) {
      throw ApiErrors.badRequest(4000, "adminId 不合法");
    }
    return this.auditLog.list({
      adminId: parsedAdminId,
      action: action || undefined,
      limit: Math.min(200, Math.max(1, Number(limit) || 50)),
      offset: Math.max(0, Number(offset) || 0),
    });
  }

  // ==================== IP 白名单 ====================

  @AdminGuarded()
  @Get("ip-whitelist/:adminId")
  @RequireRole("super_admin")
  async getIpWhitelist(@Param("adminId") adminId: string) {
    const target = await this.adminUsers.findById(this.parseId(adminId));
    if (!target) throw ApiErrors.notFound(4044, "管理员不存在");
    return { whitelist: target.ip_whitelist || "" };
  }

  @AdminGuarded()
  @Post("ip-whitelist/:adminId")
  @RequireRole("super_admin")
  @HttpCode(HttpStatus.NO_CONTENT)
  async setIpWhitelist(@Req() req: AdminAuthenticatedRequest, @Param("adminId") adminId: string,
                       @Body() body: Record<string, unknown>): Promise<void> {
    const actor = this.requireActor(req);
    const targetId = this.parseId(adminId);
    const target = await this.adminUsers.findById(targetId);
    if (!target) throw ApiErrors.notFound(4044, "管理员不存在");

    const normalized = this.normalizeIpWhitelist(String(body?.whitelist ?? ""));
    await this.adminUsers.setIpWhitelist(targetId, normalized || null);
    await this.auditLog.log(auditActorId(actor), "admin.ip_whitelist", "admin_user", String(targetId),
      JSON.stringify({ whitelist: normalized }), this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  // ==================== 内部工具 ====================

  /**
   * 取当前管理员身份。
   *
   * 守卫保证这里一定有值；`id` 为 0 的是兼容令牌身份，它没有对应的
   * `admin_users` 行，所以按 id 查自身记录的接口对它自然返回 4011。
   */
  private requireActor(req: AdminAuthenticatedRequest): AdminActor {
    const actor = req.adminUser;
    if (!actor) throw ApiErrors.unauthorized(4013, "管理员认证失败");
    return actor;
  }

  private parseId(value: string): number {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw ApiErrors.badRequest(4000, "ID 不合法");
    return id;
  }

  private normalizeEmail(value: unknown): string | null {
    if (value === undefined || value === null || value === "") return null;
    const email = String(value).trim();
    if (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
      throw ApiErrors.badRequest(4000, "邮箱格式不正确");
    }
    return email;
  }

  private normalizeIpWhitelist(value: string): string {
    return value.split(",").map((item) => item.trim()).filter(Boolean).join(",");
  }

  /** 非超管可见的管理员字段：去掉网络访问控制相关的两列。 */
  private trimAdminRow(row: AdminUserRecord) {
    const { ip_whitelist: _ipWhitelist, last_login_ip: _lastLoginIp, ...rest } = row;
    return rest;
  }

  private async assertNotLastSuperAdmin(action: string): Promise<void> {
    if (await this.adminUsers.countActiveSuperAdmins() <= 1) {
      throw ApiErrors.badRequest(4000, `不能${action}最后一个超级管理员`);
    }
  }

  /** IP 白名单为空表示不限制；否则必须命中，不命中直接 403。 */
  private async assertIpAllowed(adminId: number, ip: string): Promise<void> {
    const admin = await this.adminUsers.findById(adminId);
    if (!admin?.ip_whitelist) return;
    const allowed = admin.ip_whitelist.split(",").map((item) => item.trim()).filter(Boolean);
    if (allowed.length === 0 || allowed.includes(ip)) return;
    throw ApiErrors.forbidden(4030, "当前 IP 不在白名单中");
  }

  /**
   * 取客户端地址。
   *
   * 与限流共用 [forwardedClientAddress]：只有显式开启 TRUST_PROXY 时才采信
   * `X-Forwarded-For`。以前这里无条件信任该请求头，等于谁都能靠一个头
   * 伪造出白名单里的 IP 绕过访问控制。
   */
  private extractIp(req: Request): string {
    return forwardedClientAddress(req);
  }

  /**
   * 对外暴露的管理员身份。
   *
   * 刻意逐字段挑选而不是直接把记录返回出去：`AdminCredentials` 里带着
   * `password_hash` / `password_salt` / `totp_secret`，整个对象返回等于把
   * 口令哈希和 2FA 密钥发给前端。
   *
   * `must_change_password` 转成布尔量给前端用，避免让客户端理解 0/1 约定。
   */
  private publicAdmin(admin: {
    id: number; username: string; display_name: string; role: string; must_change_password: number;
  }) {
    return {
      id: admin.id,
      username: admin.username,
      display_name: admin.display_name,
      role: admin.role,
      must_change_password: admin.must_change_password === 1,
    };
  }
}
