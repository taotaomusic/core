import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/**
 * 开放 API Key 的公开形状。
 *
 * 列表与创建响应用它；**绝不包含 key_hash 或明文** —— 明文只在签发那一次返回，
 * 之后任何读路径都只能看到 key_prefix 回显。
 */
export type OpenApiKeyPublic = {
  id: number;
  name: string;
  keyPrefix: string;
  /** 0/1，与库里 smallint 一致；调用方自行判断是否为 1。 */
  enabled: number;
  createdAt: number;
  lastUsedAt: number | null;
  revokedAt: number | null;
  createdBy: number | null;
};

/**
 * 含校验材料的完整行，仅供 [OpenApiKeyService.verify] 内部使用。
 *
 * `keyHash` 只进内存做等值比对，不进任何 HTTP 响应或审计 detail。
 */
export type OpenApiKeyRow = OpenApiKeyPublic & { keyHash: string };

/**
 * 别名必须加双引号：PostgreSQL 会把不加引号的标识符折叠成小写，
 * `AS keyPrefix` 会得到 `keyprefix`，读出来全是 undefined 且不报错。
 */
const COLUMNS = `id,
  name,
  key_prefix AS "keyPrefix",
  enabled,
  created_at AS "createdAt",
  last_used_at AS "lastUsedAt",
  revoked_at AS "revokedAt",
  created_by AS "createdBy"`;

/**
 * 入站开放 API Key 的数据访问（`open_api_key` 表）。
 *
 * 与出站的 `api_key`（图片服务凭据）无关，是两套东西。
 * 库里只存 sha256(key)，明文仅在创建响应里返回一次。
 */
@Injectable()
export class OpenApiKeyRepository {
  constructor(private readonly database: DatabaseService) {}

  /** 签发落库并返回公开形状；key_hash 与明文的换算在服务层完成，这里只收已算好的值。 */
  async create(
    name: string,
    keyHash: string,
    keyPrefix: string,
    createdBy: number | null,
  ): Promise<OpenApiKeyPublic> {
    const created = await this.database.first<OpenApiKeyPublic>(
      `INSERT INTO open_api_key (name, key_hash, key_prefix, enabled, created_by, created_at)
      VALUES ($1, $2, $3, 1, $4, $5)
      RETURNING id, name, key_prefix AS "keyPrefix", enabled,
           created_at AS "createdAt", last_used_at AS "lastUsedAt",
           revoked_at AS "revokedAt", created_by AS "createdBy"`,
      [name, keyHash, keyPrefix, createdBy, Date.now()],
    );
    if (!created) throw new Error("签发开放 API Key 失败");
    return created;
  }

  /** 管理端列表：按创建时间倒序，含启停与吊销状态，不含 key_hash。 */
  list(): Promise<OpenApiKeyPublic[]> {
    return this.database.all<OpenApiKeyPublic>(
      `SELECT ${COLUMNS} FROM open_api_key ORDER BY created_at DESC, id DESC`,
    );
  }

  /** 按主键取公开形状；启停/吊销前的存在性判断用。 */
  findById(id: number): Promise<OpenApiKeyPublic | undefined> {
    return this.database.first<OpenApiKeyPublic>(
      `SELECT ${COLUMNS} FROM open_api_key WHERE id = $1`,
      [id],
    );
  }

  /** 按 sha256 查完整行（含 enabled / revoked_at），供校验流程判断是否可用。 */
  findByHash(keyHash: string): Promise<OpenApiKeyRow | undefined> {
    return this.database.first<OpenApiKeyRow>(
      `SELECT ${COLUMNS}, key_hash AS "keyHash" FROM open_api_key WHERE key_hash = $1`,
      [keyHash],
    );
  }

  /** 启停开关。返回受影响行数：0 表示 id 不存在。 */
  setEnabled(id: number, enabled: boolean): Promise<number> {
    return this.database.run(
      "UPDATE open_api_key SET enabled = $1 WHERE id = $2",
      [enabled ? 1 : 0, id],
    );
  }

  /**
   * 吊销：置 `revoked_at`。
   *
   * 带 `AND revoked_at IS NULL`：已吊销的 key 再点一次返回 0 行，
   * 控制器据此回 404，避免把「重复吊销」当成成功写进审计。
   */
  revoke(id: number): Promise<number> {
    return this.database.run(
      "UPDATE open_api_key SET revoked_at = $1 WHERE id = $2 AND revoked_at IS NULL",
      [Date.now(), id],
    );
  }

  /**
   * 刷新最近使用时间。
   *
   * 展示用途，失败不能影响主流程 —— 内部 catch，调用方 fire-and-forget 即可。
   */
  async touchLastUsed(id: number): Promise<void> {
    try {
      await this.database.run(
        "UPDATE open_api_key SET last_used_at = $1 WHERE id = $2",
        [Date.now(), id],
      );
    } catch {
      // 鉴权已经通过，写库抖动不应把这次请求变成 5xx
    }
  }
}
