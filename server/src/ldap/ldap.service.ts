import { Injectable, Logger } from "@nestjs/common";
import { randomBytes } from "node:crypto";
import * as net from "node:net";
import * as tls from "node:tls";
import { AppConfigService } from "../config/app-config.service";
import { AdminUsersRepository } from "../admin-auth/admin-users.repository";
import { AdminAuthService } from "../admin-auth/admin-auth.service";
import type { AdminActor } from "../common/request.types";

/**
 * LDAP 认证结果。
 *
 * 四种结果的差别是**要不要回落到本地密码校验**，所以不能简化成
 * 「成功/失败」两态：
 *
 * - `success`：目录已确认身份，直接用这个本地账号签发会话
 * - `denied`：目录明确拒绝（口令错，或本地已禁用），**不再回落**
 * - `skipped`：未配置、目录不可达、或目录里没这个人 —— 交给本地密码校验。
 *   这条路径是配置写错/目录挂掉时的 break-glass 通道
 * - `unavailable`：**目录已经用用户 DN 绑定成功**，但之后的取组/角色映射/
 *   同步写库失败。身份已经确认过了，再回落本地口令等于让「目录判定已停用」
 *   的账号靠残留的本地哈希登进来，因此这里**禁止回落**。
 */
export type LdapAuthResult =
  | { outcome: "success"; admin: AdminActor }
  | { outcome: "denied"; message: string }
  | { outcome: "skipped"; message: string }
  | { outcome: "unavailable"; message: string };

/** 目录条目：DN 加上请求到的属性。 */
export interface LdapEntry {
  dn: string;
  attributes: Record<string, string[]>;
}

/** 只取 DN、不要属性时用的 OID（RFC 4511 §4.5.1.8 的 "1.1"）。 */
const NO_ATTRIBUTES = ["1.1"];

