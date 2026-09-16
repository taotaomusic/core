import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query, Req, UseGuards } from "@nestjs/common";
import type { Request } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminAuthService } from "./admin-auth.service";
import { AdminAuthGuard } from "./admin-auth.guard";
import { AdminUsersRepository } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";
import { AppConfigService } from "../config/app-config.service";

@Public()
@UseGuards(AdminAuthGuard)
@Controller("admin/auth")
export class AdminAuthController {
  constructor(
    private readonly auth: AdminAuthService,
    private readonly adminUsers: AdminUsersRepository,
    private readonly sessions: AdminSessionsRepository,
    private readonly auditLog: AuditLogRepository,
    private readonly config: AppConfigService,
  ) {}

  @Public()
  @RateLimit("auth:admin-login")
  @Post("login")
  @HttpCode(HttpStatus.OK)
  async login(@Body() body: Record<string, unknown>, @Req() req: Request) {
    const username = String(body?.username ?? "").trim();
    const password = String(body?.password ?? "");
    const ip = this.extractIp(req);
    const userAgent = req.headers["user-agent"] ?? "";

    // 尝试 LDAP 登录（如果配置了）
    if (this.config.isLdapConfigured && !password.startsWith("__legacy__")) {
      // LDAP 逻辑由 LdapService 处理（如果存在）
    }

    const admin = await this.adminUsers.findByUsername(username);
    if (!admin || !this.auth.verifyPassword(password, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "用户名或密码错误");
    }

    // IP 白名单检查
    if (admin.ip_whitelist && !this.isIpAllowed(ip, admin.ip_whitelist)) {
      throw ApiErrors.forbidden(4030, "当前 IP 不在白名单中");
    }

    // 2FA 检查
    if (admin.totp_enabled) {
      const tempToken = this.auth.hashPassword(username + Date.now()).hash.slice(0, 32);
      // 存储临时 token 到内存（5分钟有效），这里简化处理
      return { requires_totp: true, temp_token: tempToken, admin_id: admin.id };
    }

    const sessionToken = await this.auth.createSession(admin.id, ip, userAgent);
    await this.adminUsers.updateLastLogin(admin.id, ip);
    await this.auditLog.log(admin.id, "auth.login", "admin_user", String(admin.id), null, ip, userAgent);

    return {
      token: sessionToken,
      admin: { id: admin.id, username: admin.username, display_name: admin.display_name, role: admin.role },
    };
  }

  @Public()
  @Post("totp-verify")
  @HttpCode(HttpStatus.OK)
  async totpVerify(@Body() body: Record<string, unknown>, @Req() req: Request) {
    const adminId = Number(body?.admin_id);
    const token = String(body?.token ?? "");
    const ip = this.extractIp(req);
    const userAgent = req.headers["user-agent"] ?? "";

    const admin = await this.adminUsers.findById(adminId);
    if (!admin || !admin.totp_secret) throw ApiErrors.unauthorized(4011, "验证失败");

    if (!this.auth.verifyTotp(admin.totp_secret, token)) {
      throw ApiErrors.unauthorized(4011, "动态码错误");
    }

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

  @Get("me")
  async me(@Req() req: Request) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    return adminUser;
  }

  @Post("change-password")
  @HttpCode(HttpStatus.NO_CONTENT)
  async changePassword(@Req() req: Request, @Body() body: Record<string, unknown>) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    const oldPassword = String(body?.oldPassword ?? "");
    const newPassword = String(body?.newPassword ?? "");
    if (newPassword.length < 8) throw ApiErrors.badRequest(4000, "新密码至少8位");
    const admin = await this.adminUsers.findByUsername(adminUser.username);
    if (!admin || !this.auth.verifyPassword(oldPassword, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "当前密码错误");
    }
    const { hash, salt } = this.auth.hashPassword(newPassword);
    await this.adminUsers.updatePassword(admin.id, hash, salt);
    await this.sessions.revokeAllForAdmin(admin.id);
    await this.auditLog.log(admin.id, "auth.change_password", "admin_user", String(admin.id), null,
      this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  @Post("totp-enable")
  @HttpCode(HttpStatus.OK)
  async enableTotp(@Req() req: Request, @Body() body: Record<string, unknown>) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    const password = String(body?.password ?? "");
    const admin = await this.adminUsers.findByUsername(adminUser.username);
    if (!admin || !this.auth.verifyPassword(password, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "密码错误");
    }
    const { secret, otpauthUrl } = this.auth.generateTotpSecret(admin.username);
    await this.adminUsers.setTotpSecret(admin.id, secret, false);
    return { secret, otpauth_url: otpauthUrl };
  }

