import { Injectable, Logger } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";

const REQUEST_TIMEOUT_MS = 5_000;

/**
 * 悟空 IM 产品 API 的最小适配层。
 *
 * 它仅在服务端通过 `127.0.0.1:5001` 调用，客户端永远拿不到此地址或 API Token。悟空 IM
 * v2.2.5 的用户 Token 与设备退出接口没有统一业务信封，因此只以 HTTP 2xx 判定成功。
 */
@Injectable()
export class WukongImClient {
  private readonly logger = new Logger(WukongImClient.name);

  constructor(private readonly config: AppConfigService) {}

  async registerUserToken(input: { uid: string; token: string; deviceFlag: number; deviceLevel: number }): Promise<void> {
    await this.post("/user/token", {
      uid: input.uid,
      token: input.token,
      device_flag: input.deviceFlag,
      device_level: input.deviceLevel,
    });
  }

  async quitDevice(uid: string, deviceFlag: number): Promise<void> {
    await this.post("/user/device_quit", { uid, device_flag: deviceFlag });
  }

  private async post(path: string, body: Record<string, unknown>): Promise<void> {
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await fetch(`${this.config.imInternalApiBaseUrl}${path}`, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          ...(this.config.imApiToken ? { token: this.config.imApiToken } : {}),
        },
        body: JSON.stringify(body),
        signal: abort.signal,
      });
      if (response.ok) return;

      // 只保留有限长度的服务端错误以辅助运维；请求体中含 Token，绝不能记录请求体。
      const detail = (await response.text()).replace(/\s+/g, " ").slice(0, 200);
      this.logger.warn(`悟空 IM ${path} 返回 HTTP ${response.status}${detail ? `：${detail}` : ""}`);
      throw ApiErrors.upstream("悟空 IM 服务暂时不可用");
    } catch (error) {
      if (error instanceof Error && "getStatus" in error) throw error;
      const reason = error instanceof Error ? error.name : String(error);
      this.logger.warn(`调用悟空 IM ${path} 失败：${reason}`);
      throw ApiErrors.upstream("悟空 IM 服务暂时不可用");
    } finally {
      clearTimeout(timeout);
    }
  }
}