/**
 * LDAP 服务。
 *
 * 用 Node 原生 net/tls 手写 LDAP 协议（不引第三方库），负责：
 * - 校验用户凭据：服务账号绑定 → 搜用户 → 用用户 DN 再绑定
 * - 取用户所属组并按 [AppConfigService.ldapRoleMapping] 映射到 admin 角色
 * - 把目录用户同步到本地 `admin_users`，供审计与角色判断使用
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
   * 用 LDAP 校验凭据并同步到本地 `admin_users`。
   *
   * 「目录不可达」的异常在这里收敛成 `skipped`（允许 break-glass 回落），
   * 但**用户 DN 绑定成功之后**的失败一律收敛成 `unavailable` 并禁止回落 ——
   * 详见 [LdapAuthResult]。
   */
  async authenticate(username: string, password: string): Promise<LdapAuthResult> {
    if (!this.config.isLdapConfigured) {
      return { outcome: "skipped", message: "未配置 LDAP" };
    }
    if (!username || !password) {
      return { outcome: "skipped", message: "缺少凭据" };
    }

    const timeout = this.config.ldapTimeoutMs;
    let client: LdapClient | null = null;
    try {
      client = await this.createClient();

      // 服务账号绑定：失败属于配置问题，不是用户凭据问题。
      try {
        await client.bind(this.config.ldapBindDn, this.config.ldapBindPassword, timeout);
      } catch (error) {
        this.logger.warn(`LDAP 服务账号绑定失败，本次登录回落本地校验：${(error as Error).message}`);
        return { outcome: "skipped", message: "目录不可用" };
      }

      const userDn = await this.searchUser(client, username);
      if (!userDn) {
        this.logger.log(`LDAP 目录中不存在用户 ${username}，本次登录回落本地校验`);
        return { outcome: "skipped", message: "目录中不存在该用户" };
      }

      // 用用户自己的 DN 再绑定一次，这才是真正的口令校验。
      try {
        await client.bind(userDn, password, timeout);
      } catch {
        return { outcome: "denied", message: "目录口令校验失败" };
      }

      // 口令已在目录侧验证通过。**从这里开始绝不能再回落到本地口令**：
      // 目录已经确认了身份，后面这些步骤失败属于服务端故障，不是「目录不可达」。
      // 旧实现把它们一起兜进外层 catch 变成 skipped，等于让「目录已判定停用」
      // 的账号在目录抖动时靠残留的本地哈希登进后台。
      try {
        const groups = await this.getUserGroups(client, userDn);
        const role = this.mapRole(groups);
        const synced = await this.syncToLocal(username, role);
        if (!synced) {
          // 本地已禁用：目录放行也不算数，否则禁用等于没禁。
          return { outcome: "denied", message: "该账号已被禁用" };
        }

        this.logger.log(`LDAP 认证成功：${username} -> role=${role}`);
        return { outcome: "success", admin: synced };
      } catch (error) {
        this.logger.error(
          `LDAP 已确认 ${username} 的身份，但后续取组或同步失败，本次不回落本地校验：${(error as Error).message}`,
        );
        return { outcome: "unavailable", message: "目录已确认身份但同步失败" };
      }
    } catch (error) {
      this.logger.warn(`LDAP 认证异常，本次登录回落本地校验：${(error as Error).message}`);
      return { outcome: "skipped", message: "目录不可用" };
    } finally {
      client?.destroy();
    }
  }

  /**
   * 建立连接。
   *
   * LDAPS 走 `secureConnect` 而不是 `connect` —— 后者在 TLS 握手完成前就触发，
   * 之后写入的绑定请求会撞在还没就绪的会话上。
   */
  private createClient(): Promise<LdapClient> {
    return new Promise((resolve, reject) => {
      const url = new URL(this.config.ldapUrl);
      const host = url.hostname;
      const port = Number(url.port) || (url.protocol === "ldaps:" ? 636 : 389);
      const useTls = url.protocol === "ldaps:";
      const timeout = this.config.ldapTimeoutMs;

      const socket = useTls
        ? tls.connect({
            host,
            port,
            servername: host,
            rejectUnauthorized: this.config.ldapTlsRejectUnauthorized,
          })
        : net.connect({ host, port });

      const client = new LdapClient(socket, timeout);
      const timer = setTimeout(() => {
        socket.destroy();
        reject(new Error(`LDAP 连接超时：${host}:${port}`));
      }, timeout);

      socket.once(useTls ? "secureConnect" : "connect", () => {
        clearTimeout(timer);
        resolve(client);
      });
      socket.once("error", (error) => {
        clearTimeout(timer);
        reject(new Error(`LDAP 连接失败：${error.message}`));
      });
    });
  }

  /** 搜索用户并返回 DN。 */
  private async searchUser(client: LdapClient, username: string): Promise<string | null> {
    const filter = this.config.ldapUserSearchFilter.replace(
      /\{\{username\}\}/g, escapeFilterValue(username));
    const entries = await client.search(this.config.ldapUserSearchBase, filter, NO_ATTRIBUTES);
    return entries.length > 0 ? entries[0].dn : null;
  }

  /**
   * 取用户所属的组 DN 列表。
   *
   * 组搜索用的是 `member=<用户DN>` 这类过滤器，命中的条目 DN 就是组 DN，
   * 所以不需要回读属性。
   */
  private async getUserGroups(client: LdapClient, userDn: string): Promise<string[]> {
    if (!this.config.ldapGroupSearchBase || !this.config.ldapGroupSearchFilter) return [];
    const filter = this.config.ldapGroupSearchFilter.replace(
      /\{\{userDn\}\}/g, escapeFilterValue(userDn));
    const entries = await client.search(this.config.ldapGroupSearchBase, filter, NO_ATTRIBUTES);
    return entries.map((entry) => entry.dn);
  }

  /**
   * 按映射表把组 DN 换成 admin 角色。
   *
   * 比较时统一小写：DN 的属性名不区分大小写，值在实践中也几乎总是
   * 大小写不敏感，逐字节比较会让「配置里少写一个大写字母」变成静默失效。
   */
  private mapRole(groups: string[]): string {
    const normalizedGroups = new Set(groups.map((group) => group.toLowerCase()));
    for (const [groupDn, role] of Object.entries(this.config.ldapRoleMapping)) {
      if (normalizedGroups.has(groupDn.toLowerCase())) return role;
    }
    return "viewer"; // 默认角色：最小权限
  }

  /**
   * 把目录用户同步到本地 `admin_users`。
   *
   * 返回 `null` 表示本地已禁用该账号，调用方必须据此拒绝登录。查的是
   * **包含禁用**的版本：用过滤版查会看不见禁用状态，于是每次登录都当成
   * 新账号重建，禁用形同虚设。
   */
  private async syncToLocal(username: string, role: string): Promise<AdminActor | null> {
    const existing = await this.adminUsers.findAnyByUsername(username);
    if (existing) {
      if (existing.disabled_at) return null;
      if (existing.role !== role) {
        await this.adminUsers.setRole(existing.id, role);
        this.logger.log(`LDAP 用户角色已更新：${username} ${existing.role} -> ${role}`);
      }
      // 打上「已由目录接管」标记。这是存量 LDAP 账号补齐标记的唯一途径 ——
      // 老数据存的是随机占位哈希，无法反推。标记之后，目录不可达时该账号
      // 就不再允许回落本地口令。
      if (existing.auth_source !== "ldap") {
        await this.adminUsers.setAuthSource(existing.id, "ldap");
        this.logger.log(`已将 ${username} 标记为 LDAP 接管账号`);
      }
      return {
        id: existing.id,
        username: existing.username,
        display_name: existing.display_name,
        role,
        // 目录账号不参与「首次登录强制改密」：它的口令在目录侧，本地没有可改的密码。
        must_change_password: 0,
      };
    }

    // 目录用户不掌握本地密码，存一个随机占位哈希，杜绝「本地密码后门」。
    const placeholder = this.adminAuth.hashPassword(randomBytes(32).toString("hex"));
    const created = await this.adminUsers.create(
      username, placeholder.hash, placeholder.salt, username, role, null, null, false, "ldap",
    );
    this.logger.log(`LDAP 用户已同步到本地：${username} (role=${role})`);
    return {
      id: created.id,
      username: created.username,
      display_name: created.display_name,
      role: created.role,
      must_change_password: 0,
    };
  }

  /** 是否配置了 LDAP。控制器据此决定「目录不可达」时要不要拒绝本地口令回落。 */
  get isConfigured(): boolean {
    return this.config.isLdapConfigured;
  }
}

