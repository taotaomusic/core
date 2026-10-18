import { Injectable, Logger, type OnModuleDestroy, type OnModuleInit } from "@nestjs/common";
import { Pool, types, type PoolClient, type QueryResult, type QueryResultRow } from "pg";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { dbRoutingContext } from "./db-routing.context";
import { runMigrations } from "./migrations";

/** 启动时等待数据库就绪的重试次数与间隔。 */
const CONNECT_ATTEMPTS = 10;
const CONNECT_RETRY_MS = 1000;

/** 主库连续连接失败多少次后熔断，以及熔断窗口时长。 */
const PRIMARY_FAILURE_THRESHOLD = 3;
const PRIMARY_CIRCUIT_OPEN_MS = 15_000;

/** 副本连续连接失败多少次后降级为直连主库，以及降级窗口时长。 */
const REPLICA_FAILURE_THRESHOLD = 3;
const REPLICA_DEGRADED_MS = 30_000;

/** 同一写语句重复误入读路径时，告警最多每 5 分钟打一条，防止刷爆日志。 */
const WRITE_MISS_WARNING_INTERVAL_MS = 300_000;

/** 主库不可用时的业务码：5030/5031/5032 已被 GitHub 登录、可选组件、图片生成占用。 */
const PRIMARY_UNAVAILABLE_CODE = 5033;
const PRIMARY_UNAVAILABLE_MESSAGE = "数据库主库暂时不可用，请稍后重试";

/**
 * 连接类错误的 SQLSTATE 与 Node 错误码。
 *
 * 只有这类失败才计入熔断/降级：语法错误、约束冲突等业务性失败说明连接本身是好的，
 * 若也计入，一段写错的 SQL 就能把整个主库「熔断」掉。SQLSTATE 的 08 类前缀
 * （connection_exception）在 isConnectivityError 里按前缀兜住。
 */
const CONNECTIVITY_ERROR_CODES = new Set([
  "ECONNREFUSED", "ECONNRESET", "EPIPE", "ETIMEDOUT", "EHOSTUNREACH", "ENETUNREACH",
  "57P01", "57P02", "57P03", "53300",
]);

/** 会写库的 SQL 首词（安全网用，见 isWriteStatement）。 */
const WRITE_SQL_FIRST_WORDS = new Set([
  "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE",
  "CREATE", "ALTER", "DROP", "COMMENT", "GRANT", "REVOKE",
  "REINDEX", "VACUUM", "ANALYZE", "CLUSTER", "REFRESH", "DO", "CALL",
]);

/**
 * 单查询路由覆盖。写后立读等一致性敏感的读（如写后立刻回读自己刚写的行）
 * 用 `{ primary: true }` 强制走主库；显式标记优先于请求上下文与安全网判断。
 */
export type QueryOptions = { primary?: boolean };

