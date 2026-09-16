import { Module } from "@nestjs/common";
import { AdminAuthController } from "./admin-auth.controller";
import { AdminAuthService } from "./admin-auth.service";
import { AdminUsersRepository } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";

@Module({
  controllers: [AdminAuthController],
  providers: [AdminAuthService, AdminUsersRepository, AdminSessionsRepository, AuditLogRepository],
  exports: [AdminAuthService, AdminUsersRepository],
})
export class AdminAuthModule {}