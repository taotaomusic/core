import { Module } from "@nestjs/common";
import { AuthModule } from "../auth/auth.module";
import { ImController } from "./im.controller";
import { ImRepository } from "./im.repository";
import { ImService } from "./im.service";
import { WukongImClient } from "./wukong-im.client";

/** 悟空 IM 适配模块：业务身份与权限仍由桃桃音乐后端持有。 */
@Module({
  imports: [AuthModule],
  controllers: [ImController],
  providers: [ImRepository, ImService, WukongImClient],
})
export class ImModule {}