// ==================== RFC 4515 过滤器 ====================

/**
 * 转义过滤器里作为**值**出现的字符串（RFC 4515 §3）。
 *
 * 不做这件事的话，用户名或用户 DN 里的 `(`、`)`、`*`、`\` 会被当成过滤器
 * 语法解析，轻则搜不到人，重则被拼成别处的过滤器（LDAP 注入）。
 */
export function escapeFilterValue(value: string): string {
  return value.replace(/[\\*()\u0000]/g, (char) =>
    `\\${char.charCodeAt(0).toString(16).padStart(2, "0")}`);
}

type FilterNode =
  | { kind: "and"; children: FilterNode[] }
  | { kind: "or"; children: FilterNode[] }
  | { kind: "not"; child: FilterNode }
  | { kind: "present"; attribute: string }
  | { kind: "equality"; attribute: string; value: string }
  | { kind: "substrings"; attribute: string; parts: string[] };

/**
 * 解析 RFC 4515 过滤器字符串。
 *
 * 支持 `(attr=value)`、`(attr=*)`、`(attr=a*b)`、`(&...)`、`(|...)`、`(!...)`，
 * 够覆盖 `(uid={{username}})`、`(member={{userDn}})` 以及常见的
 * `(&(objectClass=person)(uid=x))`。
 *
 * 原来的实现把整串过滤器当成 present 过滤器编码（只有标签 0x87），任何带
 * `=` 的配置都发不出去，目录只会返回空结果 —— 这是「配了 LDAP 也登不上」
 * 的根因之一。
 */
