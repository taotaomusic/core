import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query, Req,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { PRIVILEGED_READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { AuthService } from "../auth/auth.service";
import { UsersRepository } from "../auth/users.repository";
import { UserAdminRepository } from "./user-admin.repository";

const DEFAULT_PAGE_SIZE = 30;
const MAX_PAGE_SIZE = 100;
const DEFAULT_HISTORY_LIMIT = 50;
const MAX_HISTORY_LIMIT = 200;

/** 与 auth.controller 注册接口的口径逐字一致；一边收紧另一边不同步，就会出现「后台建得出、客户端登不进」的账号。 */
const USERNAME_PATTERN = /^[\w一-龥]{3,32}$/;
const MIN_PASSWORD_LENGTH = 6;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MAX_NICKNAME_LENGTH = 24;

/**
 * 管理端用户与听歌统计。
 *
 * **读和写都要求 `admin` 及以上，观察者被拒。** 这里返回的是用户隐私数据：
 * 列表带 `email`，详情带逐首歌的播放次数与时间戳。观察者进后台是为了看发布状态
 * 这类运营数据，不该看到「某个用户在几点几分听了哪首歌」，所以它用的是
 * `PRIVILEGED_READ_ROLES` 而不是业务接口的 `READ_ROLES`。
 *
 * 前端的「用户与统计」页签同样对观察者隐藏，两边保持一致。
 *
 * 守卫逐个方法标注（`@AdminGuarded()`），不挂类上。详见 [AdminGuarded]。
 */
@Public()
@RateLimit("admin")
@Controller("app/admin/users")
export class UserAdminController {
  constructor(
    private readonly users: UserAdminRepository,
    private readonly audit: AdminAuditService,
    private readonly auth: AuthService,
    private readonly accounts: UsersRepository,
  ) {}

  /**
   * 后台直接创建账号，绕过邮箱验证码。
   *
   * 用户名/密码规则与客户端注册完全一致，建出的账号当场就能登录 App。邮箱选填，
   * 且**不做注册渠道的域名白名单**：那份白名单管的是「对外承诺的注册渠道」，
   * 管理员建号是内部操作，写什么邮箱由管理员负责。密码绝不进请求日志与审计详情。
   */
  @AdminGuarded()
  @Post()
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.CREATED)
  async create(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const username = this.auth.textOf(body?.username, true);
    const password = this.auth.textOf(body?.password);
    const email = this.auth.textOf(body?.email, true).toLowerCase();
    const nickname = body?.nickname === undefined || body?.nickname === null || body?.nickname === ""
      ? null
      : this.nicknameOf(body.nickname);
    if (!USERNAME_PATTERN.test(username) || password.length < MIN_PASSWORD_LENGTH) {
      throw ApiErrors.badRequest(4000, "用户名为3至32位，密码至少6位");
    }
    if (email && !EMAIL_PATTERN.test(email)) throw ApiErrors.badRequest(4000, "邮箱格式不正确");
    // 查重只为给出准确的 409；真正兜底是 users.create 里的唯一约束翻译（连接池并发窗口）。
    if (email && (await this.accounts.findByEmail(email))) throw ApiErrors.conflict(4092, "邮箱已注册");
    if (await this.accounts.findByUsername(username)) throw ApiErrors.conflict(4090, "用户名已存在");

    const credentials = this.auth.hashPassword(password);
    const user = await this.accounts.create(username, email || null, credentials.hash, credentials.salt, nickname);
    await this.audit.record(request, "user.create", "user", String(user.id), { username, email: email || null });
    return user;
  }

  /** 与 auth.controller 的昵称校验一致：1 至 24 个非控制字符。 */
  private nicknameOf(value: unknown): string {
    const nickname = this.auth.textOf(value, true);
    if (!nickname || nickname.length > MAX_NICKNAME_LENGTH || /[\u0000-\u001F\u007F]/.test(nickname)) {
      throw ApiErrors.badRequest(4000, `昵称须为 1 至 ${MAX_NICKNAME_LENGTH} 个非控制字符`);
    }
    return nickname;
  }

  @AdminGuarded()
  @Get()
  @RequireRole(...PRIVILEGED_READ_ROLES)
  list(@Query("query") query?: string, @Query("limit") limit?: string, @Query("offset") offset?: string) {
    return this.users.list((query ?? "").trim().slice(0, 80), this.pageSize(limit), this.offset(offset));
  }

  @AdminGuarded()
  @Get(":id/playback")
  @RequireRole(...PRIVILEGED_READ_ROLES)
  async playback(@Param("id") id: string, @Query("limit") limit?: string) {
    const detail = await this.users.detail(this.userId(id), this.historyLimit(limit));
    if (!detail) throw ApiErrors.notFound(4044, "用户不存在");
    return detail;
  }

  @AdminGuarded()
  @Post(":id/disabled")
  @RequireRole(...WRITE_ROLES)
  async setDisabled(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") id: string,
    @Body() body: Record<string, unknown>,
  ) {
    if (typeof body?.disabled !== "boolean") throw ApiErrors.badRequest(4000, "disabled 必须是布尔值");
    const targetId = this.userId(id);
    const changed = await this.users.setDisabled(targetId, body.disabled);
    if (!changed) throw ApiErrors.notFound(4044, "用户不存在");
    await this.audit.record(request, "user.set_disabled", "user", String(targetId), {
      disabled: body.disabled,
    });
    return changed;
  }

  @AdminGuarded()
  @Delete(":id")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.NO_CONTENT)
  async delete(@Req() request: AdminAuthenticatedRequest, @Param("id") id: string): Promise<void> {
    const targetId = this.userId(id);
    if (!(await this.users.delete(targetId))) throw ApiErrors.notFound(4044, "用户不存在");
    await this.audit.record(request, "user.delete", "user", String(targetId));
  }

  private userId(value: string): number {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw ApiErrors.badRequest(4000, "用户 ID 不合法");
    return id;
  }

  private pageSize(value: string | undefined): number {
    return this.boundedNumber(value, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE, "limit");
  }

  private historyLimit(value: string | undefined): number {
    return this.boundedNumber(value, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT, "limit");
  }

  private offset(value: string | undefined): number {
    if (value === undefined || value === "") return 0;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 0) throw ApiErrors.badRequest(4000, "offset 不合法");
    return parsed;
  }

  private boundedNumber(value: string | undefined, fallback: number, maximum: number, name: string): number {
    if (value === undefined || value === "") return fallback;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > maximum) {
      throw ApiErrors.badRequest(4000, `${name} 必须在 1 到 ${maximum} 之间`);
    }
    return parsed;
  }
}