  @Post("totp-confirm")
  @HttpCode(HttpStatus.NO_CONTENT)
  async confirmTotp(@Req() req: Request, @Body() body: Record<string, unknown>) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    const token = String(body?.token ?? "");
    const admin = await this.adminUsers.findByUsername(adminUser.username);
    if (!admin || !admin.totp_secret) throw ApiErrors.badRequest(4000, "请先生成密钥");
    if (!this.auth.verifyTotp(admin.totp_secret, token)) {
      throw ApiErrors.badRequest(4000, "动态码错误");
    }
    await this.adminUsers.setTotpSecret(admin.id, admin.totp_secret, true);
  }

  @Post("totp-disable")
  @HttpCode(HttpStatus.NO_CONTENT)
  async disableTotp(@Req() req: Request, @Body() body: Record<string, unknown>) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    const password = String(body?.password ?? "");
    const admin = await this.adminUsers.findByUsername(adminUser.username);
    if (!admin || !this.auth.verifyPassword(password, admin.password_salt, admin.password_hash)) {
      throw ApiErrors.unauthorized(4011, "密码错误");
    }
    await this.adminUsers.setTotpSecret(admin.id, null, false);
  }

  // ==================== 管理员 CRUD ====================

  @Get("users")
  async listAdmins() {
    return this.adminUsers.list();
  }

  @Post("users")
  @HttpCode(HttpStatus.CREATED)
  async createAdmin(@Req() req: Request, @Body() body: Record<string, unknown>) {
    const adminUser = (req as any).adminUser;
    if (!adminUser || adminUser.role !== "super_admin") {
      throw ApiErrors.forbidden(4030, "仅超级管理员可创建管理员");
    }
    const username = String(body?.username ?? "").trim();
    const password = String(body?.password ?? "");
    const displayName = String(body?.displayName ?? body?.display_name ?? "").trim() || username;
    const role = String(body?.role ?? "viewer");
    if (!username || username.length < 3) throw ApiErrors.badRequest(4000, "用户名至少3位");
    if (password.length < 8) throw ApiErrors.badRequest(4000, "密码至少8位");
    if (!["super_admin", "admin", "viewer"].includes(role)) {
      throw ApiErrors.badRequest(4000, "角色不合法");
    }
    const { hash, salt } = this.auth.hashPassword(password);
    const created = await this.adminUsers.create(username, hash, salt, displayName, role, adminUser.id);
    await this.auditLog.log(adminUser.id, "admin.create", "admin_user", String(created.id),
      JSON.stringify({ username, role }), this.extractIp(req), req.headers["user-agent"] ?? "");
    return created;
  }

  @Post("users/:id")
  async updateAdmin(@Req() req: Request, @Param("id") id: string, @Body() body: Record<string, unknown>) {
    const adminUser = (req as any).adminUser;
    if (!adminUser || adminUser.role !== "super_admin") {
      throw ApiErrors.forbidden(4030, "仅超级管理员可编辑管理员");
    }
    const targetId = Number(id);
    if (!Number.isInteger(targetId) || targetId <= 0) throw ApiErrors.badRequest(4000, "ID 不合法");
    if (body?.role) {
      const role = String(body.role);
      if (!["super_admin", "admin", "viewer"].includes(role)) {
        throw ApiErrors.badRequest(4000, "角色不合法");
      }
      await this.adminUsers.setRole(targetId, role);
    }
    if (body?.disabled !== undefined) {
      await this.adminUsers.setDisabled(targetId, Boolean(body.disabled));
    }
    if (body?.display_name !== undefined) {
      // display_name 更新需要额外方法，暂不支持
    }
    await this.auditLog.log(adminUser.id, "admin.update", "admin_user", String(targetId),
      JSON.stringify(body), this.extractIp(req), req.headers["user-agent"] ?? "");
    return this.adminUsers.findById(targetId);
  }

  @Delete("users/:id")
  @HttpCode(HttpStatus.NO_CONTENT)
  async deleteAdmin(@Req() req: Request, @Param("id") id: string): Promise<void> {
    const adminUser = (req as any).adminUser;
    if (!adminUser || adminUser.role !== "super_admin") {
      throw ApiErrors.forbidden(4030, "仅超级管理员可删除管理员");
    }
    const targetId = Number(id);
    if (!Number.isInteger(targetId) || targetId <= 0) throw ApiErrors.badRequest(4000, "ID 不合法");
    if (targetId === adminUser.id) throw ApiErrors.badRequest(4000, "不能删除自己");
    await this.adminUsers.delete(targetId);
    await this.auditLog.log(adminUser.id, "admin.delete", "admin_user", String(targetId),
      null, this.extractIp(req), req.headers["user-agent"] ?? "");
  }

  // ==================== 审计日志 ====================

  @Get("audit-log")
  async listAuditLog(@Req() req: Request, @Query("adminId") adminId?: string,
                     @Query("action") action?: string, @Query("limit") limit?: string,
                     @Query("offset") offset?: string) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    return this.auditLog.list({
      adminId: adminId ? Number(adminId) : undefined,
      action: action || undefined,
      limit: Math.min(200, Math.max(1, Number(limit) || 50)),
      offset: Math.max(0, Number(offset) || 0),
    });
  }

  // ==================== IP 白名单 ====================

  @Get("ip-whitelist/:adminId")
  async getIpWhitelist(@Req() req: Request, @Param("adminId") adminId: string) {
    const adminUser = (req as any).adminUser;
    if (!adminUser) throw ApiErrors.unauthorized(4010, "未登录");
    const target = await this.adminUsers.findById(Number(adminId));
    if (!target) throw ApiErrors.notFound(4044, "管理员不存在");
    return { whitelist: target.ip_whitelist || "" };
  }

  @Post("ip-whitelist/:adminId")
  @HttpCode(HttpStatus.NO_CONTENT)
  async setIpWhitelist(@Req() req: Request, @Param("adminId") adminId: string,
                       @Body() body: Record<string, unknown>): Promise<void> {
    const adminUser = (req as any).adminUser;
    if (!adminUser || adminUser.role !== "super_admin") {
      throw ApiErrors.forbidden(4030, "仅超级管理员可管理 IP 白名单");
    }
    const whitelist = String(body?.whitelist ?? "");
    await this.adminUsers.setIpWhitelist(Number(adminId), whitelist || null);
  }

  private extractIp(req: Request): string {
    return String(req.headers["x-forwarded-for"] ?? req.socket.remoteAddress ?? "unknown").split(",")[0].trim();
  }

  private isIpAllowed(ip: string, whitelist: string): boolean {
    const allowed = whitelist.split(",").map(s => s.trim()).filter(Boolean);
    return allowed.length === 0 || allowed.includes(ip);
  }
}