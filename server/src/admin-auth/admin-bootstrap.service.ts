import { Injectable, Logger, type OnApplicationBootstrap } from "@nestjs/common";
import { randomBytes } from "node:crypto";
import { AppConfigService } from "../config/app-config.service";
import { AdminAuthService } from "./admin-auth.service";
import { AdminUsersRepository } from "./admin-users.repository";

const DEFAULT_ADMIN_USERNAME = "admin";

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
 *
 * **口令来源**（迁移前是写死的 `admin123`，等于给每个新部署开了一扇公开的门）：
 *
 * - 配了 `ADMIN_INITIAL_PASSWORD` → 用它，日志**只说明来源、不打印口令**；
 * - 没配 → 随机生成 32 字节口令，用 WARN 级别**只打印这一次**。
 *
 * 两种情况都会把账号标记为「首次登录必须改密」，改密前除 `me` /
 * `change-password` / `logout` 外一律拒绝（拦截在 [AdminAuthGuard]）。
 * 「只打印一次」由下面的存在性判断天然保证：账号已存在就直接 return。
 */
@Injectable()
export class AdminBootstrapService implements OnApplicationBootstrap {
  private readonly logger = new Logger("Bootstrap");

  constructor(
    private readonly config: AppConfigService,
    private readonly adminUsers: AdminUsersRepository,
    private readonly adminAuth: AdminAuthService,
  ) {}

  async onApplicationBootstrap(): Promise<void> {
    try {
      if (await this.adminUsers.findAnyByUsername(DEFAULT_ADMIN_USERNAME)) return;

      const configured = this.config.adminInitialPassword;
      const password = configured || randomBytes(32).toString("base64url");
      const { hash, salt } = this.adminAuth.hashPassword(password);
      await this.adminUsers.create(
        DEFAULT_ADMIN_USERNAME, hash, salt, "超级管理员", "super_admin", null, null, true,
      );

      if (configured) {
        this.logger.log(
          `已创建默认管理员账号 ${DEFAULT_ADMIN_USERNAME}（口令取自 ADMIN_INITIAL_PASSWORD），首次登录必须改密`,
        );
      } else {
        // WARN 而不是 LOG：口令只在这里出现一次，运维必须看见；
        // 同时明确提示这是公网可达时的接管风险点。
        this.logger.warn(
          `已创建默认管理员账号 ${DEFAULT_ADMIN_USERNAME}，初始口令：${password}\n` +
          "该口令只显示这一次，首次登录后必须立即修改。" +
          "若要固定初始口令，请设置 ADMIN_INITIAL_PASSWORD。",
        );
      }
    } catch (error) {
      // 建不出来不该拦住整个服务：运维仍可手工插库，其余接口也不受影响。
      this.logger.warn(`创建默认管理员失败：${(error as Error).message}`);
    }
  }
}