export function parseFilter(text: string): FilterNode {
  const state = { text, pos: 0 };

  function peek(): string {
    return state.text[state.pos] ?? "";
  }

  function expect(char: string): void {
    if (peek() !== char) throw new Error(`过滤器语法错误：期望 ${char}`);
    state.pos++;
  }

  function parseItem(): FilterNode {
    const start = state.pos;
    while (state.pos < state.text.length && state.text[state.pos] !== "=") state.pos++;
    if (state.pos >= state.text.length) throw new Error("过滤器缺少 =");
    const attribute = state.text.slice(start, state.pos).trim();
    if (!attribute) throw new Error("过滤器缺少属性名");
    state.pos++; // 吃掉 =

    const valueStart = state.pos;
    while (state.pos < state.text.length && state.text[state.pos] !== ")") state.pos++;
    const raw = state.text.slice(valueStart, state.pos);

    if (raw === "*") return { kind: "present", attribute };
    if (raw.includes("*")) {
      return { kind: "substrings", attribute, parts: raw.split("*").map(unescapeFilterValue) };
    }
    return { kind: "equality", attribute, value: unescapeFilterValue(raw) };
  }

  function parseNode(): FilterNode {
    expect("(");
    const next = peek();
    let node: FilterNode;
    if (next === "&" || next === "|") {
      state.pos++;
      const children: FilterNode[] = [];
      while (peek() === "(") children.push(parseNode());
      if (children.length === 0) throw new Error("空的多值过滤器");
      node = { kind: next === "&" ? "and" : "or", children };
    } else if (next === "!") {
      state.pos++;
      node = { kind: "not", child: parseNode() };
    } else {
      node = parseItem();
    }
    expect(")");
    return node;
  }

  const node = parseNode();
  if (state.pos !== state.text.length) throw new Error("过滤器尾部有多余内容");
  return node;
}

/** 还原 RFC 4515 的 `\xx` 转义。 */
function unescapeFilterValue(raw: string): string {
  return raw.replace(/\\([0-9a-fA-F]{2})/g, (_match, hex: string) =>
    String.fromCharCode(Number.parseInt(hex, 16)));
}

/** 把过滤器节点编码成 BER。标签按 RFC 4511 §4.5.1 的 [0]-[7]。 */
export function encodeFilterNode(node: FilterNode): Buffer {
  switch (node.kind) {
    case "and":
      return berConstructed(0xa0, node.children.map(encodeFilterNode));
    case "or":
      return berConstructed(0xa1, node.children.map(encodeFilterNode));
    case "not":
      return berConstructed(0xa2, [encodeFilterNode(node.child)]);
    case "equality":
      return berConstructed(0xa3, [berOctetString(node.attribute), berOctetString(node.value)]);
    case "substrings": {
      // [4] SubstringFilter { type, substrings SEQUENCE OF CHOICE { initial[0], any[1], final[2] } }
      const parts: Buffer[] = [];
      node.parts.forEach((part, index) => {
        if (part === "") return; // `a**b` 里的空段没有对应编码
        const tag = index === 0 ? 0x80 : index === node.parts.length - 1 ? 0x82 : 0x81;
        parts.push(berPrimitive(tag, Buffer.from(part, "utf8")));
      });
      return berConstructed(0xa4, [berOctetString(node.attribute), berConstructed(0x30, parts)]);
    }
    case "present":
      return berPrimitive(0x87, Buffer.from(node.attribute, "utf8"));
  }
}

// ==================== BER 编解码 ====================

/** 编码 BER 长度。 */
export function berLength(length: number): Buffer {
  if (length < 0x80) return Buffer.from([length]);
  const bytes: number[] = [];
  let remaining = length;
  while (remaining > 0) {
    bytes.unshift(remaining & 0xff);
    remaining = Math.floor(remaining / 256);
  }
  return Buffer.from([0x80 | bytes.length, ...bytes]);
}

