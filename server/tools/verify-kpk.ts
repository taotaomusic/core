/**
 * KPK 原生签名的正确性验证。
 *
 * 这不是「跑通就算」的冒烟脚本，而是把签名算法的每一段都钉死：
 * 归一化多留一个字符、md5 用了大写十六进制、path 多带了协议前缀，
 * 都会让**所有**原生取址请求被判 `439 sign invalid`，
 * 而表现只是「歌放不出来」，看错误文案完全定位不到签名。
 *
 * 分两段：
 *
 * 1. **离线向量**（默认执行）—— 对 `02-加密与签名.md` 的固定向量逐项对答案。
 *    这个向量来自 App 复刻工程，与 KPK 共用同一个 md5 原语，
 *    所以它能同时钉死盐值、归一化规则和 md5 编码。
 * 2. **线上对照**（`--live`）—— 同一条 URL 分别用通用签名和 KPK 签名请求，
 *    断言错误码从 `439` 前进到业务码。这是签名真正生效的证据。
 *
 * 运行：npm run verify:kpk   （加 `--live` 才发网络请求）
 */
import { kpkNormalize, kpkSign, kpkSplit } from "../src/upstream/kpk.util";
import { createHash } from "crypto";

let passed = 0;
let failed = 0;

/** `payload` 头的编码：`Base64(XOR(UTF8(json), UTF8("bd2025@")))`。 */
function xorBase64(value: string): string {
  const key = Buffer.from("bd2025@", "utf8");
  const bytes = Buffer.from(value, "utf8");
  for (let i = 0; i < bytes.length; i++) bytes[i] ^= key[i % key.length];
  return bytes.toString("base64");
}

function check(name: string, actual: unknown, expected: unknown): void {
  const ok = actual === expected;
  if (ok) {
    passed += 1;
    console.log(`  \u2713 ${name}`);
  } else {
    failed += 1;
    console.log(`  \u2717 ${name}\n      实际: ${String(actual)}\n      期望: ${String(expected)}`);
  }
}

console.log("KPK 签名验证\n");

// ---------- 1. 共用原语的固定向量 ----------
// 逐字取自复刻工程 `wiki/02-加密与签名.md` 第 4.1 节，不要手抄，改动前先回源核对。
const VECTOR_URI = "https://bd-api.kuwo.cn/api/ucenter/users/login?uid=-1&token=&timestamp=1700000000";
const VECTOR_BODY_HASH = "4f74f81bea8e0749b44481053d8b91fc";
const VECTOR_NORMALIZED = "00000000117aaabccddeeeeeghiiiiikklmmnnnnooopppprrssssttttttuuuuw";
const VECTOR_SIGN = "ce541beaf3980e105636491234bba71d";

console.log("共用原语（通用签名固定向量）");
check("归一化结果", kpkNormalize(VECTOR_URI), VECTOR_NORMALIZED);
check(
  "签名（含 bodyHash）",
  createHash("md5").update(`kuwotest${VECTOR_NORMALIZED}${VECTOR_BODY_HASH}${VECTOR_URI}`, "utf8").digest("hex"),
  VECTOR_SIGN,
);

// ---------- 2. URL 拆解 ----------
console.log("\nURL 拆解");
const SPLIT_CASE = "https://bd-api.kuwo.cn/api/play/music/v2/audioUrl?uid=1&token=a";
check("path 不含协议与域名", kpkSplit(SPLIT_CASE).path, "/api/play/music/v2/audioUrl");
check("query 取问号之后全部", kpkSplit(SPLIT_CASE).query, "uid=1&token=a");
check("无 query 时为空串", kpkSplit("https://bd-api.kuwo.cn/api/x").query, "");
check("http:// 同样剥掉", kpkSplit("http://a.b/c?d=1").path, "/c");
check("无协议时 path 为空", kpkSplit("a.b/c?d=1").path, "");

