import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/** 已知的系统设置键。集中声明，避免各处散落裸字符串。 */
export const AdminSettingKeys = {
  githubWebhookSecret: "github_webhook_secret",
} as const;

export type AdminSettingKey = (typeof AdminSettingKeys)[keyof typeof AdminSettingKeys];

/**
 * 管理端系统设置（键值）。
 *
 * 值存明文，服务端运行时按原文消费（如 webhook HMAC 验签需要原始密钥）。
 * 读给前端时只回掩码（见 [maskedOf]），绝不回传明文、也绝不写进审计。
 *
 * 带一个极小的内存缓存：webhook 每次回调都要读密钥，直接打库没必要；
 * 写入时主动失效，保证后台改完立即生效。
 */
@Injectable()
export class AdminSettingRepository {
  private readonly cache = new Map<string, string>();

  constructor(private readonly database: DatabaseService) {}

  /** 运行时取明文；未配置返回空串。只应在真正需要原文的地方调用（如验签）。 */
  async getValue(key: AdminSettingKey): Promise<string> {
    const cached = this.cache.get(key);
    if (cached !== undefined) return cached;
    const row = await this.database.first<{ value: string }>(
      "SELECT value FROM admin_setting WHERE key = $1",
      [key],
    );
    const value = row?.value ?? "";
    this.cache.set(key, value);
    return value;
  }

  async setValue(key: AdminSettingKey, value: string): Promise<void> {
    await this.database.run(
      `INSERT INTO admin_setting (key, value, updated_at) VALUES ($1, $2, $3)
       ON CONFLICT (key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at`,
      [key, value, Date.now()],
    );
    this.cache.set(key, value);
  }

  /** 该键是否已配置（非空）。 */
  async isSet(key: AdminSettingKey): Promise<boolean> {
    return (await this.getValue(key)).length > 0;
  }

  /** 掩码：空值回空串（界面据此区分「没配」与「配了但遮住」），否则遮全身只留尾 4 位。 */
  static maskedOf(value: string): string {
    if (!value) return "";
    return `••••••••${value.slice(-4)}`;
  }
}
