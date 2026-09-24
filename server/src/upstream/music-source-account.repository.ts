import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

/**
 * 管理端展示用的音源账号。**不含明文凭据** —— token 只回传掩码，
 * 明文留在服务端数据库里，从不经过 HTTP 响应体，也不进审计表。
 */
export type MusicSourceAccountSummary = {
  id: number;
  source: string;
  label: string;
  maskedPhone: string;
  maskedToken: string;
  /** 上游账号 ID。单独拿出来不足以通过鉴权（必须与 token 配对），可以回传。 */
  uid: string;
  enabled: number;
  remark: string;
  /** `unknown` 未测过 / `ok` 可用 / `invalid` 凭据已失效。 */
  lastStatus: string;
  lastError: string;
  /** 上次测试探测到的真实音质摘要，例如「mp3 128kbps」。空串表示还没测过。 */
  lastNote: string;
  lastCheckedAt: number | null;
  createdAt: number;
  updatedAt: number;
};

/** 播放链路取用的凭据。这是全项目唯一会读出明文 token 的地方。 */
export type MusicSourceCredential = { token: string; uid: string };

/**
 * 手机号掩码。
 *
 * 与 token 的全遮样式刻意不同：后台往往同时管着几个号，全遮之后只能靠尾号 4 位
 * 区分，而前 3 位是运营商号段，遮掉不增加任何安全性，只让界面更难用。
 * 空值返回空串，界面要能区分「没填」和「填了但被掩码」。
 *
 * 导出给管理控制器用：那里在写审计时需要掩码手机号，但拿不到 summary 对象。
 */