function berPrimitive(tag: number, content: Buffer): Buffer {
  return Buffer.concat([Buffer.from([tag]), berLength(content.length), content]);
}

function berConstructed(tag: number, parts: Buffer[]): Buffer {
  return berPrimitive(tag, Buffer.concat(parts));
}

function berInteger(value: number): Buffer {
  const bytes: number[] = [];
  let remaining = value;
  do {
    bytes.unshift(remaining & 0xff);
    remaining = Math.floor(remaining / 256);
  } while (remaining > 0);
  // 最高位是 1 时要补一个 0x00，否则会被解析成负数。
  if (bytes[0] & 0x80) bytes.unshift(0x00);
  return berPrimitive(0x02, Buffer.from(bytes));
}

function berOctetString(value: string): Buffer {
  return berPrimitive(0x04, Buffer.from(value, "utf8"));
}

function berEnumerated(value: number): Buffer {
  return berPrimitive(0x0a, Buffer.from([value]));
}

function berBoolean(value: boolean): Buffer {
  return berPrimitive(0x01, Buffer.from([value ? 0xff : 0x00]));
}

function berSequence(parts: Buffer[]): Buffer {
  return berConstructed(0x30, parts);
}

/** 读出 INTEGER / ENUMERATED 的内容。 */
function decodeInteger(content: Buffer): number {
  let value = 0;
  for (const byte of content) value = value * 256 + byte;
  return value;
}

/**
 * BER 读取游标。
 *
 * 长度用 `value * 256 + byte` 累加而不是 `<<` —— 移位在 32 位有符号数上
 * 会溢出，超过 2GB 的长度会被解析成负数。
 */
class BerReader {
  private offset = 0;

  constructor(private readonly buffer: Buffer) {}

  get remaining(): number {
    return this.buffer.length - this.offset;
  }

  readTlv(): { tag: number; content: Buffer } {
    const tag = this.readByte();
    const length = this.readLength();
    if (this.remaining < length) throw new Error("BER 数据不完整");
    const content = this.buffer.subarray(this.offset, this.offset + length);
    this.offset += length;
    return { tag, content };
  }

  /** 读一个 TLV 并断言标签，用来校验结构而不是靠运气。 */
  readExpected(tag: number, what: string): Buffer {
    const tlv = this.readTlv();
    if (tlv.tag !== tag) throw new Error(`BER 结构错误：${what} 期望 0x${tag.toString(16)}`);
    return tlv.content;
  }

  private readByte(): number {
    if (this.remaining < 1) throw new Error("BER 数据不完整");
    return this.buffer[this.offset++];
  }

  private readLength(): number {
    const first = this.readByte();
    if (first < 0x80) return first;
    const count = first & 0x7f;
    if (count === 0) throw new Error("不支持不定长 BER 编码");
    let length = 0;
    for (let index = 0; index < count; index++) length = length * 256 + this.readByte();
    return length;
  }
}

/**
 * 窥探缓冲区开头的 TLV 头部，不移动游标。
 *
 * 返回 `undefined` 表示数据还没收全 —— TCP 会把一条 LDAP 消息拆成多个分片，
 * 必须等整条到齐再解析。
 */
function peekTlv(buffer: Buffer): { contentStart: number; contentLength: number } | undefined {
  if (buffer.length < 2) return undefined;
  let cursor = 1;
  let length = buffer[cursor++];
  if (length & 0x80) {
    const count = length & 0x7f;
    if (count === 0) throw new Error("不支持不定长 BER 编码");
    if (buffer.length < cursor + count) return undefined;
    length = 0;
    for (let index = 0; index < count; index++) length = length * 256 + buffer[cursor++];
  }
  if (buffer.length < cursor + length) return undefined;
  return { contentStart: cursor, contentLength: length };
}

// ==================== LDAP 客户端 ====================

interface PendingRequest {
  resolve: (entries: LdapEntry[]) => void;
  reject: (error: Error) => void;
  entries: LdapEntry[];
  timer: NodeJS.Timeout;
}

