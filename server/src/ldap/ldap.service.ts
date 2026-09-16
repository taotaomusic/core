import { Injectable, Logger } from "@nestjs/common";
import { AppConfigService } from "../config/app-config.service";
import { AdminUsersRepository, type AdminCredentials } from "../admin-auth/admin-users.repository";
import { AdminAuthService } from "../admin-auth/admin-auth.service";
import * as net from "node:net";
import * as tls from "node:tls";

/**
 * LDAP 操作结果。
 */
interface LdapSearchResult {
    dn: string;
    attributes: Record<string, string[]>;
}

/**
 * LDAP 服务。
 *
 * 使用 Node.js 原生 net/tls 模块实现 LDAP 协议交互。
 * 负责：
 * - 连接 LDAP 服务器并验证用户凭据
 * - 搜索用户并获取组信息
 * - 根据 LDAP 组映射到 admin role
 * - 如果 LDAP 用户在 admin_users 表中不存在，自动创建
 */
@Injectable()
export class LdapService {
    private readonly logger = new Logger(LdapService.name);

    constructor(
        private readonly config: AppConfigService,
        private readonly adminUsers: AdminUsersRepository,
        private readonly adminAuth: AdminAuthService,
    ) {}

    /**
     * 用 LDAP 验证用户凭据并同步到本地 admin_users 表。
     * 返回本地 admin 用户记录。
     */
    async authenticate(username: string, password: string): Promise<{
        id: number;
        username: string;
        display_name: string;
        role: string;
    } | null> {
        if (!this.config.isLdapConfigured) return null;

        let client: LdapClient | null = null;
        try {
            // 1. 创建 LDAP 客户端连接
            client = await this.createClient();

            // 2. 绑定服务账号
            await client.bind(this.config.ldapBindDn, this.config.ldapBindPassword);

            // 3. 搜索用户
            const userDn = await this.searchUser(client, username);
            if (!userDn) {
                this.logger.warn(`LDAP 用户不存在: ${username}`);
                return null;
            }

            // 4. 用用户凭据绑定（验证密码）
            await client.bind(userDn, password);

            // 5. 获取用户组信息
            const groups = await this.getUserGroups(client, userDn);

            // 6. 映射 role
            const role = this.mapRole(groups);

            // 7. 同步到本地 admin_users
            const localUser = await this.syncToLocal(username, userDn, role);

            this.logger.log(`LDAP 认证成功: ${username} -> role=${role}`);
            return localUser;
        } catch (error) {
            this.logger.warn(`LDAP 认证失败: ${username} - ${(error as Error).message}`);
            return null;
        } finally {
            client?.destroy();
        }
    }

    /**
     * 创建 LDAP 客户端连接。
     * 根据配置使用 TLS 或普通 TCP。
     */
    private createClient(): Promise<LdapClient> {
        return new Promise((resolve, reject) => {
            const url = new URL(this.config.ldapUrl);
            const host = url.hostname;
            const port = Number(url.port) || (url.protocol === "ldaps:" ? 636 : 389);
            const useTls = url.protocol === "ldaps:";

            const options: tls.ConnectionOptions = {
                host,
                port,
                rejectUnauthorized: false, // 内网环境通常不验证证书
            };

            const connect = useTls ? tls.connect(options) : net.connect({ host, port });

            const client = new LdapClient(connect);
            const timeout = setTimeout(() => {
                connect.destroy();
                reject(new Error(`LDAP 连接超时: ${host}:${port}`));
            }, 10_000);

            connect.once("connect", () => {
                clearTimeout(timeout);
                resolve(client);
            });

            connect.once("error", (err) => {
                clearTimeout(timeout);
                reject(new Error(`LDAP 连接失败: ${err.message}`));
            });
        });
    }

    /**
     * 搜索用户并返回 DN。
     */
    private async searchUser(client: LdapClient, username: string): Promise<string | null> {
        const filter = this.config.ldapUserSearchFilter.replace(/\{\{username\}\}/g, username);
        const results = await client.search(this.config.ldapUserSearchBase, filter);
        return results.length > 0 ? results[0].dn : null;
    }

    /**
     * 获取用户所属的组 DN 列表。
     */
    private async getUserGroups(client: LdapClient, userDn: string): Promise<string[]> {
        if (!this.config.ldapGroupSearchBase || !this.config.ldapGroupSearchFilter) {
            return [];
        }
        const filter = this.config.ldapGroupSearchFilter.replace(/\{\{userDn\}\}/g, this.escapeLdapDn(userDn));
        const results = await client.search(this.config.ldapGroupSearchBase, filter);
        return results.map((r) => r.dn);
    }

