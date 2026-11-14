import { createHmac, randomBytes, timingSafeEqual } from "node:crypto";

/**
 * RFC 6238（TOTP）/ RFC 4228（HOTP）自包含实现 —— 从服务端
 * `server/src/admin-auth/totp.ts` 原样移植，供运维脚本直连管理接口时本地生成动态码。
 *
 * 算法固定 HMAC-SHA1 / 6 位 / 30 秒步长，与主流验证器默认行为一致；
 * 密钥是 RFC 4648 base32（容忍空格、连字符与尾部 `=`，统一转大写）。
 */

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
const TIME_STEP_SECONDS = 30;
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
    value &= (1 << bits) - 1;
  }
  if (bits > 0) output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return output;
}

/** 解码 base32 密钥（宽松：容忍空格/连字符/尾部填充，统一大写）。 */
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

/** HOTP：计数器做 HMAC-SHA1 后按 RFC 4226 §5.3 动态截断。 */
export function hotp(key: Buffer, counter: number, digits = DIGITS): string {
  if (counter < 0 || !Number.isSafeInteger(counter)) {
    throw new Error(`HOTP 计数器不合法：${counter}`);
  }
  const buffer = Buffer.alloc(8);
  buffer.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac("sha1", key).update(buffer).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const binary =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff);
  return String(binary % 10 ** digits).padStart(digits, "0");
}

/** 生成当前时间窗（± window 个步长容错）的 TOTP 动态码。 */
export function totp(secret: string, atMs: number = Date.now(), window = 1): string[] {
  const key = base32Decode(secret);
  const counter = Math.floor(atMs / 1000 / TIME_STEP_SECONDS);
  const codes: string[] = [];
  for (let drift = -window; drift <= window; drift++) codes.push(hotp(key, counter + drift));
  return codes;
}

/** 校验一个动态码（等价于服务端 verifyTotp 的宽窗口匹配）。 */
export function verifyTotp(secret: string, code: string, atMs: number = Date.now(), window = 1): boolean {
  const expected = totp(secret, atMs, window);
  return expected.some((candidate) => safeEqual(candidate, code.trim()));
}

/** 生成 160 bit 熵的新密钥（base32，无填充）。 */
export function generateTotpSecret(): string {
  return base32Encode(randomBytes(20));
}

function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}
