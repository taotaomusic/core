import assert from "node:assert/strict";
import test from "node:test";
import { base32Decode, base32Encode, hotp, totp, verifyTotp, generateTotpSecret } from "../totp.js";

test("RFC 4226 附录 D 的 HOTP 已知向量", () => {
  // RFC 4226 Appendix D：secret = "12345678901234567890"（ASCII）。
  const key = Buffer.from("12345678901234567890", "ascii");
  const expected = ["755224", "287082", "359152", "969429", "338314", "254676", "287922", "162583", "399871", "520489"];
  expected.forEach((code, counter) => {
    assert.equal(hotp(key, counter), code, `counter=${counter}`);
  });
});

test("base32 编解码往返", () => {
  for (const bytes of [Buffer.from([0]), Buffer.from("12345678901234567890", "ascii"), Buffer.from([255, 0, 127])]) {
    assert.deepEqual(base32Decode(base32Encode(bytes)), bytes);
  }
  // 空字节序列编码成空串；空串对解码是非法密钥（无信息量）。
  assert.equal(base32Encode(Buffer.alloc(0)), "");
  assert.throws(() => base32Decode(""), /密钥为空/);
});

test("base32 解码容忍空格、连字符、填充与小写", () => {
  const secret = base32Encode(Buffer.from("12345678901234567890", "ascii"));
  const grouped = secret.match(/.{1,4}/g)!.join("-").toLowerCase() + "=";
  assert.deepEqual(base32Decode(grouped), base32Decode(secret));
});

test("totp 与 verifyTotp 互为逆操作", () => {
  const secret = generateTotpSecret();
  const now = 1_700_000_000_000;
  const [code] = totp(secret, now, 0);
  assert.equal(verifyTotp(secret, code, now, 0), true);
  // 相邻窗口容错：window=1 时上一窗口的码也能过。
  const [previous] = totp(secret, now - 30_000, 0);
  assert.equal(verifyTotp(secret, previous, now, 1), true);
  // 错误的码必不过；对随机密钥恰好为 000000 的概率 1e-6，断言「错误码必拒」时绕开它。
  const wrongCode = code === "000000" ? "111111" : "000000";
  assert.equal(verifyTotp(secret, wrongCode, now, 0), false);
});