/** 简化但结构正确的 LDAP 客户端，实现 Bind 与 Search。 */
class LdapClient {
  private readonly socket: net.Socket | tls.TLSSocket;
  private readonly defaultTimeout: number;
  private messageId = 0;
  private readonly pending = new Map<number, PendingRequest>();
  private buffer: Buffer = Buffer.alloc(0);

  constructor(socket: net.Socket | tls.TLSSocket, defaultTimeout: number) {
    this.socket = socket;
    this.defaultTimeout = defaultTimeout;
    this.socket.on("data", (chunk: Buffer) => this.onData(chunk));
  }

  /** LDAP Bind。失败时 reject，reason 取目录返回的 diagnosticMessage。 */
  bind(dn: string, password: string, timeout = this.defaultTimeout): Promise<void> {
    return new Promise((resolve, reject) => {
      const id = ++this.messageId;
      this.register(id, timeout, { resolve: () => resolve(), reject, entries: [] });
      this.socket.write(encodeBindRequest(id, dn, password));
    });
  }

  /** LDAP Search，返回全部条目。 */
  search(base: string, filter: string, attributes: string[],
         timeout = this.defaultTimeout): Promise<LdapEntry[]> {
    return new Promise((resolve, reject) => {
      const id = ++this.messageId;
      this.register(id, timeout, { resolve, reject, entries: [] });
      this.socket.write(encodeSearchRequest(id, base, filter, attributes));
    });
  }

  destroy(): void {
    this.socket.destroy();
    for (const [id, request] of this.pending) {
      clearTimeout(request.timer);
      request.reject(new Error("连接已关闭"));
      this.pending.delete(id);
    }
  }

  private register(id: number, timeout: number, handlers: Omit<PendingRequest, "timer">): void {
    // 每个操作都要有自己的超时：连接超时只管握手，目录收下请求后不回应时，
    // 没有这层的话 Promise 会永远挂着。
    const timer = setTimeout(() => {
      if (this.pending.delete(id)) handlers.reject(new Error("LDAP 操作超时"));
    }, timeout);
    this.pending.set(id, { ...handlers, timer });
  }

  private settle(id: number): PendingRequest | undefined {
    const request = this.pending.get(id);
    if (!request) return undefined;
    clearTimeout(request.timer);
    this.pending.delete(id);
    return request;
  }

  private onData(chunk: Buffer): void {
    this.buffer = Buffer.concat([this.buffer, chunk]);
    for (;;) {
      let head: { contentStart: number; contentLength: number } | undefined;
      try {
        head = peekTlv(this.buffer);
      } catch (error) {
        // 协议已经跑偏，继续等数据只会一直错下去。
        this.failAll(new Error(`LDAP 响应解析失败：${(error as Error).message}`));
        return;
      }
      if (!head) return;
      const total = head.contentStart + head.contentLength;
      const payload = this.buffer.subarray(head.contentStart, total);
      this.buffer = this.buffer.subarray(total);
      try {
        this.handleMessage(payload);
      } catch (error) {
        this.failAll(new Error(`LDAP 响应解析失败：${(error as Error).message}`));
        return;
      }
    }
  }

  private failAll(error: Error): void {
    for (const [id, request] of this.pending) {
      clearTimeout(request.timer);
      request.reject(error);
      this.pending.delete(id);
    }
    this.buffer = Buffer.alloc(0);
  }

