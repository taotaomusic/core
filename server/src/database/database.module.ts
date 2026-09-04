import { Global, Module } from "@nestjs/common";
import { DatabaseService } from "./database.service";

/** 全局数据库模块：各业务模块直接注入 [DatabaseService]，不必逐个 imports。 */
@Global()
@Module({
  providers: [DatabaseService],
  exports: [DatabaseService],
})
export class DatabaseModule {}
