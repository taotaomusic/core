import { Controller, Get } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { DatabaseService } from "../database/database.service";

/** 健康检查。挂在全局前缀之外，路径保持 `/health`。 */
@Controller("health")
export class HealthController {
  constructor(private readonly database: DatabaseService) {}

  /**
   * 数据库换成独立进程后，只报告"自己还活着"已经没有意义 —— 这里探一次连接。
   * 客户端从不调这个接口，加就绪探测不影响任何契约。
   *
   * 多地区部署下有两种健康值：
   * - `up`：主库（与副本，若配置）可用，一切正常；
   * - `degraded`：主库不可用但副本还能读 —— 写请求已由熔断快速失败（503/5033），
   *   读服务仍可支撑，编排系统不应把进程摘掉，但需要告警人工介入主库。
   */
  @Public()
  @Get()
  async status() {
    try {
      const target = await this.database.ping();
      return { status: target === "primary" ? "up" : "degraded" };
    } catch (error) {
      throw ApiErrors.upstream(`数据库不可用：${error instanceof Error ? error.message : String(error)}`);
    }
  }
}
