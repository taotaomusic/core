import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type AuditLogEntry = {
  id: number; admin_id: number | null; action: string; target_type: string | null;
  target_id: string | null; detail: string | null; ip_address: string | null;
  user_agent: string | null; created_at: number;
};

@Injectable()
export class AuditLogRepository {
  constructor(private readonly database: DatabaseService) {}

  /**
   * 写一条审计记录。
   *
   * `adminId` 允许为 `null`：管理员被删除后，其历史记录会因外键
   * `ON DELETE SET NULL` 变成「无归属」，而不是被连带删除。写入前统一经
   * `auditActorId()` 收敛，确保不会把非法 id 落库撞外键。
   */
  async log(adminId: number | null, action: string, targetType: string | null, targetId: string | null,
            detail: string | null, ip: string, userAgent: string): Promise<void> {
    await this.database.run(
      `INSERT INTO admin_audit_log (admin_id, action, target_type, target_id, detail, ip_address, user_agent, created_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8)`,
      [adminId, action, targetType, targetId, detail, ip, userAgent, Date.now()],
    );
  }

  async list(options: { adminId?: number; action?: string; limit: number; offset: number }
  ): Promise<{ items: AuditLogEntry[]; total: number }> {
    const conditions: string[] = [];
    const params: unknown[] = [];
    let idx = 1;
    if (options.adminId) { conditions.push(`admin_id = $${idx++}`); params.push(options.adminId); }
    if (options.action) { conditions.push(`action = $${idx++}`); params.push(options.action); }
    const where = conditions.length ? `WHERE ${conditions.join(" AND ")}` : "";
    const total = (await this.database.first<{ count: number }>(
      `SELECT COUNT(*)::integer AS count FROM admin_audit_log ${where}`, params,
    ))?.count ?? 0;
    const items = await this.database.all<AuditLogEntry>(
      `SELECT * FROM admin_audit_log ${where} ORDER BY created_at DESC LIMIT $${idx++} OFFSET $${idx++}`,
      [...params, options.limit, options.offset],
    );
    return { items, total };
  }
}