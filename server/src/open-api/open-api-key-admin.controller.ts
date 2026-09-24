import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Patch, Post, Req,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { auditActorId, type AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { OpenApiKeyRepository } from "./open-api-key.repository";
import { OpenApiKeyService } from "./open-api-key.service";

const MAX_NAME_LENGTH = 64;

/**
 * 开放 API Key 的后台管理（`/api/v1/app/admin/open-api-keys`）。
 *
 * 结构照抄 ImageKeyAdminController：
 * - 类级 [Public] + [RateLimit]("admin")：绕开全局访问令牌守卫，改由管理员认证把关；
 * - 方法级 `@AdminGuarded()` + `@RequireRole`：读写分权，观察者只读。
 *
 * 签发响应里的 `apiKey` 明文**只返回这一次**；审计 detail 只记 name 与 keyPrefix，
 * 绝不记明文 —— 审计日志是权限更低的账号也能看到的东西。
 *
 * 守卫逐个方法标注（`@AdminGuarded()`），不挂类上，理由见 [AdminGuarded]。
 */
@Public()
@RateLimit("admin")
@Controller("app/admin/open-api-keys")
export class OpenApiKeyAdminController {
  constructor(
    private readonly keys: OpenApiKeyRepository,
    private readonly service: OpenApiKeyService,
    private readonly audit: AdminAuditService,
  ) {}

  /** 列表只有 keyPrefix 回显，没有明文也没有 key_hash。 */
  @AdminGuarded()
  @Get()
  @RequireRole(...READ_ROLES)
  listKeys() {
    return this.keys.list();
  }

  /**
   * 签发新 key。201 返回，`data.apiKey` 是明文，仅此一次可见。
   *
   * `createdBy` 经 [auditActorId] 收敛：会话异常时写 null（无归属），不撞外键。
   */
  @AdminGuarded()
  @Post()
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.CREATED)
  async issueKey(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const name = typeof body?.name === "string" ? body.name.trim() : "";
    if (!name || name.length > MAX_NAME_LENGTH) {
      throw ApiErrors.badRequest(4000, `名称须为 1 至 ${MAX_NAME_LENGTH} 个字符`);
    }
    const created = await this.service.issue(name, auditActorId(request.adminUser));
    // 审计只记标识与前缀，明文连审计表也不落。
    await this.audit.record(request, "open_api_key.create", "open_api_key", String(created.id), {
      name,
      keyPrefix: created.keyPrefix,
    });
    return created;
  }

  /** 启停开关。0 行说明 id 不存在 → 404/4042。 */
  @AdminGuarded()
  @Patch(":id")
  @RequireRole(...WRITE_ROLES)
  async setEnabled(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") rawId: string,
    @Body() body: Record<string, unknown>,
  ) {
    if (typeof body?.enabled !== "boolean") throw ApiErrors.badRequest(4000, "enabled 必须是布尔值");
    const id = this.idOf(rawId);
    if ((await this.keys.setEnabled(id, body.enabled)) !== 1) {
      throw ApiErrors.notFound(4042, "开放 API Key 不存在");
    }
    await this.audit.record(request, "open_api_key.set_enabled", "open_api_key", String(id), {
      enabled: body.enabled,
    });
    return this.keys.findById(id);
  }

  /**
   * 吊销（不可逆）。已吊销的 key 再点一次也回 404 —— 仓库层带
   * `revoked_at IS NULL` 条件，重复操作不写第二条审计。
   */
  @AdminGuarded()
  @Delete(":id")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.NO_CONTENT)
  async revokeKey(@Req() request: AdminAuthenticatedRequest, @Param("id") rawId: string): Promise<void> {
    const id = this.idOf(rawId);
    if ((await this.keys.revoke(id)) !== 1) {
      throw ApiErrors.notFound(4042, "开放 API Key 不存在或已吊销");
    }
    await this.audit.record(request, "open_api_key.revoke", "open_api_key", String(id));
  }

  /** 非正整数 ID → 400/4000，与其它管理控制器的口径一致。 */
  private idOf(value: string): number {
    const id = Number(value);
    if (!Number.isInteger(id) || id <= 0) throw ApiErrors.badRequest(4000, "API Key ID 不合法");
    return id;
  }
}
