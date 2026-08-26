import { Injectable } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { ApiErrors } from "../common/api.exception";
import { DatabaseService } from "../database/database.service";

export type ImFriend = { uid: string; username: string; nickname: string; avatarUrl: string | null; since: number };
export type ImFriendRequest = ImFriend & { direction: "incoming" | "outgoing"; requestedAt: number };
export type ImMessage = {
  cursor: number; id: string; senderUid: string; recipientUid: string; content: string; createdAt: number;
};

const FRIEND_COLUMNS = `other.im_uid AS uid, other.username, COALESCE(other.nickname, other.username) AS nickname,
  other.avatar_url AS "avatarUrl"`;

/** 好友关系和离线消息的持久化仓库。消息绝不以 Gateway 是否在线作为成功条件。 */
@Injectable()
export class ImChatRepository {
  constructor(private readonly database: DatabaseService) {}

  async listFriends(userId: number): Promise<ImFriend[]> {
    return this.database.all<ImFriend>(
      `SELECT ${FRIEND_COLUMNS}, relation.created_at AS since
       FROM im_friendship relation
       JOIN users other ON other.id = CASE WHEN relation.user_low_id = $1 THEN relation.user_high_id ELSE relation.user_low_id END
       WHERE (relation.user_low_id = $1 OR relation.user_high_id = $1) AND relation.status = 'accepted' AND other.disabled_at IS NULL
       ORDER BY COALESCE(other.nickname, other.username), other.im_uid`, [userId]);
  }

  async listRequests(userId: number): Promise<ImFriendRequest[]> {
    const rows = await this.database.all<ImFriendRequest>(
      `SELECT ${FRIEND_COLUMNS}, relation.created_at AS "requestedAt",
       CASE WHEN relation.requester_user_id = $1 THEN 'outgoing' ELSE 'incoming' END AS direction
       FROM im_friendship relation
       JOIN users other ON other.id = CASE WHEN relation.user_low_id = $1 THEN relation.user_high_id ELSE relation.user_low_id END
       WHERE (relation.user_low_id = $1 OR relation.user_high_id = $1) AND relation.status = 'pending' AND other.disabled_at IS NULL
       ORDER BY relation.created_at DESC`, [userId]);
    return rows;
  }

  async requestFriend(userId: number, targetUid: string): Promise<{ status: "pending" | "accepted" }> {
    const target = await this.userIdOfUid(targetUid);
    if (!target) throw ApiErrors.notFound(4042, "未找到该聊天用户");
    if (target === userId) throw ApiErrors.badRequest(4000, "不能添加自己为好友");
    const low = Math.min(userId, target); const high = Math.max(userId, target); const now = Date.now();
    const row = await this.database.first<{ status: "pending" | "accepted" }>(
      `INSERT INTO im_friendship (user_low_id, user_high_id, requester_user_id, status, created_at, updated_at)
       VALUES ($1, $2, $3, 'pending', $4, $4)
       ON CONFLICT (user_low_id, user_high_id) DO UPDATE SET
         status = CASE WHEN im_friendship.status = 'pending' AND im_friendship.requester_user_id <> excluded.requester_user_id THEN 'accepted' ELSE im_friendship.status END,
         updated_at = CASE WHEN im_friendship.status = 'pending' AND im_friendship.requester_user_id <> excluded.requester_user_id THEN excluded.updated_at ELSE im_friendship.updated_at END
       RETURNING status`, [low, high, userId, now]);
    return row!;
  }

  async acceptFriend(userId: number, targetUid: string): Promise<boolean> {
    const target = await this.userIdOfUid(targetUid); if (!target) return false;
    return (await this.database.run(
      `UPDATE im_friendship SET status = 'accepted', updated_at = $3
       WHERE user_low_id = $1 AND user_high_id = $2 AND status = 'pending' AND requester_user_id <> $4`,
      [Math.min(userId, target), Math.max(userId, target), Date.now(), userId])) === 1;
  }

  async send(userId: number, peerUid: string, content: string, clientMessageId: string): Promise<ImMessage> {
    const target = await this.userIdOfUid(peerUid); if (!target) throw ApiErrors.notFound(4042, "未找到该聊天用户");
    const low = Math.min(userId, target); const high = Math.max(userId, target); const now = Date.now();
    const row = await this.database.first<ImMessage>(
      `INSERT INTO im_chat_message (message_id, sender_user_id, recipient_user_id, client_message_id, content, created_at)
       SELECT $1, $2, $3, $4, $5, $6
       WHERE EXISTS (SELECT 1 FROM im_friendship WHERE user_low_id = $7 AND user_high_id = $8 AND status = 'accepted')
       ON CONFLICT (sender_user_id, client_message_id) DO UPDATE SET client_message_id = EXCLUDED.client_message_id
       RETURNING cursor_id AS cursor, message_id AS id,
         (SELECT im_uid FROM users WHERE id = sender_user_id) AS "senderUid",
         (SELECT im_uid FROM users WHERE id = recipient_user_id) AS "recipientUid", content, created_at AS "createdAt"`,
      [randomUUID(), userId, target, clientMessageId, content, now, low, high]);
    if (!row) throw ApiErrors.conflict(4095, "仅能向已添加的好友发送消息");
    return row;
  }

  sync(userId: number, cursor: number, limit: number): Promise<ImMessage[]> {
    return this.database.all<ImMessage>(
      `SELECT message.cursor_id AS cursor, message.message_id AS id, sender.im_uid AS "senderUid", recipient.im_uid AS "recipientUid",
         message.content, message.created_at AS "createdAt"
       FROM im_chat_message message JOIN users sender ON sender.id = message.sender_user_id JOIN users recipient ON recipient.id = message.recipient_user_id
       WHERE (message.sender_user_id = $1 OR message.recipient_user_id = $1) AND message.cursor_id > $2
       ORDER BY message.cursor_id ASC LIMIT $3`, [userId, cursor, limit]);
  }

  private async userIdOfUid(uid: string): Promise<number | undefined> {
    return (await this.database.first<{ id: number }>(`SELECT id FROM users WHERE im_uid = $1 AND disabled_at IS NULL`, [uid]))?.id;
  }
}
