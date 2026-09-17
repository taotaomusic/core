import { Module, forwardRef } from "@nestjs/common";
import { AdminAuthController } from "./admin-auth.controller";
import { AdminAuthService } from "./admin-auth.service";
import { AdminAuditService } from "./admin-audit.service";
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
    AdminAuditService,
    AdminBootstrapService,
    AdminUsersRepository,
    AdminSessionsRepository,
    AuditLogRepository,
    // RolesGuard 依赖 Reflector（Nest 核心提供），这里登记即可。
    RolesGuard,
  ],
  // 业务模块的管理控制器要用 AdminAuthGuard 判身份、AdminAuditService 写审计，
  // 而守卫与审计服务的依赖都在**声明 Controller 的模块**里解析，所以必须导出。
  exports: [AdminAuthService, AdminUsersRepository, AdminAuditService],
})
export class AdminAuthModule {}
