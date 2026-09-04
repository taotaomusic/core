import { Module } from "@nestjs/common";
import { AuthController } from "./auth.controller";
import { AuthService } from "./auth.service";
import { RefreshTokensRepository } from "./refresh-tokens.repository";
import { UsersRepository } from "./users.repository";
import { EmailVerificationService } from "./email-verification.service";

/**
 * 认证模块。
 * 导出 [AuthService] 与 [UsersRepository] 供全局访问令牌守卫和其它模块使用。
 */
@Module({
  controllers: [AuthController],
  providers: [AuthService, UsersRepository, RefreshTokensRepository, EmailVerificationService],
  exports: [AuthService, UsersRepository],
})
export class AuthModule {}