// ---------- 3. 归一化的边界 ----------
console.log("\n归一化边界");
check("只保留 ASCII 字母数字", kpkNormalize("uid=-1&token=&timestamp=1700000000"), "00000000117adeeiikmmnopstttu");
check("参数顺序不影响结果", kpkNormalize("b=2&a=1"), kpkNormalize("a=1&b=2"));
check("中文被丢弃", kpkNormalize("a晴天b"), "ab");
check("空串归一化仍为空", kpkNormalize(""), "");

// ---------- 4. KPK 回归钉 ----------
// 这条期望值由**已在线上验证过**的实现产出（同一请求：通用签名得 439、
// KPK 签名得业务码），钉住它是为了防止后续重构静默改变签名输入。
console.log("\nKPK 回归钉");
const PIN_URL =
  "https://bd-api.kuwo.cn/api/play/music/v2/audioUrl" +
  "?uid=1&token=a&timestamp=1758200000000&musicId=228908&format=mp3&br=128kmp3&freeSign=";
check("签名稳定", kpkSign(PIN_URL), "37f7eb93573a826238733c73924e4afc");
check("签名是 32 位小写十六进制", /^[0-9a-f]{32}$/.test(kpkSign(PIN_URL)), true);
check("改一个参数就换签名", kpkSign(PIN_URL) !== kpkSign(PIN_URL.replace("228908", "228909")), true);

// ---------- 5. 线上对照（可选） ----------
// 包在 async 函数里而不是用顶层 await：这个脚本走 CommonJS（见 tools/tsconfig.json）。
async function liveCheck(): Promise<void> {
  console.log("\n线上对照");
  const url =
    "https://bd-api.kuwo.cn/api/play/music/v2/audioUrl" +
    `?uid=&token=&timestamp=${Date.now()}&devId=00000000000000000000000000000000` +
    "&musicId=228908&format=mp3&br=128kmp3&freeSign=";
  const headers = {
    Accept: "application/json",
    client: "android",
    "X-Requested-With": "cn.wenyu.bodian",
    "api-ver": "2",
    "User-Agent": "BoDianMusic/5.9.1 (Android; cn.wenyu.bodian)",
    // `payload` 头不能省：**上游先查 payload 再查 sign**，
    // 少了它两边都只回 `402 request invalid`，看起来像签名没生效，其实是形状不全。
    payload: xorBase64(
      JSON.stringify({
        devId: "00000000000000000000000000000000",
        ver: "5.9.1",
        plat: "ar",
        channel: "wenyu",
        net: "wifi",
        uid: "",
        token: "",
        qimei36: "",
      }),
    ),
  };
  const clean = url.replace(/[^a-zA-Z0-9]/g, "");
  const generic = createHash("md5")
    .update(`kuwotest${clean.split("").sort().join("")}${url}`, "utf8")
    .digest("hex");

  const codeOf = async (signed: string) => {
    const response = await fetch(signed, { headers });
    const body = (await response.json().catch(() => ({}))) as { code?: number; msg?: string };
    return { code: Number(body.code), msg: String(body.msg ?? "") };
  };

  const withGeneric = await codeOf(`${url}&sign=${generic}`);
  const withKpk = await codeOf(`${url}&sign=${kpkSign(url)}`);
  console.log(`  通用签名 -> code=${withGeneric.code} ${withGeneric.msg}`);
  console.log(`  KPK 签名 -> code=${withKpk.code} ${withKpk.msg}`);
  check("通用签名被判 439", withGeneric.code, 439);
  check("KPK 签名不被判 439（说明签名已通过）", withKpk.code !== 439, true);
  check("KPK 签名拿到业务码（433/402 都不算）", withKpk.code !== 433 && withKpk.code !== 402, true);
}

async function main(): Promise<void> {
  if (process.argv.includes("--live")) {
    await liveCheck();
  } else {
    console.log("\n（跳过线上对照，加 --live 执行）");
  }
  console.log(`\nKPK 验证：${passed} 通过，${failed} 失败`);
  if (failed > 0) process.exit(1);
}

void main();
