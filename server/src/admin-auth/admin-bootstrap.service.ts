import { Injectable, Logger, type OnApplicationBootstrap } from "@nestjs/common";
import { AdminAuthService } from "./admin-auth.service";
import { AdminUsersRepository } from "./admin-users.repository";

const DEFAULT_ADMIN_USERNAME = "admin";
const DEFAULT_ADMIN_PASSWORD = "admin123";

/**
 * 首次启动时创建默认超级管理员。
 *
 * 必须挂在 `onApplicationBootstrap`，不能放在 main.ts 里手动 `app.get(...)`
 * 之后调用：建表发生在 [DatabaseService.onModuleInit]，而 main.ts 里那段代码
 * 执行得更早，查表只会得到「关系 admin_users 不存在」，默认账号永远建不出来
 * —— 于是全新部署的后台一个人也进不去。
 *
 * 用 `findAnyByUsername` 而不是过滤禁用状态的版本：管理员如果被显式禁用过，
 * 重启不该把它复活。
 */
@Injectable()
export class AdminBootstrapService implements OnApplicationBootstrap {
  private readonly logger = new Logger("Bootstrap");

  constructor(
    private readonly adminUsers: AdminUsersRepository,
    private readonly adminAuth: AdminAuthService,
  ) {}

  async onApplicationBootstrap(): Promise<void> {
    try {
      if (await this.adminUsers.findAnyByUsername(DEFAULT_ADMIN_USERNAME)) return;
      const { hash, salt } = this.adminAuth.hashPassword(DEFAULT_ADMIN_PASSWORD);
      await this.adminUsers.create(
        DEFAULT_ADMIN_USERNAME, hash, salt, "超级管理员", "super_admin", null, null,
      );
      this.logger.log(
        `已创建默认管理员账号：${DEFAULT_ADMIN_USERNAME} / ${DEFAULT_ADMIN_PASSWORD}（请尽快修改密码）`);
    } catch (error) {
      // 建不出来不该拦住整个服务：运维仍可手工插库，其余接口也不受影响。
      this.logger.warn(`创建默认管理员失败：${(error as Error).message}`);
    }
  }
}
