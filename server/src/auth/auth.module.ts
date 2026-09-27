import { Module } from "@nestjs/common";
import { AuthController } from "./auth.controller";
import { AuthService } from "./auth.service";
import { RefreshTokensRepository } from "./refresh-tokens.repository";
import { UsersRepository } from "./users.repository";
import { EmailVerificationService } from "./email-verification.service";
import { FilesModule } from "../files/files.module";

/**
 * 认证模块。
 * 导出 [AuthService] 与 [UsersRepository] 供全局访问令牌守卫和其它模块使用。
 * 头像上传依赖 [AvatarStoreService]，依赖在声明 Controller 的模块里解析，
 * 所以这里必须 imports [FilesModule]。
 */
@Module({
  imports: [FilesModule],
  controllers: [AuthController],
  providers: [AuthService, UsersRepository, RefreshTokensRepository, EmailVerificationService],
  exports: [AuthService, UsersRepository],
})
export class AuthModule {}
