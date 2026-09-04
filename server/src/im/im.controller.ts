import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Post, Query } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { SessionUser } from "../common/request.types";
import { ImService } from "./im.service";

const DEVICE_ID_PATTERN = /^[A-Za-z0-9._-]{16,128}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

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

  /** SDK 登录后的会话同步；服务端从登录态取 UID，客户端不能伪造其他帐号。 */
  @Post("sync/conversations")
  @RateLimit("im-sync")
  syncConversations(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>) {
    return this.im.syncConversations(this.requireUser(user).id, {
      lastMessageSeqs: typeof body.lastMessageSeqs === "string" ? body.lastMessageSeqs.slice(0, 20_000) : "",
      messageCount: this.numberOf(body.messageCount, 10, 1, 20),
      version: this.numberOf(body.version, 0, 0, Number.MAX_SAFE_INTEGER),
    });
  }

  @Post("sync/channel-messages")
  @RateLimit("im-sync")
  syncChannelMessages(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>) {
    const channelId = typeof body.channelId === "string" ? body.channelId.trim().toLowerCase() : "";
    if (!UUID_PATTERN.test(channelId)) throw ApiErrors.badRequest(4000, "聊天频道标识不正确");
    return this.im.syncChannelMessages(this.requireUser(user).id, {
      channelId, channelType: 1,
      startMessageSeq: this.numberOf(body.startMessageSeq, 0, 0, Number.MAX_SAFE_INTEGER),
      endMessageSeq: this.numberOf(body.endMessageSeq, 0, 0, Number.MAX_SAFE_INTEGER),
      limit: this.numberOf(body.limit, 50, 1, 50), pullMode: this.numberOf(body.pullMode, 0, 0, 1),
    });
  }

  /** 撤回通过悟空内部命令发送；客户端只能携带当前登录用户的会话。 */
  @Post("messages/revoke")
  @RateLimit("im-sync")
  async revokeMessage(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>): Promise<void> {
    const channelId = typeof body.channelId === "string" ? body.channelId.trim().toLowerCase() : "";
    const messageId = typeof body.messageId === "string" ? body.messageId.trim() : "";
    const clientMsgNo = typeof body.clientMsgNo === "string" ? body.clientMsgNo.trim() : "";
    if (!UUID_PATTERN.test(channelId) || !messageId || !clientMsgNo) throw ApiErrors.badRequest(4000, "撤回消息参数不正确");
    await this.im.revokeMessage(this.requireUser(user).id, { channelId, messageId: messageId.slice(0, 128), clientMsgNo: clientMsgNo.slice(0, 256) });
  }

  @Get("contacts")
  @RateLimit("im-sync")
  contacts(@CurrentUser() user: SessionUser | undefined, @Query("uids") rawUids?: string) {
    const uids = (rawUids ?? "").split(",").map((uid) => uid.trim().toLowerCase()).filter((uid, index, all) => UUID_PATTERN.test(uid) && all.indexOf(uid) === index).slice(0, 50);
    return this.im.contacts(this.requireUser(user).id, uids);
  }

  @Post("conversations/read")
  @RateLimit("im-sync")
  async markConversationRead(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>): Promise<void> {
    const channelId = typeof body.channelId === "string" ? body.channelId.trim().toLowerCase() : "";
    if (!UUID_PATTERN.test(channelId)) throw ApiErrors.badRequest(4000, "聊天频道标识不正确");
    await this.im.markConversationRead(this.requireUser(user).id, channelId);
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

  private numberOf(value: unknown, fallback: number, min: number, max: number): number {
    const parsed = typeof value === "number" ? value : Number(value);
    return Number.isSafeInteger(parsed) ? Math.min(max, Math.max(min, parsed)) : fallback;
  }
}
