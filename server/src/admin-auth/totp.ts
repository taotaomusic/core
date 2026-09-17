import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

/**
 * RFC 6238（TOTP）与 RFC 4226（HOTP）的自包含实现。
 *
 * 迁移前这一步依赖 `speakeasy`，但该库已长期停止维护，而它承担的是 2FA 校验
 * 这条安全关键路径 —— 一个不再收安全修复的库不该留在认证链上。这里用
 * `node:crypto` 直接实现，算法固定为 HMAC-SHA1 / 6 位 / 30 秒步长，与主流
 * 验证器（Google Authenticator、Microsoft Authenticator 等）的默认行为一致。
 *
 * 与旧数据的兼容性：`admin_users.totp_secret` 里已存的 base32 密钥由同一套
 * base32 解码逻辑处理，**不需要迁移数据**。新生成的密钥改为直接取 20 字节
 * 随机数再 base32 编码（完整 160 bit 熵）；旧实现是把 20 字节随机数逐字节
 * 映射到一个 80 字符的字母表（约 126 bit 熵）。两者对 TOTP 校验完全等价。
 */

/** RFC 4648 base32 字母表。 */
const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

/** TOTP 时间步长（秒）。30 秒是 RFC 6238 推荐值，也是验证器的默认值。 */
const TIME_STEP_SECONDS = 30;

/** 动态码位数。 */
const DIGITS = 6;

/** 把字节序列编码成不带填充的 base32（RFC 4648）。 */
export function base32Encode(input: Buffer): string {
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of input) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
    // 只保留尚未输出的低位，避免 value 在长输入上溢出 32 位。
    value &= (1 << bits) - 1;
  }
  if (bits > 0) output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return output;
}

/**
 * 解码 base32 密钥。
 *
 * 比 RFC 4648 宽松：容忍空格、连字符（验证器常见分组写法）与尾部填充 `=`，
 * 并统一转大写。这样从验证器里手工抄回来的密钥也能直接用。
 */
export function base32Decode(secret: string): Buffer {
  const normalized = secret.replace(/[\s-]/g, "").replace(/=+$/, "").toUpperCase();
  if (!normalized) throw new Error("TOTP 密钥为空");
  const bytes: number[] = [];
  let bits = 0;
  let value = 0;
  for (const char of normalized) {
    const index = BASE32_ALPHABET.indexOf(char);
    if (index < 0) throw new Error(`TOTP 密钥含非法 base32 字符：${char}`);
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      bytes.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
    value &= (1 << bits) - 1;
  }
  return Buffer.from(bytes);
}

/**
 * HOTP：把计数器做 HMAC-SHA1，再按 RFC 4226 §5.3 做动态截断。
 *
 * 截断规则：取 HMAC 最后一字节的低 4 位作为偏移量，从该位置读 4 字节并
 * 抹掉最高位（避免被当成负数），最后对 10^digits 取模。
 */
export function hotp(key: Buffer, counter: number, digits = DIGITS): string {
  if (counter < 0 || !Number.isSafeInteger(counter)) {
    throw new Error(`HOTP 计数器不合法：${counter}`);
  }
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac("sha1", key).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const binary =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff);
  return String(binary % 10 ** digits).padStart(digits, "0");
}

/** TOTP：按时间推出计数器再走 HOTP。 */
export function totp(key: Buffer, timeMs: number = Date.now(), digits = DIGITS): string {
  return hotp(key, Math.floor(timeMs / 1000 / TIME_STEP_SECONDS), digits);
}

/**
 * 校验动态码。
 *
 * `window = 1` 表示同时接受前一个、当前、后一个时间步（±30 秒），
 * 用来容忍客户端与服务端的时钟偏差 —— 与迁移前的行为一致。
 *
 * 比较用 `timingSafeEqual` 而不是 `===`，避免从响应时间推断出命中的是
 * 哪一个时间窗。任一步解码失败一律返回 false，不向上抛。
 */
export function verifyTotpCode(
  secretBase32: string,
  token: string,
  window = 1,
  timeMs: number = Date.now(),
): boolean {
  // 先挡掉长度不对的输入，避免把任意字符串喂进后面的计算。
  if (!/^\d{6}$/.test(token)) return false;
  let key: Buffer;
  try {
    key = base32Decode(secretBase32);
  } catch {
    return false;
  }
  if (key.length === 0) return false;

  const provided = Buffer.from(token);
  const counter = Math.floor(timeMs / 1000 / TIME_STEP_SECONDS);
  for (let offset = -window; offset <= window; offset++) {
    const candidate = counter + offset;
    if (candidate < 0) continue;
    const expected = Buffer.from(hotp(key, candidate));
    if (expected.length === provided.length && timingSafeEqual(expected, provided)) return true;
  }
  return false;
}

/**
 * 生成一个新的 TOTP 密钥与对应的 otpauth URL。
 *
 * URL 形态与迁移前保持一致：`otpauth://totp/<label>?secret=<base32>`。
 * label 是「签发方 (用户名)」，按 URL 编码；不加 `issuer` 查询参数，
 * 因为旧实现也没加，保持已发出去的二维码行为不变。
 */
export function generateTotpSecret(
  issuer: string,
  username: string,
): { secret: string; otpauthUrl: string } {
  const secret = base32Encode(randomBytes(20));
  const label = encodeURIComponent(`${issuer} (${username})`);
  return { secret, otpauthUrl: `otpauth://totp/${label}?secret=${secret}` };
}
