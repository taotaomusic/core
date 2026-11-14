import {
  Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query, Req,
} from "@nestjs/common";
import type { OnModuleDestroy, OnApplicationBootstrap } from "@nestjs/common";
import { Logger } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { AppConfigService } from "../config/app-config.service";
import { ShareAdminRepository } from "./share-admin.repository";

const DEFAULT_PAGE_SIZE = 30;
const MAX_PAGE_SIZE = 100;
/** 扫描兜底上限：一次最多删 1000 条，防止极端积压把一次事务拖得过长。 */
const PURGE_BATCH_LIMIT = 1000;

/**
 * 管理端分享短链：列表、统计、删除与自动清除。
 *
 * - 读接口用 [READ_ROLES]：短链列表是运营数据，观察者可以看；
 * - 删除与手动清除用 [WRITE_ROLES]，且都写审计（`share.delete` / `share.purge`）。
 *
 * 守卫逐个方法标注（`@AdminGuarded()`），不挂类上 —— 与全部业务管理控制器一致。
 */
@Public()
@RateLimit("admin")
@Controller("app/admin/share-links")
export class ShareAdminController implements OnApplicationBootstrap, OnModuleDestroy {
  private readonly logger = new Logger(ShareAdminController.name);
  /** 自动清除扫描句柄；`shareExpireDays <= 0` 或间隔为 0 时不启动周期任务。 */
  private sweepTimer: ReturnType<typeof setInterval> | null = null;

  constructor(
    private readonly repository: ShareAdminRepository,
    private readonly audit: AdminAuditService,
    private readonly config: AppConfigService,
  ) {}

  /**
   * 启动时先清一轮过期短链，再按配置的间隔周期扫描。
   *
   * 清除动作要晚于建表（onModuleInit 的迁移）才能执行，所以挂在
   * [onApplicationBootstrap] 而不是构造函数。失败只告警：自动清除是
   * 运维便利，不能让一次数据库抖动挡住服务启动。
   */
  async onApplicationBootstrap(): Promise<void> {
    await this.runScheduledSweep();
    const intervalMs = this.config.shareExpireSweepSeconds * 1000;
    if (this.config.shareExpireDays > 0 && intervalMs > 0) {
      this.sweepTimer = setInterval(() => void this.runScheduledSweep(), intervalMs);
      // 定时器不该阻止进程退出。
      this.sweepTimer.unref?.();
    }
  }

  onModuleDestroy(): void {
    if (this.sweepTimer) clearInterval(this.sweepTimer);
    this.sweepTimer = null;
  }

  @AdminGuarded()
  @Get()
  @RequireRole(...READ_ROLES)
  async list(@Query("keyword") keyword?: string, @Query("limit") limit?: string, @Query("offset") offset?: string) {
    const [items, summary] = await Promise.all([
      this.repository.list((keyword ?? "").trim().slice(0, 80), this.pageSize(limit), this.offset(offset)),
      this.repository.summary(),
    ]);
    return { ...items, summary, expireDays: this.config.shareExpireDays };
  }

  @AdminGuarded()
  @Get("summary")
  @RequireRole(...READ_ROLES)
  summary() {
    return this.repository.summary();
  }

  /**
   * 删除（物理 DELETE）一条短链。
   *
   * 删除后公开分享页按既有语义返回 404/4045「不存在或已失效」，
   * 客户端与分享页都不需要任何改动。访客去重行随外键级联清理。
   */
  @AdminGuarded()
  @Delete(":token")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@Req() request: AdminAuthenticatedRequest, @Param("token") token: string): Promise<void> {
    if (!this.validToken(token)) throw ApiErrors.badRequest(4001, "短码不合法");
    if (!(await this.repository.remove(token))) throw ApiErrors.notFound(4045, "分享短链不存在");
    await this.audit.record(request, "share.delete", "share", token);
  }

  /** 隐藏（软删除）：分享页立即失效，统计数据保留在库里供核查。 */
  @AdminGuarded()
  @Post(":token/disabled")
  @RequireRole(...WRITE_ROLES)
  async setDisabled(
    @Req() request: AdminAuthenticatedRequest,
    @Param("token") token: string,
  ) {
    if (!this.validToken(token)) throw ApiErrors.badRequest(4001, "短码不合法");
    // 简单开关：有 body 用 body（明确 true/false），没有 body 默认隐藏。
    const changed = await this.repository.setEnabled(token, request.body?.enabled === true);
    if (!changed) throw ApiErrors.notFound(4045, "分享短链不存在");
    await this.audit.record(request, "share.set_disabled", "share", token, {
      enabled: request.body?.enabled === true,
    });
    return { enabled: request.body?.enabled === true };
  }

  /**
   * 手动触发一次过期清除。
   *
   * 与启动/定时扫描同一段实现；返回被清除的短码列表（前端用于提示条数）。
   * 自动清除阈值关闭（SHARE_EXPIRE_DAYS=0）时直接返回空列表，不做删除 ——
   * 手动触发也不能绕过「永不清除」的显式配置。
   */
  @AdminGuarded()
  @Post("purge")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.OK)
  async purge(@Req() request: AdminAuthenticatedRequest) {
    const removed = await this.purgeOnce();
    await this.audit.record(request, "share.purge", "share", null, {
      expireDays: this.config.shareExpireDays,
      removed: removed.length,
    });
    return { removed, expireDays: this.config.shareExpireDays };
  }

  /** 定时扫描：失败只告警，绝不打断服务。 */
  private async runScheduledSweep(): Promise<void> {
    try {
      const removed = await this.purgeOnce();
      if (removed.length > 0) {
        this.logger.log(`已自动清除 ${removed.length} 条超期未访问的分享短链`);
      }
    } catch (error) {
      this.logger.warn(`分享短链自动清除失败（不影响服务）：${error instanceof Error ? error.message : String(error)}`);
    }
  }

  private async purgeOnce(): Promise<string[]> {
    if (this.config.shareExpireDays <= 0) return [];
    const removed = await this.repository.purgeExpired(this.config.shareExpireDays, Date.now());
    // 单次兜底截断：正常部署不会碰到，防御性保留。
    return removed.slice(0, PURGE_BATCH_LIMIT).map((row) => row.token);
  }

  private validToken(token: string): boolean {
    return /^[A-Za-z0-9_-]{8,24}$/.test(token);
  }

  private pageSize(value: string | undefined): number {
    if (value === undefined || value === "") return DEFAULT_PAGE_SIZE;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 1 || parsed > MAX_PAGE_SIZE) {
      throw ApiErrors.badRequest(4000, "limit 必须在 1 到 100 之间");
    }
    return parsed;
  }

  private offset(value: string | undefined): number {
    if (value === undefined || value === "") return 0;
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed < 0) throw ApiErrors.badRequest(4000, "offset 不合法");
    return parsed;
  }
}
