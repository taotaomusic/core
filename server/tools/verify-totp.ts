/**
 * TOTP 实现的正确性验证。
 *
 * 这不是「跑通就算」的冒烟脚本，而是拿 RFC 的官方测试向量逐条对答案：
 * 动态截断写错一位、base32 解码差一个 padding，都会让所有 2FA 用户被锁在
 * 门外，而且只有在真实验证器上才会暴露。所以必须用标准向量钉死。
 *
 * 运行：npm run verify:totp
 */
import { base32Decode, base32Encode, hotp, totp, verifyTotpCode } from "../src/admin-auth/totp";

let passed = 0;
let failed = 0;

function check(name: string, actual: unknown, expected: unknown): void {
  const ok = actual === expected;
  if (ok) {
    passed += 1;
    console.log(`  \u2713 ${name}`);
  } else {
    failed += 1;
    console.error(`  \u2717 ${name}\n      期望 ${String(expected)}，实际 ${String(actual)}`);
  }
}

function checkTrue(name: string, value: boolean): void {
  check(name, value, true);
}

console.log("TOTP 正确性验证\n");

// ---------------------------------------------------------------------------
// 1. base32 编解码
// ---------------------------------------------------------------------------
console.log("base32 编解码");

const RFC_SECRET_ASCII = "12345678901234567890";
const RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

check(
  "编码 RFC 4226 测试密钥",
  base32Encode(Buffer.from(RFC_SECRET_ASCII, "ascii")),
  RFC_SECRET_BASE32,
);
check(
  "解码 RFC 4226 测试密钥",
  base32Decode(RFC_SECRET_BASE32).toString("ascii"),
  RFC_SECRET_ASCII,
);
check(
  "解码容忍空格与连字符分组",
  base32Decode("gezd gnbv-gy3t qojq gezd gnbv gy3t qojq").toString("ascii"),
  RFC_SECRET_ASCII,
);
check("解码容忍尾部填充", base32Decode(`${RFC_SECRET_BASE32}=`).toString("ascii"), RFC_SECRET_ASCII);
check("编码长度与 RFC 一致", base32Encode(Buffer.from(RFC_SECRET_ASCII, "ascii")).length, 32);

let invalidRejected = false;
try {
  base32Decode("GEZD1NBV");
} catch {
  invalidRejected = true;
}
checkTrue("解码拒绝非法字符", invalidRejected);

// ---------------------------------------------------------------------------
// 2. RFC 4226 附录 D：HOTP 测试向量
// ---------------------------------------------------------------------------
console.log("\nRFC 4226 附录 D：HOTP 向量");

const HOTP_VECTORS = [
  "755224", "287082", "359152", "969429", "338314",
  "254676", "287922", "162583", "399871", "520489",
];
const rfcKey = base32Decode(RFC_SECRET_BASE32);
HOTP_VECTORS.forEach((expected, counter) => {
  check(`counter=${counter}`, hotp(rfcKey, counter), expected);
});

// ---------------------------------------------------------------------------
// 3. RFC 6238 附录 B：TOTP 测试向量（SHA1，8 位）
// ---------------------------------------------------------------------------
console.log("\nRFC 6238 附录 B：TOTP 向量（SHA1）");

const TOTP_VECTORS: Array<[number, string]> = [
  [59, "94287082"],
  [1111111109, "07081804"],
  [1111111111, "14050471"],
  [1234567890, "89005924"],
  [2000000000, "69279037"],
  [20000000000, "65353130"],
];
for (const [unixSeconds, expected] of TOTP_VECTORS) {
  const actual = totp(rfcKey, unixSeconds * 1000, 8);
  check(`T=${unixSeconds}`, actual, expected);
  // 本服务用 6 位，应当就是 8 位码对 10^6 取模的结果。
  check(`T=${unixSeconds} 的 6 位形式`, totp(rfcKey, unixSeconds * 1000, 6), expected.slice(-6));
}

// ---------------------------------------------------------------------------
// 4. window 容错边界
// ---------------------------------------------------------------------------
console.log("\nwindow=1 容错边界");

