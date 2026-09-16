import { Module, forwardRef } from "@nestjs/common";
import { LdapService } from "./ldap.service";
import { AdminAuthModule } from "../admin-auth/admin-auth.module";

/**
 * LDAP/SSO 集成模块。
 *
 * 提供 LDAP 认证服务，支持：
 * - LDAP 用户认证
 * - 组信息获取和角色映射
 * - 自动同步到本地 admin_users 表
 */
@Module({
    imports: [forwardRef(() => AdminAuthModule)],
    providers: [LdapService],
    exports: [LdapService],
})
export class LdapModule {}
