import { Injectable } from "@nestjs/common";
import { createHash, randomBytes } from "node:crypto";
import type { OpenApiKeyIdentity } from "../common/request.types";
import { OpenApiKeyRepository, type OpenApiKeyPublic } from "./open-api-key.repository";

/** 签发结果：公开字段 + 一次性返回的明文。明文只在这一次出现，之后无法找回。 */
export type IssuedOpenApiKey = OpenApiKeyPublic & { apiKey: string };

/** 明文前缀，与限流守卫读 header 的口径一致（用户访问令牌不以 tt_ 开头）。 */
const KEY_PREFIX = "tt_";
/** 前缀 + 32 字节随机的 base64url 前 12 位，足够在列表页区分同名 key。 */
const DISPLAY_PREFIX_LENGTH = 12;

/**
 * 开放 API Key 的签发与校验。
 *
 * 只存 sha256(key)：库被读走也还原不出可用明文；列表页只回显 key_prefix。
 */
@Injectable()
export class OpenApiKeyService {
  constructor(private readonly repository: OpenApiKeyRepository) {}

  /**
   * 签发一把新 key。
   *
   * 明文只在这里生成并随返回值出去一次，服务端不落明文 —— 调用方丢了就只能重新签发。
   */
  async issue(name: string, createdBy: number | null): Promise<IssuedOpenApiKey> {
    const apiKey = `${KEY_PREFIX}${randomBytes(32).toString("base64url")}`;
    const keyHash = this.hashOf(apiKey);
    const keyPrefix = apiKey.slice(0, DISPLAY_PREFIX_LENGTH);
    const created = await this.repository.create(name, keyHash, keyPrefix, createdBy);
    return { ...created, apiKey };
  }

  /**
   * 校验请求头里的 key 原文。
   *
   * 空、不以 `tt_` 开头、查无此哈希、已禁用或已吊销 → undefined（调用方回 401/4014）。
   * 校验通过后 fire-and-forget 刷 `last_used_at`，失败也不影响本次请求。
   *
   * 哈希用**普通等值比较**而不是 timingSafeEqual：key 是 32 字节随机值，
   * 猜中哈希本身已不可行，逐字节时序差异在这里没有实际威胁面。
   */
  async verify(rawKey: string): Promise<OpenApiKeyIdentity | undefined> {
    if (!rawKey || !rawKey.startsWith(KEY_PREFIX)) return undefined;
    const row = await this.repository.findByHash(this.hashOf(rawKey));
    if (!row) return undefined;
    if (row.enabled !== 1) return undefined;
    if (row.revokedAt !== null && row.revokedAt !== undefined) return undefined;
    // 内部已 catch，这里不需要再挂 .catch
    void this.repository.touchLastUsed(row.id);
    return { id: row.id, name: row.name };
  }

  private hashOf(rawKey: string): string {
    return createHash("sha256").update(rawKey).digest("hex");
  }
}
