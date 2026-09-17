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
import { UserAdminRepository } from "./user-admin.repository";

const DEFAULT_PAGE_SIZE = 30;
const MAX_PAGE_SIZE = 100;
const DEFAULT_HISTORY_LIMIT = 50;
const MAX_HISTORY_LIMIT = 200;

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
  ) {}

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
