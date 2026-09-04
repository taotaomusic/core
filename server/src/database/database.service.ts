import { Injectable, Logger, type OnModuleDestroy, type OnModuleInit } from "@nestjs/common";
import { Pool, types, type PoolClient, type QueryResultRow } from "pg";
import { AppConfigService } from "../config/app-config.service";
import { runMigrations } from "./migrations";

/** 启动时等待数据库就绪的重试次数与间隔。 */
const CONNECT_ATTEMPTS = 10;
const CONNECT_RETRY_MS = 1000;

/**
 * PostgreSQL 连接池。
 *
 * 对外只暴露 first / all / run 三个窄接口，不把 `Pool` 本身交出去 ——
 * 一旦有人 `pool.connect()` 取出 client 又跨上游请求持有它，8 个并发搜索就能耗尽连接池。
 *
 * 各仓库不在构造函数里做任何查询：Nest 会先实例化全部 provider 再调用 onModuleInit，
 * 那时表还没建出来。
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(DatabaseService.name);
  private readonly pool: Pool;

  constructor(config: AppConfigService) {
    // int8 默认被解析成 JS 字符串（64 位整数可能超出 Number.MAX_SAFE_INTEGER）。
    // 本库的 bigint 列只存毫秒时间戳，约 1.7e12，远小于 2^53，转成 number 是安全的，
    // 而不转会让 createdAt / configVersion 之类字段从 number 静默漂成 string。
    // 反过来说：今后不要把雪花 ID 这类真正的 64 位值放进 bigint 列。
    types.setTypeParser(types.builtins.INT8, Number);

    this.pool = new Pool({
      connectionString: config.databaseUrl,
      max: 10,
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 5_000,
    });

    // 必须挂这个监听：数据库重启或网络抖动会在**空闲连接**上抛 error，
    // 而 EventEmitter 的未处理 error 会直接终止进程 —— 挂掉的是唯一的热更新通道。
    this.pool.on("error", (error) => {
      this.logger.error(`空闲连接异常：${error.message}`);
    });
  }

  async onModuleInit(): Promise<void> {
    await this.waitForDatabase();
    await runMigrations(this.pool);
    this.logger.log("数据库已就绪");
  }

  async onModuleDestroy(): Promise<void> {
    await this.pool.end();
  }

  /** 取第一行，没有命中时返回 undefined。 */
  async first<T extends QueryResultRow>(sql: string, params: unknown[] = []): Promise<T | undefined> {
    const result = await this.pool.query<T>(sql, params);
    return result.rows[0];
  }

  async all<T extends QueryResultRow>(sql: string, params: unknown[] = []): Promise<T[]> {
    return (await this.pool.query<T>(sql, params)).rows;
  }

  /** 执行写操作，返回受影响行数。pg 的 rowCount 可能是 null，这里归一成 number。 */
  async run(sql: string, params: unknown[] = []): Promise<number> {
    return (await this.pool.query(sql, params)).rowCount ?? 0;
  }

  /** 少量需要「先清旧状态、再写新状态」原子性的业务操作使用此事务入口。 */
  async transaction<T>(action: (client: PoolClient) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("BEGIN");
      const result = await action(client);
      await client.query("COMMIT");
      return result;
    } catch (error) {
      await client.query("ROLLBACK");
      throw error;
    } finally {
      client.release();
    }
  }

  /** 就绪探测。数据库现在是独立进程，健康检查不能只报告自己还活着。 */
  async ping(): Promise<void> {
    await this.pool.query("SELECT 1");
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
        await this.pool.query("SELECT 1");
        return;
      } catch (error) {
        const reason = error instanceof Error ? error.message : String(error);
        if (attempt === CONNECT_ATTEMPTS) {
          throw new Error(`连接数据库失败（已重试 ${CONNECT_ATTEMPTS} 次）：${reason}`);
        }
        this.logger.warn(`连接数据库失败，${CONNECT_RETRY_MS}ms 后重试（${attempt}/${CONNECT_ATTEMPTS}）：${reason}`);
        await new Promise((done) => setTimeout(done, CONNECT_RETRY_MS));
      }
    }
  }
}
