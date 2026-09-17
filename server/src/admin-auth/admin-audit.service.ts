import { Injectable } from "@nestjs/common";
import {
  auditActorId, forwardedClientAddress, type AdminAuthenticatedRequest,
} from "../common/request.types";
import { AuditLogRepository } from "./audit-log.repository";

/**
 * 管理端写操作的审计留痕。
 *
 * 包一层是为了让控制器里只写一行 `await this.audit.record(...)` —— 取操作人、取来源
 * 地址、取 UA 这三件事各有各的坑（`X-Forwarded-For` 只有开启 `TRUST_PROXY` 时才可信），
 * 集中在这里才不会每个接口各错一遍。
 *
 * **只在操作成功之后调用**：控制器抛异常时这一行不会执行，所以失败的操作不留痕 ——
 * 与 [AuditLogRepository] 的既有口径一致，审计记的是「发生了什么」，不是「尝试了什么」。
 */
@Injectable()
export class AdminAuditService {
  constructor(private readonly repository: AuditLogRepository) {}

  /**
   * 写一条审计。
   *
   * @param action 形如 `release.publish` 的动作名，按「域.动作」命名，便于按前缀筛。
   * @param targetType 目标类型，例如 `release` / `announcement` / `user` / `image_key`。
   * @param targetId 目标标识，转成字符串存放（表里是 text）。
   * @param detail 补充信息，会被 JSON 序列化；传 `undefined` 表示没有。
   */
  async record(
    request: AdminAuthenticatedRequest,
    action: string,
    targetType: string | null,
    targetId: string | null,
    detail?: unknown,
  ): Promise<void> {
    await this.repository.log(
      auditActorId(request.adminUser),
      action,
      targetType,
      targetId,
      detail === undefined || detail === null ? null : JSON.stringify(detail),
      forwardedClientAddress(request),
      String(request.headers["user-agent"] ?? ""),
    );
  }
}
