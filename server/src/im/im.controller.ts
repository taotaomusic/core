import { Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Post, Query } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { SessionUser } from "../common/request.types";
import { ImService } from "./im.service";
import { ImChatRepository } from "./im-chat.repository";

const DEVICE_ID_PATTERN = /^[A-Za-z0-9._-]{16,128}$/;
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const CLIENT_MESSAGE_ID_PATTERN = /^[A-Za-z0-9._:-]{16,160}$/;

/** 聊天连接凭据、好友关系和可靠消息同步接口。 */
@Controller("im")
export class ImController {
  constructor(private readonly im: ImService, private readonly chat: ImChatRepository) {}

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

  /** 自己的聊天 UUID，仅返回给当前登录帐号，用于安全地交换好友标识。 */
  @Get("identity")
  @RateLimit("im-chat")
  identity(@CurrentUser() user: SessionUser | undefined) {
    return this.im.identity(this.requireUser(user).id);
  }

  /** 已接受的好友列表；客户端只能从这里选择私聊对象，不能任意填写 UUID。 */
  @Get("friends")
  @RateLimit("im-chat")
  listFriends(@CurrentUser() user: SessionUser | undefined) {
    return this.chat.listFriends(this.requireImUser(user).id);
  }

  @Get("friends/requests")
  @RateLimit("im-chat")
  listFriendRequests(@CurrentUser() user: SessionUser | undefined) {
    return this.chat.listRequests(this.requireImUser(user).id);
  }

  @Post("friends/requests")
  @RateLimit("im-chat")
  requestFriend(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>) {
    return this.chat.requestFriend(this.requireImUser(user).id, this.uidOf(body?.uid));
  }

  @Post("friends/:uid/accept")
  @RateLimit("im-chat")
  async acceptFriend(@CurrentUser() user: SessionUser | undefined, @Param("uid") uid: string) {
    if (!(await this.chat.acceptFriend(this.requireImUser(user).id, this.uidOf(uid)))) {
      throw ApiErrors.notFound(4042, "没有可接受的好友申请");
    }
    return { accepted: true };
  }

  /** 先入库再响应，离线好友在下次同步时必定可见。 */
  @Post("messages")
  @RateLimit("im-chat")
  sendMessage(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>) {
    const content = this.textOf(body?.content, 2000, "消息不能为空或过长");
    const clientMessageId = this.textOf(body?.clientMessageId, 160, "消息标识不正确");
    if (!CLIENT_MESSAGE_ID_PATTERN.test(clientMessageId)) throw ApiErrors.badRequest(4000, "消息标识不正确");
    return this.chat.send(this.requireImUser(user).id, this.uidOf(body?.peerUid), content, clientMessageId);
  }

  @Get("messages")
  @RateLimit("im-chat")
  async syncMessages(@CurrentUser() user: SessionUser | undefined, @Query("cursor") cursor?: string, @Query("limit") limit?: string) {
    const after = this.nonNegativeInt(cursor, 0);
    const pageSize = Math.min(200, Math.max(1, this.nonNegativeInt(limit, 100)));
    const messages = await this.chat.sync(this.requireImUser(user).id, after, pageSize);
    return { messages, cursor: messages.at(-1)?.cursor ?? after, hasMore: messages.length === pageSize };
  }

  private requireUser(user: SessionUser | undefined): SessionUser {
    if (!user) throw ApiErrors.unauthorized(4010, "请先登录");
    return user;
  }

  private requireImUser(user: SessionUser | undefined): SessionUser {
    this.im.ensureEnabled();
    return this.requireUser(user);
  }

  private deviceIdOf(value: unknown): string {
    const deviceId = typeof value === "string" ? value.trim() : "";
    if (!DEVICE_ID_PATTERN.test(deviceId)) {
      throw ApiErrors.badRequest(4000, "设备标识格式不正确");
    }
    return deviceId;
  }

  private uidOf(value: unknown): string {
    const uid = typeof value === "string" ? value.trim().toLowerCase() : "";
    if (!UUID_PATTERN.test(uid)) throw ApiErrors.badRequest(4000, "聊天用户 ID 不正确");
    return uid;
  }

  private textOf(value: unknown, maxLength: number, message: string): string {
    const text = typeof value === "string" ? value.trim() : "";
    if (!text || text.length > maxLength) throw ApiErrors.badRequest(4000, message);
    return text;
  }

  private nonNegativeInt(value: string | undefined, fallback: number): number {
    const parsed = Number(value);
    return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : fallback;
  }
}
