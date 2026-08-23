import { Injectable, Logger, type OnModuleDestroy, type OnModuleInit } from "@nestjs/common";
import Database from "better-sqlite3";
import { mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { AppConfigService } from "../config/app-config.service";
import { runMigrations } from "./migrations";

/**
 * SQLite 连接。
 *
 * better-sqlite3 是同步 API，所有查询都在事件循环上执行 —— 目前的查询都是
 * 主键或唯一索引查找，代价可忽略；若将来出现全表扫描要另行处理。
 *
 * 各仓库（repository）不在构造函数里预编译语句：Nest 会先实例化全部 provider
 * 再调用 onModuleInit，那时表还没建出来，预编译会失败。
 */
@Injectable()
export class DatabaseService implements OnModuleInit, OnModuleDestroy {
  private readonly logger = new Logger(DatabaseService.name);
  readonly connection: Database.Database;

  constructor(config: AppConfigService) {
    mkdirSync(dirname(config.databasePath), { recursive: true });
    this.connection = new Database(config.databasePath);
    this.connection.pragma("journal_mode = WAL");
  }

  onModuleInit(): void {
    runMigrations(this.connection);
    this.logger.log("数据库已就绪");
  }

  onModuleDestroy(): void {
    this.connection.close();
  }
}
