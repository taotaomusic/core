import * as crypto from "crypto";

/**
 * 波点 App 原生签名（KPK）的复刻实现。
 *
 * ## 为什么需要它
 *
 * 波点有两条取址入口，签名方式完全不同：
 *
 * | 入口 | 签名 | 时间戳 | 匿名可用 |
 * | --- | --- | --- | --- |
 * | `playbasic/music/v2/audioUrl` | 通用 `kuwotest` MD5，放 `sign` **请求头** | 秒 | 是 |
 * | `play/music/v2/audioUrl` | 原生 `KpkUtil.kpk`，放 `sign` **query 参数** | **毫秒** | 否 |
 *
 * 原生入口的签名由 `libkpk.so` 生成，而这个 `.so` 只有 `arm64-v8a` 一个 ABI ——
 * 原先只能在 ARM64 设备上调 JNI 取得，服务端拿不到。本文件把算法复刻成纯 JS，
 * 服务端不再依赖设备。
 *
 * ## 算法
 *
 * ```text
 * kpk(url) = url + "&sign=" + md5("kuwotest" + 归一化(query) + path)
 * ```
 *
 * 三个分量的取法（**错一个就是 `439 sign invalid`**）：
 *
 * ```text
 * query     第一个 "?" 之后的全部内容
 * 归一化     只保留 [A-Za-z0-9]，再把剩下的字符按字符升序排序
 * path      剥掉 "http://" / "https://" 后，从第一个 "/" 起、到 "?" 之前的部分
 * ```
 *
 * 注意它与通用签名是**同一个原语的两种参数化**，不是两套算法：
 *
 * ```text
 * 通用: sign = md5("kuwotest" + 归一化(完整 URI) + bodyHash + 完整 URI)
 * KPK : sign = md5("kuwotest" + 归一化(query)    + ""       + path)
 * ```
 *
 * 通用签名归一化的是整条 URI（含协议、域名、路径），尾串也是整条 URI；
 * KPK 归一化的是 query，尾串是 path，bodyHash 恒为空。
 *
 * ## 怎么确认它是对的
 *
 * `libkpk.so` 未做混淆，`.rodata` 里明文躺着 `kuwotest`、`&sign=`、`http://`、
 * `https://`、`%02x` 以及 MD5 初始向量 `0123456789abcdeffedcba9876543210`，
 * JNI 注册表 `cn/kuwo/base/utils/KpkUtil#kpk` 指向的函数里能直接读到这套流程。
 *
 * ⚠️ **这套依据来自对 `libkpk.so` 本身的分析，不是 Dart 反编译。** 2026-09-19 用
 * blutter 反编译（`out-bodian`）复核时，Dart 层里找不到任何 `KpkUtil` —— 这是**预期的**：
 * blutter 只还原 Dart AOT，原生 arm64 库不在它的输出范围内，所以「Dart dump 里没有」
 * 既不能证实也不能证伪这套算法。本文件的正确性判据仍是上面的**实网业务码对照**
 * （439→20018→200）与 `.so` 的 `.rodata`/JNI 读取，而不是 Dart dump。
 * 另外，这版 App 的 Dart 层实测并**不调用**用到本签名的 `play/music/v2/audioUrl`
 * —— 详见 `bodian.client.ts` 的 [NATIVE_AUDIO_URL] 注释。
 *
 * 线上对照（同一首歌、同一请求，只改签名）：
 *
 * ```text
 * 不带 sign          -> 433 param error
 * 通用 MD5 签名       -> 439 sign invalid   ← 通用签名不被这个入口接受
 * KPK 签名           -> 20018 没有解锁付费歌曲 / 20012 歌曲已下线 / 200
 * ```
 *
 * 错误码从 `439` 前进到业务码，就说明签名这一层已经过了 —— 剩下的 20018 / 20012
 * 是账号权益与曲库状态，与签名无关。可复现的对照脚本见 `tools/verify-kpk.mjs`。
 */

/** 签名盐。与通用签名共用同一个常量。 */
const KPK_SALT = "kuwotest";

/**
 * 归一化：只保留 ASCII 字母数字，再按字符升序排序。
 *
 * 排序的是**字符**不是 token，所以 `b=2&a=1` 与 `a=1&b=2` 归一化后相同 ——
 * 这正是签名不校验参数顺序的原因。反过来，**参数顺序改变后必须重新签名**，
 * 因为尾串里的 `path` 不含 query，而 query 本身参与归一化。
 */
export function kpkNormalize(value: string): string {
  const kept = value.replace(/[^A-Za-z0-9]/g, "");
  return kept.split("").sort().join("");
}

/**
 * 从 `协议 + 域名 + 路径` 里取出 path。
 *
 * 不匹配 `http://` / `https://` 前缀时返回空串 —— 宁可签出一个错的也不抛异常：
 * 调用方拿到的永远是一条形状合法的 URL，失败会在上游表现为 `439`，
 * 比在这里中断整条播放链更容易定位。
 */
export function kpkExtractPath(prefix: string): string {
  let rest: string;
  if (prefix.startsWith("http://")) rest = prefix.slice(7);
  else if (prefix.startsWith("https://")) rest = prefix.slice(8);
  else return "";
  const slash = rest.indexOf("/");
  return slash < 0 ? "" : rest.slice(slash);
}

/** 把一条 URL 拆成 KPK 需要的三个分量。导出是为了让自检脚本能逐段对照。 */
export function kpkSplit(url: string): { prefix: string; query: string; path: string } {
  const mark = url.indexOf("?");
  const prefix = mark < 0 ? url : url.slice(0, mark);
  const query = mark < 0 ? "" : url.slice(mark + 1);
  return { prefix, query, path: kpkExtractPath(prefix) };
}

/**
 * 算出 32 位小写十六进制签名。
 *
 * 入参必须是**尚未包含 `sign` 的完整 URL**。签名后若再改动任何参数，
 * 必须对改动后的 URL 重新签名 —— 复用旧签名会被上游判 `439`。
 */
export function kpkSign(unsignedUrl: string): string {
  const { query, path } = kpkSplit(unsignedUrl);
  return crypto
    .createHash("md5")
    .update(`${KPK_SALT}${kpkNormalize(query)}${path}`, "utf8")
    .digest("hex");
}

/** 返回带 `&sign=` 的完整 URL。 */
export function kpk(unsignedUrl: string): string {
  return `${unsignedUrl}&sign=${kpkSign(unsignedUrl)}`;
}
