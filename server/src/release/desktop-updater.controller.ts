import { Controller, Get } from "@nestjs/common";
import { Public } from "../common/decorators/public.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { DesktopUpdaterService } from "./desktop-updater.service";

/**
 * Windows（Tauri）自动更新清单代理。
 *
 * tauri-plugin-updater 的 `endpoints` 指到这里，由本端点转发 GitHub 的 `latest.json`
 * 并把安装包直链改写成代理直链提速。`@RawResponse()` 让它返回**裸 JSON**，
 * 不套 `{code,data}` 信封——Tauri 按原生清单格式解析。
 */
@Public()
@RawResponse()
@Controller("desktop/updater")
export class DesktopUpdaterController {
  constructor(private readonly updater: DesktopUpdaterService) {}

  @RateLimit("app")
  @Get("latest.json")
  latestJson() {
    return this.updater.getManifest();
  }
}
