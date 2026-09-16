import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query, UseGuards } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminAuthGuard } from "../admin-auth/admin-auth.guard";
import { UserAdminRepository } from "./user-admin.repository";

const DEFAULT_PAGE_SIZE = 30;
const MAX_PAGE_SIZE = 100;
const DEFAULT_HISTORY_LIMIT = 50;
const MAX_HISTORY_LIMIT = 200;

/** 管理端用户与听歌统计，只读且必须持有管理员令牌。 */
@Public()
@UseGuards(AdminAuthGuard)
@RateLimit("admin")
@Controller("app/admin/users")
export class UserAdminController {
  constructor(private readonly users: UserAdminRepository) {}

  @Get()
  list(@Query("query") query?: string, @Query("limit") limit?: string, @Query("offset") offset?: string) {
    return this.users.list((query ?? "").trim().slice(0, 80), this.pageSize(limit), this.offset(offset));
  }

  @Get(":id/playback")
  async playback(@Param("id") id: string, @Query("limit") limit?: string) {
    const detail = await this.users.detail(this.userId(id), this.historyLimit(limit));
    if (!detail) throw ApiErrors.notFound(4044, "用户不存在");
    return detail;
  }

  @Post(":id/disabled")
  async setDisabled(@Param("id") id: string, @Body() body: Record<string, unknown>) {
    if (typeof body?.disabled !== "boolean") throw ApiErrors.badRequest(4000, "disabled 必须是布尔值");
    const changed = await this.users.setDisabled(this.userId(id), body.disabled);
    if (!changed) throw ApiErrors.notFound(4044, "用户不存在");
    return changed;
  }

  @Delete(":id")
  @HttpCode(HttpStatus.NO_CONTENT)
  async delete(@Param("id") id: string): Promise<void> {
    if (!(await this.users.delete(this.userId(id)))) throw ApiErrors.notFound(4044, "用户不存在");
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