export function maskPhoneNumber(phone: string): string {
  if (!phone) return "";
  if (phone.length < 7) return "••••";
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`;
}

/** 新建或更新时允许提交的字段。未提供的字段保持原值。 */
export type MusicSourceAccountPatch = {
  label?: string;
  phone?: string;
  token?: string;
  uid?: string;
  remark?: string;
  enabled?: boolean;
};

/** 数据库行。列名是 snake_case，映射成 camelCase 由 `summaryOf` 统一负责。 */
type AccountRow = {
  id: number;
  source: string;
  label: string;
  phone: string;
  token: string;
  uid: string;
  enabled: number;
  remark: string;
  lastStatus: string;
  lastError: string;
  lastNote: string;
  lastCheckedAt: number | null;
  createdAt: number;
  updatedAt: number;
};

/**
 * 展示用的列。
 *
 * `token` 必须取出来才能算掩码，但**只允许在 [summaryOf] 里被就地掩掉** ——
 * 这个类型不叫 `Summary` 就是为了提醒：`AccountRow` 带明文，
 * 任何把它直接返回给控制器的写法都是泄漏。
 */
const SUMMARY_COLUMNS = `
  id, source, label, phone, token, uid, enabled, remark,
  last_status     AS "lastStatus",
  last_error      AS "lastError",
  last_note       AS "lastNote",
  last_checked_at AS "lastCheckedAt",
  created_at      AS "createdAt",
  updated_at      AS "updatedAt"`;

/**
 * 音源账号数据访问。
 *
 * 一个音源可以配多个账号，播放链路取**启用状态里最新的那一条**。
 * 目前没有做轮换与配额，一个账号就够；留成多行是为了后面真要换号时不用改表。
 */
@Injectable()
export class MusicSourceAccountRepository {
  constructor(private readonly database: DatabaseService) {}

  async list(source?: string): Promise<MusicSourceAccountSummary[]> {
    const rows = await this.database.all<AccountRow>(
      `SELECT ${SUMMARY_COLUMNS} FROM music_source_account
       ${source ? "WHERE source = $1" : ""}
       ORDER BY source, enabled DESC, id`,
      source ? [source] : [],
    );
    return rows.map((row) => this.summaryOf(row));
  }

  async findById(id: number): Promise<MusicSourceAccountSummary | undefined> {
    const row = await this.database.first<AccountRow>(
      `SELECT ${SUMMARY_COLUMNS} FROM music_source_account WHERE id = $1`,
      [id],
    );
    return row ? this.summaryOf(row) : undefined;
  }

  /**
   * 取某个账号的明文凭据，供连通性测试使用。
   *
   * 与 [credentialFor] 分开：这里按主键取（管理员指定要测哪一条，禁用状态也能测），
   * 后者按音源取（播放链路只认启用的）。
   */
  async credentialOf(id: number): Promise<MusicSourceCredential | undefined> {
    return this.database.first<MusicSourceCredential>(
      `SELECT token, uid FROM music_source_account WHERE id = $1`,
      [id],
    );
  }

  /** 取某个音源当前生效的凭据。没有启用的账号时返回 undefined，调用方按匿名处理。 */
  credentialFor(source: string): Promise<MusicSourceCredential | undefined> {
    return this.database.first<MusicSourceCredential>(
      `SELECT token, uid FROM music_source_account
       WHERE source = $1 AND enabled = 1 AND token <> ''
       ORDER BY updated_at DESC, id DESC LIMIT 1`,
      [source],
    );
  }

  async create(source: string, patch: MusicSourceAccountPatch): Promise<MusicSourceAccountSummary> {
    const now = Date.now();
    const row = await this.database.first<AccountRow>(
      `INSERT INTO music_source_account
         (source, label, phone, token, uid, remark, enabled, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $8)
       RETURNING ${SUMMARY_COLUMNS}`,
      [
        source,
        patch.label ?? "",
        patch.phone ?? "",
        patch.token ?? "",
        patch.uid ?? "",
        patch.remark ?? "",
        patch.enabled === false ? 0 : 1,
        now,
      ],
    );
    return this.summaryOf(row!);
  }

  /**
   * 按登录结果写入或刷新凭据。
   *
   * 以 `(source, uid)` 为身份：同一个账号重新登录只刷新 token，不会堆出第二行。
   * 索引是带 `WHERE uid <> ''` 的部分索引，`ON CONFLICT` 必须带上同样的谓词才认。
   *
   * ⚠️ **`DO UPDATE` 里必须显式带 `enabled = 1`。** 漏了它，重复登录会保留这一行
   * 之前的停用状态 —— 于是出现最难查的一种表现：**后台显示登录成功、token 也是新的，
   * 但播放链路取不到凭据**（[credentialFor] 要求 `enabled = 1`），
   * 搜索和取址全部按匿名走，看起来就像「账号完全没被使用」。
   * 管理员刚刚亲自走完了短信登录，意图足够明确，这里重新启用是符合预期的行为。
   */
  async upsertByLogin(
    source: string,
    phone: string,
    token: string,
    uid: string,
    label: string,
  ): Promise<MusicSourceAccountSummary> {
    const now = Date.now();
    const row = await this.database.first<AccountRow>(
      `INSERT INTO music_source_account
         (source, label, phone, token, uid, enabled, last_status, last_error, last_checked_at, created_at, updated_at)
       VALUES ($1, $2, $3, $4, $5, 1, 'ok', '', $6, $6, $6)
       ON CONFLICT (source, uid) WHERE uid <> ''
       DO UPDATE SET token = EXCLUDED.token,
                     phone = EXCLUDED.phone,
                     label = CASE WHEN EXCLUDED.label <> '' THEN EXCLUDED.label ELSE music_source_account.label END,
                     enabled = 1,
                     last_status = 'ok',
                     last_error = '',
                     last_checked_at = EXCLUDED.last_checked_at,
                     updated_at = EXCLUDED.updated_at
       RETURNING ${SUMMARY_COLUMNS}`,
      [source, label, phone, token, uid, now],
    );
    return this.summaryOf(row!);
  }

  /**
   * 局部更新。只写提交过的字段。
   *
   * `CASE WHEN $n::boolean` 而不是 `COALESCE`：`label` 这类字段允许被显式改成空串，
   * 用 COALESCE 就永远清不掉。
   */
  async update(id: number, patch: MusicSourceAccountPatch): Promise<MusicSourceAccountSummary | undefined> {
    const row = await this.database.first<AccountRow>(
      `UPDATE music_source_account SET
         label      = CASE WHEN $2::boolean THEN $3  ELSE label      END,
         phone      = CASE WHEN $4::boolean THEN $5  ELSE phone      END,
         token      = CASE WHEN $6::boolean THEN $7  ELSE token      END,
         uid        = CASE WHEN $8::boolean THEN $9  ELSE uid        END,
         remark     = CASE WHEN $10::boolean THEN $11 ELSE remark    END,
         enabled    = CASE WHEN $12::boolean THEN $13 ELSE enabled   END,
         updated_at = $14
       WHERE id = $1
       RETURNING ${SUMMARY_COLUMNS}`,
      [
        id,
        patch.label !== undefined, patch.label ?? null,
        patch.phone !== undefined, patch.phone ?? null,
        patch.token !== undefined, patch.token ?? null,
        patch.uid !== undefined, patch.uid ?? null,
        patch.remark !== undefined, patch.remark ?? null,
        patch.enabled !== undefined, patch.enabled === undefined ? null : patch.enabled ? 1 : 0,
        Date.now(),
      ],
    );
    return row ? this.summaryOf(row) : undefined;
  }

  async setEnabled(id: number, enabled: boolean): Promise<MusicSourceAccountSummary | undefined> {
    const row = await this.database.first<AccountRow>(
      `UPDATE music_source_account SET enabled = $2, updated_at = $3 WHERE id = $1
       RETURNING ${SUMMARY_COLUMNS}`,
      [id, enabled ? 1 : 0, Date.now()],
    );
    return row ? this.summaryOf(row) : undefined;
  }

  /**
   * 记录一次连通性测试的结果。
   *
   * 测试失败也要落库，界面才能显示「上次为什么失败」。[note] 放探测到的真实音质，
   * 成功时才有意义。
   */
  async recordCheck(id: number, status: "ok" | "invalid", error: string, note = ""): Promise<void> {
    await this.database.run(
      `UPDATE music_source_account
       SET last_status = $2, last_error = $3, last_note = $4, last_checked_at = $5
       WHERE id = $1`,
      [id, status, error.slice(0, 500), note.slice(0, 200), Date.now()],
    );
  }

  async remove(id: number): Promise<boolean> {
    return (await this.database.run("DELETE FROM music_source_account WHERE id = $1", [id])) === 1;
  }

  /** 手机号与 token 都只回传掩码。token 全遮，手机号保留号段与尾号。 */
  private summaryOf(row: AccountRow): MusicSourceAccountSummary {
    return {
      id: row.id,
      source: row.source,
      label: row.label,
      maskedPhone: maskPhoneNumber(row.phone),
      maskedToken: this.mask(row.token, 4),
      uid: row.uid,
      enabled: row.enabled,
      remark: row.remark,
      lastStatus: row.lastStatus,
      lastError: row.lastError,
      lastNote: row.lastNote,
      lastCheckedAt: row.lastCheckedAt,
      createdAt: row.createdAt,
      updatedAt: row.updatedAt,
    };
  }

  /** 空值返回空串而不是 `••••`：界面要能区分「没配」和「配了但被掩码」。 */
  private mask(value: string, visibleTail: number): string {
    if (!value) return "";
    return `••••••••${value.slice(-visibleTail)}`;
  }
}
