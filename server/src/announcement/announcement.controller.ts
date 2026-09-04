import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, UseGuards } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { AdminTokenGuard } from "../common/guards/admin-token.guard";
import { AnnouncementRepository } from "./announcement.repository";

const MAX_TITLE_LENGTH = 80;
const MAX_CONTENT_LENGTH = 5_000;

/** 客户端公告读取与后台发布管理。 */
@Controller()
export class AnnouncementController {
  constructor(private readonly announcements: AnnouncementRepository) {}

  /** 公告公开可读，最多返回 20 条，避免首页拉取无限增长的历史数据。 */
  @Public()
  @Get("announcements")
  listPublic() {
    return this.announcements.listVisible();
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Get("app/admin/announcements")
  listAdmin() {
    return this.announcements.listAll();
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Post("app/admin/announcements")
  @HttpCode(HttpStatus.CREATED)
  async publish(@Body() body: Record<string, unknown>) {
    return this.announcements.create(this.requiredText(body?.title, "标题", MAX_TITLE_LENGTH), this.requiredText(body?.content, "正文", MAX_CONTENT_LENGTH));
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Post("app/admin/announcements/:id")
  async update(@Param("id") idParam: string, @Body() body: Record<string, unknown>) {
    const id = this.idOf(idParam);
    const title = body?.title === undefined ? undefined : this.requiredText(body.title, "标题", MAX_TITLE_LENGTH);
    const content = body?.content === undefined ? undefined : this.requiredText(body.content, "正文", MAX_CONTENT_LENGTH);
    if (title === undefined && content === undefined) throw ApiErrors.badRequest(4000, "请至少提供标题或正文");
    const announcement = await this.announcements.update(id, title, content);
    if (!announcement) throw ApiErrors.notFound(4043, "公告不存在");
    return announcement;
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Post("app/admin/announcements/:id/enabled")
  async setEnabled(@Param("id") idParam: string, @Body() body: Record<string, unknown>) {
    if (typeof body?.enabled !== "boolean") throw ApiErrors.badRequest(4000, "enabled 必须是布尔值");
    const announcement = await this.announcements.setEnabled(this.idOf(idParam), body.enabled);
    if (!announcement) throw ApiErrors.notFound(4043, "公告不存在");
    return announcement;
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Post("app/admin/announcements/:id/pinned")
  async setPinned(@Param("id") idParam: string, @Body() body: Record<string, unknown>) {
    if (typeof body?.pinned !== "boolean") throw ApiErrors.badRequest(4000, "pinned 必须是布尔值");
    const announcement = await this.announcements.setPinned(this.idOf(idParam), body.pinned);
    if (!announcement) throw ApiErrors.notFound(4043, "公告不存在");
    return announcement;
  }

  @Public()
  @UseGuards(AdminTokenGuard)
  @RateLimit("admin")
  @Delete("app/admin/announcements/:id")
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@Param("id") idParam: string): Promise<void> {
    if (!(await this.announcements.remove(this.idOf(idParam)))) throw ApiErrors.notFound(4043, "公告不存在");
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