const fixedTime = 1111111111 * 1000;
const currentCode = totp(rfcKey, fixedTime);
checkTrue("当前时间步通过", verifyTotpCode(RFC_SECRET_BASE32, currentCode, 1, fixedTime));
checkTrue(
  "前一时间步通过",
  verifyTotpCode(RFC_SECRET_BASE32, totp(rfcKey, fixedTime - 30_000), 1, fixedTime),
);
checkTrue(
  "后一时间步通过",
  verifyTotpCode(RFC_SECRET_BASE32, totp(rfcKey, fixedTime + 30_000), 1, fixedTime),
);
check(
  "前两个时间步被拒",
  verifyTotpCode(RFC_SECRET_BASE32, totp(rfcKey, fixedTime - 60_000), 1, fixedTime),
  false,
);
check(
  "后两个时间步被拒",
  verifyTotpCode(RFC_SECRET_BASE32, totp(rfcKey, fixedTime + 60_000), 1, fixedTime),
  false,
);
check("非 6 位数字被拒", verifyTotpCode(RFC_SECRET_BASE32, "12345", 1, fixedTime), false);
check("非数字被拒", verifyTotpCode(RFC_SECRET_BASE32, "abcdef", 1, fixedTime), false);
check("空密钥被拒", verifyTotpCode("", "123456", 1, fixedTime), false);

// ---------------------------------------------------------------------------
// 5. 与 speakeasy 对拍（移除依赖前才有意义；移除后自动跳过）
// ---------------------------------------------------------------------------
console.log("\n与 speakeasy 对拍");

interface LegacySpeakeasy {
  totp(options: { secret: string; encoding: string; time: number }): string;
}

let speakeasy: LegacySpeakeasy | undefined;
try {
  // 用 require 而不是 import：speakeasy 已从 package.json 移除，
  // 静态 import 会让这个脚本在依赖删掉后直接编译不过。
  speakeasy = require("speakeasy") as LegacySpeakeasy;
} catch {
  speakeasy = undefined;
}

/** 生成一个 20 字节随机 base32 密钥。 */
function randomSecret(): string {
  return base32Encode(Buffer.from(Array.from({ length: 20 }, () => Math.floor(Math.random() * 256))));
}

if (!speakeasy) {
  console.log("  - 已移除 speakeasy，跳过对拍（RFC 向量已覆盖核心算法）");
} else {
  const legacy = speakeasy;
  let mismatches = 0;
  for (let round = 0; round < 200; round++) {
    const secret = randomSecret();
    const timeMs = Math.floor(Math.random() * 2_000_000_000_000);
    const mine = totp(base32Decode(secret), timeMs, 6);
    const theirs = legacy.totp({
      secret,
      encoding: "base32",
      time: Math.floor(timeMs / 1000),
    });
    if (mine !== theirs) {
      mismatches += 1;
      if (mismatches <= 3) {
        console.error(`      不一致：secret=${secret} time=${timeMs} 本实现=${mine} speakeasy=${theirs}`);
      }
    }
  }
  check("200 组随机密钥 × 随机时间戳一致", mismatches, 0);

  // 向后兼容：speakeasy 生成的动态码必须能被本实现校验通过。
  const COMPAT_TIME_MS = 1_700_000_000_000;
  let backwardOk = true;
  for (let round = 0; round < 20; round++) {
    const secret = randomSecret();
    const legacyCode = legacy.totp({
      secret,
      encoding: "base32",
      time: Math.floor(COMPAT_TIME_MS / 1000),
    });
    if (!verifyTotpCode(secret, legacyCode, 1, COMPAT_TIME_MS)) backwardOk = false;
  }
  checkTrue("能校验 speakeasy 生成的动态码（向后兼容）", backwardOk);
}

// ---------------------------------------------------------------------------
console.log(`\n结果：${passed} 项通过，${failed} 项失败`);
if (failed > 0) process.exit(1);
console.log("TOTP 实现与 RFC 向量一致。");
