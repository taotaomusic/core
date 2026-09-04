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
   */
  @Public()
  @Get()
  async status() {
    try {
      await this.database.ping();
    } catch (error) {
      throw ApiErrors.upstream(`数据库不可用：${error instanceof Error ? error.message : String(error)}`);
    }
    return { status: "up" };
  }
}
