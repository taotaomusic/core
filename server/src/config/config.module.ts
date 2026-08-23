import { Global, Module } from "@nestjs/common";
import { ConfigModule } from "@nestjs/config";
import { AppConfigService } from "./app-config.service";
import { validateEnvironment } from "./env.validation";

/**
 * 全局配置模块。
 *
 * `@nestjs/config` 负责读取 `.env` 并写入 process.env（已存在的环境变量优先），
 * 替掉迁移前那个手写的 .env 解析。`ENV_FILE` 仍然可以指定其它路径。
 */
@Global()
@Module({
  imports: [
    ConfigModule.forRoot({
      isGlobal: true,
      envFilePath: process.env.ENV_FILE ?? ".env",
      validate: validateEnvironment,
      cache: true,
    }),
  ],
  providers: [AppConfigService],
  exports: [AppConfigService],
})
export class AppConfigModule {}