  /** 处理一条 LDAPMessage 的内容部分（messageID + protocolOp）。 */
  private handleMessage(payload: Buffer): void {
    const reader = new BerReader(payload);
    const messageId = decodeInteger(reader.readExpected(0x02, "messageID"));
    const operation = reader.readTlv();
    const request = this.pending.get(messageId);
    if (!request) return; // 迟到的响应，对应请求早已超时

    switch (operation.tag) {
      case 0x61: { // BindResponse
        const { resultCode, diagnosticMessage } = parseResult(operation.content);
        const settled = this.settle(messageId);
        if (!settled) return;
        if (resultCode === 0) settled.resolve([]);
        else settled.reject(new Error(diagnosticMessage || `LDAP 绑定失败（resultCode=${resultCode}）`));
        return;
      }
      case 0x64: { // SearchResultEntry
        // 原实现解析出 dn 之后只把空对象塞进结果集，上游永远拿不到 DN，
        // 于是每次搜索都「查无此人」。这里保留完整条目。
        const entryReader = new BerReader(operation.content);
        const dn = entryReader.readExpected(0x04, "objectName").toString("utf8");
        const attributes = parseAttributes(entryReader.readExpected(0x30, "attributes"));
        request.entries.push({ dn, attributes });
        return;
      }
      case 0x65: { // SearchResultDone
        const { resultCode, diagnosticMessage } = parseResult(operation.content);
        const settled = this.settle(messageId);
        if (!settled) return;
        // 部分目录会在结果集非空时以 sizeLimitExceeded(4) 结束，
        // 这时已有条目仍然可用，不当成失败。
        if (resultCode === 0 || (resultCode === 4 && settled.entries.length > 0)) {
          settled.resolve(settled.entries);
        } else {
          settled.reject(new Error(diagnosticMessage || `LDAP 搜索失败（resultCode=${resultCode}）`));
        }
        return;
      }
      default:
        return; // 其它协议操作（如 SearchResultReference）忽略
    }
  }
}

/** 解析 LDAPResult：resultCode + matchedDN + diagnosticMessage。 */
function parseResult(content: Buffer): { resultCode: number; diagnosticMessage: string } {
  const reader = new BerReader(content);
  const resultCode = decodeInteger(reader.readTlv().content);
  reader.readTlv(); // matchedDN，用不到
  const diagnosticMessage = reader.readTlv().content.toString("utf8");
  return { resultCode, diagnosticMessage };
}

/** 解析 PartialAttributeList：SEQUENCE OF { type, SET OF values }。 */
function parseAttributes(content: Buffer): Record<string, string[]> {
  const attributes: Record<string, string[]> = {};
  const reader = new BerReader(content);
  while (reader.remaining > 0) {
    const attribute = new BerReader(reader.readTlv().content);
    const type = attribute.readTlv().content.toString("utf8");
    const values = new BerReader(attribute.readTlv().content);
    const collected: string[] = [];
    while (values.remaining > 0) collected.push(values.readTlv().content.toString("utf8"));
    attributes[type] = collected;
  }
  return attributes;
}

/**
 * 编码 LDAPMessage 包 BindRequest。
 *
 * BindRequest ::= [APPLICATION 0] SEQUENCE { version INTEGER, name LDAPDN,
 *   authentication AuthenticationChoice }，简单认证是上下文标签 [0] 基本型。
 */
function encodeBindRequest(id: number, dn: string, password: string): Buffer {
  return berSequence([
    berInteger(id),
    berConstructed(0x60, [
      berInteger(3),
      berOctetString(dn),
      berPrimitive(0x80, Buffer.from(password, "utf8")),
    ]),
  ]);
}

/**
 * 编码 LDAPMessage 包 SearchRequest。
 *
 * SearchRequest ::= [APPLICATION 3] SEQUENCE { baseObject, scope, derefAliases,
 *   sizeLimit, timeLimit, typesOnly, filter, attributes }
 */
function encodeSearchRequest(id: number, base: string, filter: string, attributes: string[]): Buffer {
  return berSequence([
    berInteger(id),
    berConstructed(0x63, [
      berOctetString(base),
      berEnumerated(2), // wholeSubtree
      berEnumerated(0), // neverDerefAliases
      berInteger(0), // sizeLimit：0 表示不限
      berInteger(0), // timeLimit：0 表示不限，超时由客户端控制
      berBoolean(false), // typesOnly
      encodeFilterNode(parseFilter(filter)),
      berSequence(attributes.map(berOctetString)),
    ]),
  ]);
}