/**
 * PostgreSQL 连接池（主库 + 可选只读副本）。
 *
 * 对外只暴露 first / all / run 三个窄接口，不把 `Pool` 本身交出去 ——
 * 一旦有人 `pool.connect()` 取出 client 又跨上游请求持有它，8 个并发搜索就能耗尽连接池。
 * （唯一的例外是 transaction，它自己管理 client 的获取与提交。）
 *
 * 路由规则（多地区部署：每个地区一个本地只读副本，主库只有一处）：
 * - 写请求（POST/PUT/PATCH/DELETE）整体走主库；读请求默认走本地副本。
 * - 单查询可显式 pin 主库（QueryOptions.primary），见 resolveTarget 的优先序。
 * - SQL 首词安全网：读上下文里混进来的写语句自动改走主库并告警，兜住漏标的仓库。
 * - 未配置 DATABASE_REPLICA_URL 时视同全部走主库，行为与单池部署完全一致。
 *
 * 各仓库不在构造函数里做任何查询：Nest 会先实例化全部 provider 再调用 onModuleInit，
 * 那时表还没建出来。
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(DatabaseService.name);
  private readonly primaryPool: Pool;
  private readonly replicaPool: Pool | null;

  /** 主库熔断状态：连续连接失败进熔断窗口，窗口内写请求快速失败而不是干等连接超时。 */
  private primaryFailures = 0;
  private primaryCircuitOpenUntil = 0;

  /** 副本降级状态：连续连接失败后暂时直连主库，窗口过期再试。 */
  private replicaFailures = 0;
  private replicaDegradedUntil = 0;

  /** 写语句误入读路径的告警节流表（指纹 → 上次告警时刻）。 */
  private readonly writeMissWarnedAt = new Map<string, number>();

  constructor(config: AppConfigService) {
    // int8 默认被解析成 JS 字符串（64 位整数可能超出 Number.MAX_SAFE_INTEGER）。
    // 本库的 bigint 列只存毫秒时间戳，约 1.7e12，远小于 2^53，转成 number 是安全的，
    // 而不转会让 createdAt / configVersion 之类字段从 number 静默漂成 string。
    // 反过来说：今后不要把雪花 ID 这类真正的 64 位值放进 bigint 列。
    types.setTypeParser(types.builtins.INT8, Number);

    this.primaryPool = this.createPool(config.databaseUrl, "主库");
    this.replicaPool = config.databaseReplicaUrl
      ? this.createPool(config.databaseReplicaUrl, "只读副本")
      : null;
  }

  async onModuleInit(): Promise<void> {
    await this.waitForDatabase();
    await runMigrations(this.primaryPool);
    if (this.replicaPool) this.logger.log("已配置只读副本，读请求默认走本地副本");
    this.logger.log("数据库已就绪");
  }

  async onModuleDestroy(): Promise<void> {
    await Promise.all([
      this.primaryPool.end(),
      this.replicaPool ? this.replicaPool.end() : Promise.resolve(),
    ]);
  }

  /** 取第一行，没有命中时返回 undefined。 */
  async first<T extends QueryResultRow>(
    sql: string,
    params: unknown[] = [],
    options: QueryOptions = {},
  ): Promise<T | undefined> {
    const result = await this.query<T>(sql, params, options);
    return result.rows[0];
  }

  async all<T extends QueryResultRow>(
    sql: string,
    params: unknown[] = [],
    options: QueryOptions = {},
  ): Promise<T[]> {
    return (await this.query<T>(sql, params, options)).rows;
  }

  /** 执行写操作，返回受影响行数。pg 的 rowCount 可能是 null，这里归一成 number。 */
  async run(sql: string, params: unknown[] = [], options: QueryOptions = {}): Promise<number> {
    return (await this.query(sql, params, options)).rowCount ?? 0;
  }

  /**
   * 少量需要「先清旧状态、再写新状态」原子性的业务操作使用此事务入口。
   *
   * 固定走主库：事务里拿到的 client 直接执行 SQL（不经过本服务的路由），
   * 副本是只读的，事务落到副本必然失败。熔断窗口内快速失败。
   */
  async transaction<T>(action: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.connectPrimary();
    try {
      await client.query("BEGIN");
      const result = await action(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      // 回滚自身失败（连接已断）不能覆盖原始异常，否则调用方看到的是连接错误
      // 而不是真实的业务错误。
      try {
        await client.query("ROLLBACK");
      } catch (rollbackError) {
        this.logger.warn(`事务回滚失败：${messageOf(rollbackError)}`);
      }
      throw error;
    } finally {
      client.release();
    }
  }

  /**
   * 就绪探测。数据库是独立进程，健康检查不能只报告自己还活着。
   *
   * 主库是写路径与热更新通道的依赖，副本只是读分担：
   * - 主库可用 → "primary"（健康检查对外报 `up`）；
   * - 主库不可用但副本还能读 → "degraded"（读服务仍在，进程不应被摘除）；
   * - 两者都不可用 → 抛出主库错误。
   */
  async ping(): Promise<"primary" | "degraded"> {
    try {
      await this.queryPrimary("SELECT 1", []);
      return "primary";
    } catch (primaryError) {
      if (await this.replicaReadable()) return "degraded";
      throw primaryError;
    }
  }

  /** 按显式标记与请求上下文选择连接池，并执行首词安全网检查。 */
  private query<T extends QueryResultRow>(
    sql: string,
    params: unknown[],
    options: QueryOptions,
  ): Promise<QueryResult<T>> {
    const target = this.resolveTarget(sql, options);
    return target === "replica" ? this.queryReplica<T>(sql, params) : this.queryPrimary<T>(sql, params);
  }

  /**
   * 路由判定，优先序从高到低：
   * 1. 查询级显式 pin（`{ primary: true }`）；
   * 2. 未配置副本 → 主库；
   * 3. 请求上下文非读（写请求、管理端路径、写后立读窗口）→ 主库；
   * 4. SQL 首词是写语句 → 主库 + 节流告警（兜底，正常应由 1 显式兜住）；
   * 5. 其余读 → 副本（副本不可用时由 queryReplica 再降级回主库）。
   */
  private resolveTarget(sql: string, options: QueryOptions): "primary" | "replica" {
    if (options.primary || !this.replicaPool) return "primary";
    const context = dbRoutingContext.getStore();
    if (!context || context.defaultTarget !== "replica") return "primary";
    if (isWriteStatement(sql)) {
      this.warnWriteOnReplica(sql);
      return "primary";
    }
    return "replica";
  }

  /** 主库查询：熔断窗口内快速失败（503/5033），连接类失败累计进熔断计数。 */
  private async queryPrimary<T extends QueryResultRow>(
    sql: string,
    params: unknown[],
  ): Promise<QueryResult<T>> {
    if (Date.now() < this.primaryCircuitOpenUntil) {
      throw ApiErrors.serviceUnavailable(PRIMARY_UNAVAILABLE_CODE, PRIMARY_UNAVAILABLE_MESSAGE);
    }
    try {
      const result = await this.primaryPool.query<T>(sql, params);
      this.primaryFailures = 0;
      return result;
    } catch (error) {
      this.notePrimaryFailure(error);
      throw error;
    }
  }

  /** 副本查询：降级窗口内直连主库；连接类失败累计降级计数，本次立即回退主库。 */
  private async queryReplica<T extends QueryResultRow>(
    sql: string,
    params: unknown[],
  ): Promise<QueryResult<T>> {
    const replica = this.replicaPool;
    if (!replica || Date.now() < this.replicaDegradedUntil) {
      return this.queryPrimary<T>(sql, params);
    }
    try {
      const result = await replica.query<T>(sql, params);
      this.replicaFailures = 0;
      return result;
    } catch (error) {
      // 非连接类错误（语法错、权限错）说明副本本身是好的，直接抛出，
      // 不能让主库重试掩盖真正的程序错误。
      if (!isConnectivityError(error)) throw error;
      this.replicaFailures += 1;
      if (this.replicaFailures >= REPLICA_FAILURE_THRESHOLD) {
        this.replicaFailures = 0;
        this.replicaDegradedUntil = Date.now() + REPLICA_DEGRADED_MS;
        this.logger.error(`只读副本连续连接失败，降级为直连主库 ${REPLICA_DEGRADED_MS}ms`);
      } else {
        this.logger.warn(
          `只读副本查询失败（${this.replicaFailures}/${REPLICA_FAILURE_THRESHOLD}），本次改走主库：${messageOf(error)}`,
        );
      }
      return this.queryPrimary<T>(sql, params);
    }
  }

  /** 取主库连接（事务用）：与 queryPrimary 同一套熔断与计数逻辑。 */
  private async connectPrimary(): Promise<PoolClient> {
    if (Date.now() < this.primaryCircuitOpenUntil) {
      throw ApiErrors.serviceUnavailable(PRIMARY_UNAVAILABLE_CODE, PRIMARY_UNAVAILABLE_MESSAGE);
    }
    try {
      const client = await this.primaryPool.connect();
      this.primaryFailures = 0;
      return client;
    } catch (error) {
      this.notePrimaryFailure(error);
      throw error;
    }
  }

  private notePrimaryFailure(error: unknown): void {
    if (!isConnectivityError(error)) return;
    this.primaryFailures += 1;
    if (this.primaryFailures >= PRIMARY_FAILURE_THRESHOLD) {
      this.primaryFailures = 0;
      this.primaryCircuitOpenUntil = Date.now() + PRIMARY_CIRCUIT_OPEN_MS;
      this.logger.error(
        `主库连续连接失败，熔断 ${PRIMARY_CIRCUIT_OPEN_MS}ms（期间写请求立即返回 503/${PRIMARY_UNAVAILABLE_CODE}）`,
      );
    }
  }

  /** 同一语句 5 分钟内只告警一次；顺手清扫过期指纹，防止表单调增长。 */
  private warnWriteOnReplica(sql: string): void {
    const fingerprint = sql.replace(/\s+/g, " ").trim().slice(0, 120);
    const now = Date.now();
    const lastWarnedAt = this.writeMissWarnedAt.get(fingerprint);
    if (lastWarnedAt !== undefined && now - lastWarnedAt < WRITE_MISS_WARNING_INTERVAL_MS) return;
    for (const [key, at] of this.writeMissWarnedAt) {
      if (now - at >= WRITE_MISS_WARNING_INTERVAL_MS) this.writeMissWarnedAt.delete(key);
    }
    this.writeMissWarnedAt.set(fingerprint, now);
    this.logger.warn(`读上下文检测到写语句，已自动改走主库（应为该仓库补显式 primary 选项）：${fingerprint}`);
  }

  /** 副本是否还能响应（ping 的降级判定用，不抛错、不进降级计数）。 */
  private async replicaReadable(): Promise<boolean> {
    const replica = this.replicaPool;
    if (!replica) return false;
    try {
      await replica.query("SELECT 1");
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 等数据库起来。
   *
   * SQLite 没有这种失败模式，换成 PostgreSQL 后机器重启时 Node 可能先于数据库启动。
   * 有界重试后仍然失败就让启动崩掉，交给进程管理器重启，避免无限等待掩盖配置错误。
   */
  private async waitForDatabase(): Promise<void> {
    for (let attempt = 1; attempt <= CONNECT_ATTEMPTS; attempt++) {
      try {
        await this.primaryPool.query("SELECT 1");
        return;
      } catch (error) {
        const reason = messageOf(error);
        if (attempt === CONNECT_ATTEMPTS) {
          throw new Error(`连接数据库失败（已重试 ${CONNECT_ATTEMPTS} 次）：${reason}`);
        }
        this.logger.warn(`连接数据库失败，${CONNECT_RETRY_MS}ms 后重试（${attempt}/${CONNECT_ATTEMPTS}）：${reason}`);
        await new Promise((done) => setTimeout(done, CONNECT_RETRY_MS));
      }
    }
  }

  private createPool(connectionString: string, label: string): Pool {
    const pool = new Pool({
      connectionString,
      max: 10,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
    });

    // 必须挂这个监听：数据库重启或网络抖动会在**空闲连接**上抛 error，
    // 而 EventEmitter 的未处理 error 会直接终止进程 —— 挂掉的是唯一的热更新通道。
    pool.on("error", (error) => {
      this.logger.error(`${label}空闲连接异常：${error.message}`);
    });
    return pool;
  }
}

/**
 * SQL 首词安全网：读上下文（GET 请求）里混进来的写语句不能在只读副本上执行
 * （热备副本会直接报「read-only transaction」），这里识别出来改走主库。
 *
 * 之所以需要它：`first()` 既能执行 `SELECT` 也承接了各仓库的
 * `INSERT/UPDATE ... RETURNING`，单看方法名无法判断读写。漏 pin 的写语句如果
 * 不兜住就是用户可见的 502，因此宁可保守：识别不出读写倾向的形态（WITH CTE、
 * SELECT FOR UPDATE）一律按写处理，代价只是多走一次主库读。
 */
function isWriteStatement(sql: string): boolean {
  const head = stripLeadingComments(sql);
  const firstWord = (head.match(/^[A-Za-z]+/) ?? [""])[0].toUpperCase();
  if (WRITE_SQL_FIRST_WORDS.has(firstWord)) return true;
  if (firstWord === "WITH") return true;
  return firstWord === "SELECT" && /\bFOR\s+(UPDATE|NO\s+KEY\s+UPDATE|SHARE)\b/i.test(head);
}

/** 去掉 SQL 开头的空白与注释（`--` 与 `/*` 开头的两种注释各剥一层），露出首个关键字。 */
function stripLeadingComments(sql: string): string {
  let text = sql.trimStart();
  for (;;) {
    if (text.startsWith("--")) {
      const end = text.indexOf("\n");
      text = end === -1 ? "" : text.slice(end + 1).trimStart();
      continue;
    }
    if (text.startsWith("/*")) {
      const end = text.indexOf("*/", 2);
      text = end === -1 ? "" : text.slice(end + 2).trimStart();
      continue;
    }
    return text;
  }
}

/**
 * 连接类失败判定：SQLSTATE 08 类前缀（connection_exception）、几类明确的
 * Node 网络错误码，以及 pg-pool 的超时/断开消息。业务性 SQL 错误（42xxx、
 * 23xxx 等）一律不算 —— 它们不该触发熔断或降级。
 */
function isConnectivityError(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  const code = (error as { code?: unknown }).code;
  if (typeof code === "string" && (CONNECTIVITY_ERROR_CODES.has(code) || code.startsWith("08"))) {
    return true;
  }
  return /timeout exceeded|Connection terminated|ECONNREFUSED|ECONNRESET/i.test(error.message);
}

function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
