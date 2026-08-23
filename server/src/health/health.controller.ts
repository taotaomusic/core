import { Controller, Get } from "@nestjs/common";
import { Public } from "../common/decorators/public.decorator";

/** 健康检查。挂在全局前缀之外，路径保持 `/health`。 */
@Controller("health")
export class HealthController {
  @Public()
  @Get()
  status() {
    return { status: "up" };
  }
}