    /**
     * 根据 LDAP 组映射到 admin role。
     */
    private mapRole(groups: string[]): string {
        const mapping = this.config.ldapRoleMapping;
        for (const [groupDn, role] of Object.entries(mapping)) {
            if (groups.includes(groupDn)) {
                return role;
            }
        }
        return "viewer"; // 默认角色
    }

    /**
     * 转义 LDAP DN 中的特殊字符。
     */
    private escapeLdapDn(dn: string): string {
        return dn.replace(/[,+="<>#;\\]/g, (char) => `\\${char}`);
    }

    /**
     * 将 LDAP 用户同步到本地 admin_users 表。
     */
    private async syncToLocal(
        username: string,
        _userDn: string,
        role: string,
    ): Promise<{ id: number; username: string; display_name: string; role: string }> {
        const existing = await this.adminUsers.findByUsername(username);
        if (existing) {
            // 用户已存在，如果角色发生变化则更新
            if (existing.role !== role) {
                await this.adminUsers.setRole(existing.id, role);
                this.logger.log(`LDAP 用户角色已更新: ${username} ${existing.role} -> ${role}`);
            }
            return {
                id: existing.id,
                username: existing.username,
                display_name: existing.display_name,
                role,
            };
        }

        // 创建本地记录（LDAP 用户不需要本地密码，用随机哈希占位）
        const placeholder = this.adminAuth.hashPassword(`ldap_${Date.now()}_${username}`);
        const created = await this.adminUsers.create(
            username,
            placeholder.hash,
            placeholder.salt,
            username, // display_name 默认用 username
            role,
            null, // created_by
        );

        this.logger.log(`LDAP 用户已同步到本地: ${username} (role=${role})`);
        return {
            id: created.id,
            username: created.username,
            display_name: created.display_name,
            role: created.role,
        };
    }
}

/**
 * 简化的 LDAP 客户端。
 * 实现 LDAP 协议的 Bind 和 Search 操作。
 */
class LdapClient {
    private readonly socket: net.Socket | tls.TLSSocket;
    private messageId = 0;
    private readonly pending = new Map<number, {
        resolve: (value: any) => void;
        reject: (reason: any) => void;
    }>();
    private buffer = Buffer.alloc(0);

    constructor(socket: net.Socket | tls.TLSSocket) {
        this.socket = socket;
        this.socket.on("data", (chunk) => this.onData(chunk));
    }

    /**
     * LDAP Bind 操作。
     */
    bind(dn: string, password: string): Promise<void> {
        return new Promise((resolve, reject) => {
            const id = ++this.messageId;
            const request = this.encodeBindRequest(id, dn, password);
            this.pending.set(id, {
                resolve: () => resolve(),
                reject,
            });
            this.socket.write(request);
        });
    }

    /**
     * LDAP Search 操作。
     */
    search(base: string, filter: string): Promise<LdapSearchResult[]> {
        return new Promise((resolve, reject) => {
            const id = ++this.messageId;
            const request = this.encodeSearchRequest(id, base, filter);
            const results: LdapSearchResult[] = [];
            this.pending.set(id, {
                resolve: (entries: LdapSearchResult[]) => {
                    results.push(...entries);
                    resolve(results);
                },
                reject,
            });
            this.socket.write(request);
        });
    }

    /**
     * 关闭连接。
     */
    destroy(): void {
        this.socket.destroy();
        for (const [id, handler] of this.pending) {
            handler.reject(new Error("连接已关闭"));
            this.pending.delete(id);
        }
    }

    /**
     * 处理接收到的数据。
     */
    private onData(chunk: Buffer): void {
        this.buffer = Buffer.concat([this.buffer, chunk]);

        // 尝试解析 LDAP 消息
        while (this.buffer.length > 0) {
            try {
                const { message, bytesRead } = this.parseLdapMessage(this.buffer);
                if (bytesRead === 0) break;

                this.buffer = this.buffer.subarray(bytesRead);
                this.handleMessage(message);
            } catch {
                // 数据不完整，等待更多数据
                break;
            }
        }
    }

    /**
     * 处理 LDAP 消息。
     */
    private handleMessage(message: any): void {
        const id = message.messageId;
        const handler = this.pending.get(id);
        if (!handler) return;

        if (message.protocolOp === 1) {
            // Bind Response
            if (message.resultCode === 0) {
                handler.resolve(null);
            } else {
                handler.reject(new Error(`LDAP 操作失败: ${message.diagnosticMessage || "未知错误"}`));
            }
            this.pending.delete(id);
        } else if (message.protocolOp === 4) {
            // Search Result Entry
            if (message.entries) {
                // 临时存储条目，等收到 Search Result Done 后一起返回
                if (!this.pending.has(id)) {
                    this.pending.set(id, handler);
                }
                // 将条目附加到处理函数上
                (handler as any)._entries = (handler as any)._entries || [];
                (handler as any)._entries.push(message.entries);
            }
        } else if (message.protocolOp === 5) {
            // Search Result Done
            const entries = (handler as any)._entries || [];
            handler.resolve(entries);
            this.pending.delete(id);
        }
    }

    /**
     * 编码 LDAP Bind Request。
     */
    private encodeBindRequest(id: number, dn: string, password: string): Buffer {
        // 简化的 ASN.1 BER 编码
        const dnBuffer = Buffer.from(dn, "utf-8");
        const passwordBuffer = Buffer.from(password, "utf-8");

        // 认证字段
        const authBuffer = Buffer.concat([
            Buffer.from([0x04]), // Octet String tag
            this.encodeLength(passwordBuffer.length),
            passwordBuffer,
        ]);

        // Bind Request 体
        const bodyBuffer = Buffer.concat([
            Buffer.from([0x02]), // Integer tag (message ID)
            this.encodeLength(this.encodeInteger(id).length),
            this.encodeInteger(id),
            Buffer.from([0x30]), // Sequence tag (Bind Request)
            this.encodeLength(
                this.encodeInteger(3).length + // version
                this.encodeLength(dnBuffer.length).length + 1 + dnBuffer.length +
                authBuffer.length
            ),
            Buffer.from([0x02]), // Integer tag (version)
            this.encodeLength(this.encodeInteger(3).length),
            this.encodeInteger(3),
            Buffer.from([0x04]), // Octet String tag (DN)
            this.encodeLength(dnBuffer.length),
            dnBuffer,
            authBuffer,
        ]);

        // LDAP Message
        return Buffer.concat([
            Buffer.from([0x30]), // Sequence tag
            this.encodeLength(bodyBuffer.length),
            bodyBuffer,
        ]);
    }

    /**
     * 编码 LDAP Search Request。
     */
    private encodeSearchRequest(id: number, base: string, filter: string): Buffer {
        const baseBuffer = Buffer.from(base, "utf-8");
        const filterBuffer = this.encodeFilter(filter);

        // Search Request 体
        const bodyBuffer = Buffer.concat([
            Buffer.from([0x02]), // Integer tag (message ID)
            this.encodeLength(this.encodeInteger(id).length),
            this.encodeInteger(id),
            Buffer.from([0x63]), // Application 3 (Search Request)
            this.encodeLength(
                this.encodeLength(baseBuffer.length).length + 1 + baseBuffer.length +
                this.encodeInteger(0).length + 2 + // scope (wholeSubtree)
                this.encodeInteger(0).length + 2 + // derefAliases
                this.encodeInteger(0).length + 2 + // sizeLimit
                this.encodeInteger(0).length + 2 + // timeLimit
                this.encodeInteger(0).length + 2 + // typesOnly
                filterBuffer.length +
                this.encodeLength(0).length + 1 // attributes (empty)
            ),
            Buffer.from([0x04]), // Octet String tag (base DN)
            this.encodeLength(baseBuffer.length),
            baseBuffer,
            Buffer.from([0x0a]), // Enum tag (scope)
            this.encodeLength(this.encodeInteger(0).length),
            this.encodeInteger(0),
            Buffer.from([0x0a]), // Enum tag (derefAliases)
            this.encodeLength(this.encodeInteger(0).length),
            this.encodeInteger(0),
            Buffer.from([0x02]), // Integer tag (sizeLimit)
            this.encodeLength(this.encodeInteger(0).length),
            this.encodeInteger(0),
            Buffer.from([0x02]), // Integer tag (timeLimit)
            this.encodeLength(this.encodeInteger(0).length),
            this.encodeInteger(0),
            Buffer.from([0x01]), // Boolean tag (typesOnly)
            this.encodeLength(1),
            Buffer.from([0x00]),
            filterBuffer,
            Buffer.from([0x30]), // Sequence tag (attributes)
            this.encodeLength(0),
        ]);

        // LDAP Message
        return Buffer.concat([
            Buffer.from([0x30]), // Sequence tag
            this.encodeLength(bodyBuffer.length),
            bodyBuffer,
        ]);
    }

    /**
     * 编码 LDAP 过滤器。
     */
    private encodeFilter(filter: string): Buffer {
        // 简化的过滤器编码
        const filterBuffer = Buffer.from(filter, "utf-8");
        return Buffer.concat([
            Buffer.from([0x87]), // Context tag 7 (present filter)
            this.encodeLength(filterBuffer.length),
            filterBuffer,
        ]);
    }

    /**
     * 编码 BER 长度。
     */
    private encodeLength(length: number): Buffer {
        if (length < 0x80) {
            return Buffer.from([length]);
        }
        const bytes: number[] = [];
        let temp = length;
        while (temp > 0) {
            bytes.unshift(temp & 0xff);
            temp >>= 8;
        }
        return Buffer.from([0x80 | bytes.length, ...bytes]);
    }

    /**
     * 编码整数为 BER 格式。
     */
    private encodeInteger(value: number): Buffer {
        if (value === 0) return Buffer.from([0x00]);
        const bytes: number[] = [];
        let temp = value;
        while (temp > 0) {
            bytes.unshift(temp & 0xff);
            temp >>= 8;
        }
        return Buffer.from(bytes);
    }

    /**
     * 解析 LDAP 消息。
     */
    private parseLdapMessage(buffer: Buffer): { message: any; bytesRead: number } {
        // 简化的 BER 解析
        if (buffer.length < 2) {
            return { message: null, bytesRead: 0 };
        }

        if (buffer[0] !== 0x30) {
            throw new Error("无效的 LDAP 消息");
        }

        const { length, bytesRead: lengthBytes } = this.parseLength(buffer.subarray(1));
        const totalLength = lengthBytes + 1 + length;

        if (buffer.length < totalLength) {
            return { message: null, bytesRead: 0 };
        }

        // 解析消息 ID
        let offset = lengthBytes + 1;
        const messageId = this.parseInt(buffer.subarray(offset));
        offset += messageId.bytesRead;

        // 解析协议操作
        const protocolOpTag = buffer[offset];
        offset += 1;

        const { length: opLength, bytesRead: opLengthBytes } = this.parseLength(buffer.subarray(offset));
        offset += opLengthBytes;

        let result: any = { messageId, protocolOp: 0 };

        if (protocolOpTag === 0x61) {
            // Bind Response
            result.protocolOp = 1;
            const { value: resultCode, bytesRead: rcBytes } = this.parseInt(buffer.subarray(offset));
            offset += rcBytes;
            result.resultCode = resultCode;

            // 跳过 matchedDN 和 diagnosticMessage
            offset += 4 + this.parseLength(buffer.subarray(offset + 1)).length + 1;
            const diagLen = this.parseLength(buffer.subarray(offset)).length;
            offset += diagLen;
        } else if (protocolOpTag === 0x64) {
            // Search Result Entry
            result.protocolOp = 4;
            result.entries = {};

            // 解析 DN
            const { value: dn, bytesRead: dnBytes } = this.parseOctetString(buffer.subarray(offset));
            result.dn = dn;
            offset += dnBytes;
        } else if (protocolOpTag === 0x65) {
            // Search Result Done
            result.protocolOp = 5;
        }

        return { message: result, bytesRead: totalLength };
    }

    /**
     * 解析 BER 长度。
     */
    private parseLength(buffer: Buffer): { length: number; bytesRead: number } {
        if (buffer.length === 0) {
            return { length: 0, bytesRead: 0 };
        }

        const firstByte = buffer[0];
        if (firstByte < 0x80) {
            return { length: firstByte, bytesRead: 1 };
        }

        const numBytes = firstByte & 0x7f;
        let length = 0;
        for (let i = 0; i < numBytes; i++) {
            length = (length << 8) | buffer[i + 1];
        }
        return { length, bytesRead: numBytes + 1 };
    }

    /**
     * 解析 BER 整数。
     */
    private parseInt(buffer: Buffer): { value: number; bytesRead: number } {
        const { length, bytesRead } = this.parseLength(buffer);
        let value = 0;
        for (let i = bytesRead; i < bytesRead + length; i++) {
            value = (value << 8) | buffer[i];
        }
        return { value, bytesRead: bytesRead + length };
    }

    /**
     * 解析 BER Octet String。
     */
    private parseOctetString(buffer: Buffer): { value: string; bytesRead: number } {
        const { length, bytesRead } = this.parseLength(buffer);
        const value = buffer.subarray(bytesRead, bytesRead + length).toString("utf-8");
        return { value, bytesRead: bytesRead + length };
    }
}
