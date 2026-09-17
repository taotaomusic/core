import { Module, forwardRef } from "@nestjs/common";
import { AdminAuthController } from "./admin-auth.controller";
import { AdminAuthService } from "./admin-auth.service";
import { AdminBootstrapService } from "./admin-bootstrap.service";
import { AdminUsersRepository } from "./admin-users.repository";
import { AdminSessionsRepository } from "./admin-sessions.repository";
import { AuditLogRepository } from "./audit-log.repository";
import { RolesGuard } from "./roles.guard";
import { LdapModule } from "../ldap/ldap.module";

/**
 * 管理员认证模块。
 *
 * 与 [LdapModule] 是双向依赖：登录接口要用 LdapService 校验目录凭据，
 * 而 LDAP 服务要用本模块的仓储与认证服务同步账号 —— 两边都用 forwardRef。
 */
@Module({
  imports: [forwardRef(() => LdapModule)],
  controllers: [AdminAuthController],
  providers: [
    AdminAuthService,
    AdminBootstrapService,
    AdminUsersRepository,
    AdminSessionsRepository,
    AuditLogRepository,
    // RolesGuard 依赖 Reflector（Nest 核心提供），这里登记即可。
    RolesGuard,
  ],
  exports: [AdminAuthService, AdminUsersRepository],
})
export class AdminAuthModule {}
