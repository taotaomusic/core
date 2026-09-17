import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Req,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { AnnouncementRepository } from "./announcement.repository";

const MAX_TITLE_LENGTH = 80;
const MAX_CONTENT_LENGTH = 5_000;

/**
 * 客户端公告读取与后台发布管理。
 *
 * **守卫只能逐个方法挂**：类级 `@UseGuards` 会连公开的 `GET /announcements`
 * 一起保护，客户端首页会直接 401。这也是 `admin/auth` 里踩过的同一个坑。
 */
@Controller()
export class AnnouncementController {
  constructor(
    private readonly announcements: AnnouncementRepository,
    private readonly audit: AdminAuditService,
  ) {}

  /** 公告公开可读，最多返回 20 条，避免首页拉取无限增长的历史数据。 */
  @Public()
  @Get("announcements")
  listPublic() {
    return this.announcements.listVisible();
  }

  @Public()
  @AdminGuarded()
  @RateLimit("admin")
  @Get("app/admin/announcements")
  @RequireRole(...READ_ROLES)
  listAdmin() {
    return this.announcements.listAll();
  }

  @Public()
  @AdminGuarded()
  @RateLimit("admin")
  @Post("app/admin/announcements")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.CREATED)
  async publish(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const created = await this.announcements.create(
      this.requiredText(body?.title, "标题", MAX_TITLE_LENGTH),
      this.requiredText(body?.content, "正文", MAX_CONTENT_LENGTH),
    );
    await this.audit.record(request, "announcement.create", "announcement", String(created.id), {
      title: created.title,
    });
    return created;
  }

  @Public()
  @AdminGuarded()
  @RateLimit("admin")
  @Post("app/admin/announcements/:id")
  @RequireRole(...WRITE_ROLES)
  async update(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") idParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    const id = this.idOf(idParam);
    const title = body?.title === undefined ? undefined : this.requiredText(body.title, "标题", MAX_TITLE_LENGTH);
    const content = body?.content === undefined ? undefined : this.requiredText(body.content, "正文", MAX_CONTENT_LENGTH);
    if (title === undefined && content === undefined) throw ApiErrors.badRequest(4000, "请至少提供标题或正文");
    const announcement = await this.announcements.update(id, title, content);
    if (!announcement) throw ApiErrors.notFound(4043, "公告不存在");
    await this.audit.record(request, "announcement.update", "announcement", String(id), {
      // 只记改了哪几个字段，正文可能很长，全文塞进审计表没有意义。
      fields: [title !== undefined ? "title" : null, content !== undefined ? "content" : null]
        .filter((field): field is string => field !== null),
      title: announcement.title,
    });
    return announcement;
  }

  @Public()
  @AdminGuarded()
  @RateLimit("admin")
  @Post("app/admin/announcements/:id/enabled")
  @RequireRole(...WRITE_ROLES)
  async setEnabled(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") idParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    if (typeof body?.enabled !== "boolean") throw ApiErrors.badRequest(4000, "enabled 必须是布尔值");
    const id = this.idOf(idParam);
    const announcement = await this.announcements.setEnabled(id, body.enabled);
    if (!announcement) throw ApiErrors.notFound(4043, "公告不存在");
    await this.audit.record(request, "announcement.set_enabled", "announcement", String(id), {
      enabled: body.enabled,
    });
    return announcement;
  }

  @Public()
  @AdminGuarded()
  @RateLimit("admin")
  @Post("app/admin/announcements/:id/pinned")
  @RequireRole(...WRITE_ROLES)
  async setPinned(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") idParam: string,
    @Body() body: Record<string, unknown>,
  ) {
    if (typeof body?.pinned !== "boolean") throw ApiErrors.badRequest(4000, "pinned 必须是布尔值");
    const id = this.idOf(idParam);
    const announcement = await this.announcements.setPinned(id, body.pinned);
    if (!announcement) throw ApiErrors.notFound(4043, "公告不存在");
    await this.audit.record(request, "announcement.set_pinned", "announcement", String(id), {
      pinned: body.pinned,
    });
    return announcement;
  }

  @Public()
  @AdminGuarded()
  @RateLimit("admin")
  @Delete("app/admin/announcements/:id")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@Req() request: AdminAuthenticatedRequest, @Param("id") idParam: string): Promise<void> {
    const id = this.idOf(idParam);
    if (!(await this.announcements.remove(id))) throw ApiErrors.notFound(4043, "公告不存在");
    await this.audit.record(request, "announcement.delete", "announcement", String(id));
  }

  private requiredText(value: unknown, label: string, maxLength: number): string {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text || text.length > maxLength) throw ApiErrors.badRequest(4000, `${label}须为 1 至 ${maxLength} 个字符`);
    return text;
  }

  private idOf(value: string): number {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw ApiErrors.badRequest(4000, "公告 ID 不合法");
    return id;
  }
}
