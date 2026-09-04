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

  /** 仅由已认证业务用户调用的悟空会话同步代理，不能把 5001 暴露给客户端。 */
  syncConversations(uid: string, lastMessageSeqs: string, messageCount: number, version: number): Promise<unknown> {
    // 登录只恢复最近 50 个会话；更早历史由频道消息的按页同步按需取得，避免重连放大。
    return this.postJson("/conversation/sync", {
      uid,
      last_msg_seqs: lastMessageSeqs,
      msg_count: messageCount,
      version,
      page: 1,
      page_size: 50,
    });
  }

  syncChannelMessages(input: { uid: string; channelId: string; channelType: number; startMessageSeq: number; endMessageSeq: number; limit: number; pullMode: number }): Promise<unknown> {
    return this.postJson("/channel/messagesync", {
      login_uid: input.uid, channel_id: input.channelId, channel_type: input.channelType,
      start_message_seq: input.startMessageSeq, end_message_seq: input.endMessageSeq, limit: input.limit, pull_mode: input.pullMode,
    });
  }

  async revokeMessage(input: { uid: string; channelId: string; messageId: string; clientMsgNo: string }): Promise<void> {
    // 不再查询消息进行权限校验，因为：
    // 1. 客户端已经在 UI 层校验了 isMine
    // 2. 悟空的 /message 接口可能返回 404（本地消息、已删除消息等）
    // 3. 撤回命令本身会被悟空服务端和 SDK 再次校验
    // v2.2.5 没有 /message/revoke HTTP 路由。将撤回作为悟空内部命令发给双方，
    // 由 Android SDK 的 WKCMDKeys.wk_messageRevoke 回调更新本地消息视图。
    const payload = Buffer.from(JSON.stringify({
      type: 99,
      cmd: "messageRevoke",
      param: {
        message_id: input.messageId,
        client_msg_no: input.clientMsgNo,
        channel_id: input.channelId,
        channel_type: 1,
      },
    })).toString("base64");
    await this.post("/message/send", {
      from_uid: input.uid,
      channel_id: input.channelId,
      channel_type: 1,
      // 撤回命令也需离线可恢复；SDK 会将 type=99 交给 CMDManager，不会显示为普通消息。
      // 当前 Android SDK 使用 READ 会话同步；命令留在原频道才能随会话历史恢复。
      header: { no_persist: 0, red_dot: 0, sync_once: 0 },
      payload,
    });
  }

  async clearUnread(uid: string, channelId: string): Promise<void> {
    await this.post("/conversations/clearUnread", { uid, channel_id: channelId, channel_type: 1 });
  }

  private async post(path: string, body: Record<string, unknown>): Promise<void> {
    await this.request("POST", path, body);
  }

  private async postJson(path: string, body: Record<string, unknown>): Promise<unknown> {
    return this.request("POST", path, body);
  }

  private async request(method: "POST" | "PUT", path: string, body: Record<string, unknown>): Promise<unknown> {
    const abort = new AbortController();
    const timeout = setTimeout(() => abort.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await fetch(`${this.config.imInternalApiBaseUrl}${path}`, {
        method,
        headers: {
          "content-type": "application/json",
          ...(this.config.imApiToken ? { token: this.config.imApiToken } : {}),
        },
        body: JSON.stringify(body),
        signal: abort.signal,
      });
      if (response.ok) return response.status === 204 ? undefined : response.json();

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
