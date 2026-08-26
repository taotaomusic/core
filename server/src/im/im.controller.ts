import { Body, Controller, Delete, HttpCode, HttpStatus, Post } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { SessionUser } from "../common/request.types";
import { ImService } from "./im.service";

const DEVICE_ID_PATTERN = /^[A-Za-z0-9._-]{16,128}$/;

/** 聊天连接凭据接口；聊天消息本身不经过 NestJS。 */
@Controller("im")
export class ImController {
  constructor(private readonly im: ImService) {}

  @Post("session")
  @RateLimit("im-session")
  async createSession(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>) {
    return this.im.createAndroidSession(this.requireUser(user).id, this.deviceIdOf(body?.deviceId));
  }

  @Delete("session")
  @RateLimit("im-session")
  @HttpCode(HttpStatus.NO_CONTENT)
  async revokeSession(@CurrentUser() user: SessionUser | undefined): Promise<void> {
    await this.im.revokeAndroidSession(this.requireUser(user).id);
  }

  private requireUser(user: SessionUser | undefined): SessionUser {
    if (!user) throw ApiErrors.unauthorized(4010, "请先登录");
    return user;
  }

  private deviceIdOf(value: unknown): string {
    const deviceId = typeof value === "string" ? value.trim() : "";
    if (!DEVICE_ID_PATTERN.test(deviceId)) {
      throw ApiErrors.badRequest(4000, "设备标识格式不正确");
    }
    return deviceId;
  }
}
