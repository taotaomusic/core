import { Module } from "@nestjs/common";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";
import { AdminSettingController } from "./admin-setting.controller";
import { AdminSettingRepository } from "./admin-setting.repository";

/**
 * 管理端系统设置（密钥类运维配置）。
 *
 * 导出 [AdminSettingRepository]：webhook 等运行时消费方要在自己的模块里注入它，
 * 而依赖在声明 Controller 的模块里解析，所以必须 export。
 */
@Module({
  imports: [AdminAuthModule],
  controllers: [AdminSettingController],
  providers: [AdminSettingRepository],
  exports: [AdminSettingRepository],
})
export class AdminSettingModule {}
