import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query, Req, UseGuards,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminAuthGuard } from "../admin-auth/admin-auth.guard";
import { RolesGuard } from "../admin-auth/roles.guard";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { UserAdminRepository } from "./user-admin.repository";

const DEFAULT_PAGE_SIZE = 30;
const MAX_PAGE_SIZE = 100;
const DEFAULT_HISTORY_LIMIT = 50;
const MAX_HISTORY_LIMIT = 200;

/**
 * 管理端用户与听歌统计。
 *
 * 查询接口三种角色都能看（观察者进后台就是为了看数据）；禁用和删除用户是写操作，
 * 只读账号被拒，并且都要留审计 —— 这两条以前既没有角色校验也不写日志，
 * 「谁把哪个用户删了」在 admin_audit_log 里查不到。
 */
@Public()
@UseGuards(AdminAuthGuard, RolesGuard)
@RateLimit("admin")
@Controller("app/admin/users")
export class UserAdminController {
  constructor(
    private readonly users: UserAdminRepository,
    private readonly audit: AdminAuditService,
  ) {}

  @Get()
  @RequireRole(...READ_ROLES)
  list(@Query("query") query?: string, @Query("limit") limit?: string, @Query("offset") offset?: string) {
    return this.users.list((query ?? "").trim().slice(0, 80), this.pageSize(limit), this.offset(offset));
  }

  @Get(":id/playback")
  @RequireRole(...READ_ROLES)
  async playback(@Param("id") id: string, @Query("limit") limit?: string) {
    const detail = await this.users.detail(this.userId(id), this.historyLimit(limit));
    if (!detail) throw ApiErrors.notFound(4044, "用户不存在");
    return detail;
  }

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
