import * as crypto from "crypto";
import * as fs from "fs";
import * as path from "path";
import { once } from "node:events";
import { inflateSync } from "zlib";
import { kpkSign } from "./kpk.util";

/**
 * 酷我「波点」接口的传输层客户端。
 *
 * 这是反编译波点 App 后复刻出来的接口层，只负责协议本身：签名、XOR 加密、
 * 压缩与编码还原、字段搬运。**它不做任何领域映射** —— 把上游字段翻译成
 * 桃桃音乐的歌曲模型由 [KuwoClient] 负责，两者刻意分开，换协议不影响业务。
 *
 * 与原始复刻版本相比修掉了 5 处会静默出错的缺陷，每处都在下方就地注明。
 */

// ==================== 类型定义 ====================

/** 搜索结果里的一首歌。字段来自 `search/music/list` 的 resultList 条目。 */
export interface Song {
  id: number;
  name: string;
  artist: string;
  album: string;
  /** 封面绝对地址。搜索结果里叫 `albumPic`。 */
  cover?: string;
  /** 时长，秒。搜索结果里叫 `duration`。 */
  durationSeconds?: number;
  /** 副标题，例如「《声生不息》综艺」。 */
  subtitle?: string;
  /**
   * 这首歌能否真的取到播放地址。
   *
   * 匿名状态下由 `payInfo.listen_fragment` 判定。该字段表示免费/试听权限，
   * 登录会员后不能继续据此置灰：张震岳《再见》匿名为 `1`，但有效会员凭据通过
   * 原生 KPK 接口可取得完整 128K、320K 与 FLAC。
   */
  playable?: boolean;
}

export interface Artist {
  id: number;
  name: string;
  songs: number;
  albums: number;
}

export interface Album {
  id: number;
  name: string;
  artist: string;
}

export interface Playlist {
  id: number;
  name: string;
}

export interface User {
  id: number;
  name: string;
}

export interface Video {
  id: number;
  name: string;
  artist: string;
  album: string;
}

export interface LyricResult {
  id: number;
  name: string;
  artist: string;
}

/** 官方搜索首页 `hotWord` 中的一条热搜。 */
export interface HotSearchItem {
  keyword: string;
  type: number;
  icon: string;
  sort: number;
  searchType: number;
  jumpUrl: string;
}

/** `playbasic/music/v2/audioUrl` 的响应。`bitrate` 是真实码率，不是请求值。 */
export interface AudioData {
  audioUrl?: string;
  audioHttpsUrl?: string;
  format?: string;
  bitrate?: string;
  size?: string;
  duration?: number;
}

/** `service/music/info` 中声明的一档音频资源。 */
export interface AdvertisedAudio {
  format?: string;
  bitrate?: string | number;
  size?: string | number;
  playQuality?: string | number;
}

/**
 * 原生音频接口的调用结果。
 *
 * **刻意不返回 `AudioData | null`**：这个入口的失败原因只有业务码能区分，
 * 压成 `null` 之后「签名写错了」和「这首歌要付费」会变成同一件事，
 * 而这两种情况要做的事完全相反（改代码 vs 换账号/换歌）。
 *
 * `code` 为 `0` 表示请求根本没发出去（网络异常 / 超时），此时 `message` 是异常文案。
 */
export interface NativeAudioOutcome {
  audio: AudioData | null;
  code: number;
  message: string;
}

export interface MusicInfo {
  name?: string;
  artist?: string;
  album?: string;
  /** 封面绝对地址。 */
  cover?: string;
  duration?: number;
  /** 歌词能力标记。`lrcx` 为 0 表示这首歌没有逐字歌词。 */
  lrcInfo?: { lrc?: number; lrcx?: number };
  /** 官方单曲信息接口声明的资源目录；是否有权限仍以取址接口的实际响应为准。 */
  audios?: AdvertisedAudio[];
}

export interface MvInfo {
  mid: number;
  name: string;
  coverUrl: string;
  highUrl: string;
  lowUrl: string;
  mvDuration: number;
  highP2pid: string;
  lowP2pid: string;
  highBitrate: number;
  lowBitrate: number;
}

/** LRCX 的一个逐字单元。 */
export interface LrcxWord {
  char: string;
  startMs: number;
  endMs: number;
}

/** LRCX 的一行。 */
export interface LrcxLine {
  lineMs: number;
  words: LrcxWord[];
}

/**
 * 账号凭据。
 *
 * 以 `uid` / `token` 两个请求头随每个请求发出，匿名时是空串。
 *
 * ## 实测结论（都会静默出错，值得记牢）
 *
 * 1. **`uid` 必须是纯数字。** 传非数字（例如 `"contract-uid"`）时，上游会拒绝下发
 *    **任何播放地址**，而搜索、单曲信息、歌词全部照常 —— 表现为「搜得到、放不出」。
 *    换任何 token 都救不回来。这是写入账号时就必须拦掉的形状错误。
 * 2. **`token` 的取值不被校验。** 乱码 token 配数字 uid 照样能取到地址，
 *    和匿名完全一样；`zp` 无损档匿名也能拿到真 FLAC。连签名都不校验（见 [signUrl]）。
 * 3. **`uid` 会被读取，`token` 会被忽略。** 所以「凭据完全无关」是错的表述，
 *    准确说法是「凭据的**有效性**不被校验，但字段会被读取」。
 *
 * 这组事实推翻了更早的一条判断：当时用「乱码 token + 非数字 uid」做对照，看到取不到
 * 地址就归因成「无效凭据比不带凭据更糟」；把两个变量拆开才看清，真正起作用的是
 * uid 的形状，与 token 无关。**做这类对照时一次只改一个变量。**
 *
 * ## 为什么仍然保留并接回凭据
 *
 * 曾经据「14/14 接口匿名与带凭据返回逐项相同」把播放链路改成纯匿名。
 * ⚠️ **那个对照只用了一个普通账号，不能推广成「所有账号都零收益」** ——
 * 账号权益（VIP、已购专辑）只可能通过凭据体现，换个账号结果就可能不同。
 * 所以凭据已接回播放链路（见 `KuwoClient.clientOf`），后台配的账号是真正生效的。
 *
 * 另外，客户端侧的播放鉴权（`AccessTokenGuard`）与这里是两件不相干的事：
 * 那是桃桃对自己的用户设的门，与酷我是否认账无关。
 */
export interface BodianConfig {
  token?: string;
  uid?: string;
}

/**
 * 登录结果。
 *
 * 刻意把上游的业务码带出来而不是返回 `null`：调用方需要区分
 * 「验证码不对」（管理员输错，回 4xx）和「上游异常」（回 502）。
 */
export type LoginOutcome =
  | { ok: true; token: string; uid: string }
  | { ok: false; code: number; message: string };

/**
 * 发送短信验证码的结果。
 *
 * 形状与 [LoginOutcome] 对齐，理由也相同：**把上游的业务码带出来，不压成布尔**。
 *
 * 上游拒绝发码的原因至少有四类，管理员的下一步动作完全不同：
 * - 号码未注册 / 不可用 → 换号；
 * - 发送过于频繁（刚发过、有冷却期）→ 等一会儿再点；
 * - 被风控拦截 → 换网络或联系上游；
 * - 上游服务异常 → 过会儿重试。
 *
 * 压成 `false` 之后上层只能猜一句话，实测就出现过「号码明明没问题，却提示确认号码可用」。
 *
 * 另外 [code] 为 `-1` 是一个**我们自己造出来的哨兵值**，代表「响应根本不是可解析的 JSON」，
 * 与上游真的返回业务码 `-1` 在语义上不同（前者通常意味着被网关/WAF 拦了）。
 * 这种时候 [message] 里会带上 HTTP 状态 —— 403/429 是「被拦」，502/503 是「上游挂了」，
 * 两者的处置完全不同，所以状态必须透出去而不是丢掉。
 */
export type SmsOutcome =
  | { ok: true }
  | { ok: false; code: number; message: string };

// ==================== 常量 ====================

const BASE_URL = "https://bd-api.kuwo.cn/api/";

/** LRCX 接口的 XOR 密钥。 */
const LRCX_KEY = Buffer.from("yeelion");

/** 手机号与登录 body 的 XOR 密钥。 */
const LOGIN_XOR_KEY = Buffer.from("bd2025@");

/**
 * 短信验证码登录的 `authType`。
 *
 * 线上真实验证码已验证值为 `1`。AOT 中写入的机器立即数 `2` 是 Dart Smi
 * 对整数 `1` 的 tagged representation；把它直接当业务值才会误判。
 * `authType=2` 会进入 QQ 授权分支并返回 `11008`。
 */
const SMS_LOGIN_AUTH_TYPE = 1;

/** 上游在「验证码本身不对」时回的业务码。用来区分「输错」与「上游故障」。 */
export const SMS_CODE_REJECTED = 11004;

/**
 * 「响应根本不可解析」的哨兵码。
 *
 * **这不是上游的业务码，是我们自己造的**，用来和「上游确实回了一个业务码」区分开。
 * 调用方拼错误文案时要按它分流，别把它当数字直接展示给管理员 ——
 * 「上游业务码 -1」比不说还糟。附带说明里会带上真实 HTTP 状态。
 */
export const UPSTREAM_UNPARSABLE = -1;

/**
 * 单次上游请求的超时。
 *
 * 原复刻版本没有任何超时，上游挂起会把调用方永久挂住 —— 搜索接口聚合多个音源时
 * 只要一个上游不响应，整个搜索就再也返回不了。
 */
const REQUEST_TIMEOUT_MS = 15_000;

/** 音频下载超时。比普通请求宽松：要留够整个文件的传输时间。 */
const DOWNLOAD_TIMEOUT_MS = 120_000;

/**
 * 请求档位到上游 `format` / `br` 参数的映射。
 *
 * ⚠️ **这份映射只服务于 [downloadSong] 那条链路，而它当前全项目零引用**
 * （`downloadSong` / `getAudioUrlBest` / `checkAvailability` / `getMvInfo` 都是
 * 原复刻版本遗留的死代码）。**播放链路真正在用的档位定义是
 * `kuwo.client.ts` 的 `KUWO_TIERS`**，要改档位请改那边。
 *
 * ⚠️ **`mp3hi: ["mp3", "320k"]` 拿不到 320k。** 实测 `br` 写成
 * `320k` / `320` / `320kbps` / `320000` / `320K` **全部**返回
 * `format: "mp3"`, `bitrate: 128`。想要 320k 得请求 `format=flac`
 * （会被上游降级成 mp3 320k）。别照抄这条映射。
 *
 * **无损档必须写 `["zp", "20000"]`，不能写 `["flac", "flac"]`。**
 * 实测 `format=flac&br=flac` 会被上游**静默降级成 mp3 320k**（响应里
 * `format: "mp3"`, `bitrate: 320`），而 `format=zp&br=20000` 才真的返回 FLAC
 * （响应 `format: "flac"`，前 4 字节 `fLaC`）。`["al", "2000"]` 等价。
 * 两个写法都不会报错，只看接口是否返回 200 根本区分不出来。
 */
const QUALITY_MAP: Record<string, [string, string]> = {
  aac: ["aac", "48kaac"],
  mp3lo: ["mp3", "128k"],
  mp3hi: ["mp3", "320k"],
  // `flac` 是调用方用的档位名；上游认的参数值是 `zp` / `20000`。
  flac: ["zp", "20000"],
  zp: ["zp", "20000"],
  best: ["zp", "20000"],
};

/**
 * 原生音频接口 `play/music/v2/audioUrl` 的地址。
 *
 * 与 `playbasic` 是**两条不同的入口**，不要互相替换参数：`br` 的形状、时间戳单位、
 * 签名算法、payload 形状四样全都不同，混用只会得到 `439` 或 `402`。
 *
 * ⚠️ **2026-09-19 用 blutter 反编译（`out-bodian`）复核的结论，接手前务必读：**
 *
 * - **这版 App 的 Dart 层根本不调用 `play/music/v2/audioUrl`。** 全树 grep 零命中，
 *   字符串池里只有 `play/music/v2/checkRight` 和 `playbasic/music/v2/audioUrl/baseMode`。
 *   App 真正的取址方式是：**播放地址直接内嵌在歌曲对象的 `audios` 列表里**
 *   （`base_song_data.dart` 的 `MusicAudio.fromJson` 逐条读 `audioUrl`/`format`/`bitrate`），
 *   付费权限走 `play/music/v2/checkRight`（POST），免费模式走 `.../audioUrl/baseMode`。
 * - 反编译里也**没有任何 Dart 层的 `KpkUtil`**（见 [kpk.util]）。
 *
 * **但这不等于本方法是臆造的、可以删：**
 * 1. blutter 只反编译 Dart，`libkpk.so` 是 arm64 原生库，**本来就不会出现在 Dart dump 里** ——
 *    在 `out-bodian` 里找不到 KpkUtil 是预期的，不构成「不存在」的证据。
 * 2. 服务端接口是否接受这个 endpoint 是**上游行为**，与「这版 App 是否调用它」是两件事；
 *    本方法保留的依据是 [kpk.util] 里记录的**实网对照**（439→20018→200 的业务码前进）。
 *
 * 结论：保留本路径，但把它当成「实测有效、但当前 App 版本不走」的通道看待。
 * 若日后要提升取到地址的比例，**更该先做的是读内嵌 `audios`**（等实网核验，见
 * `KuwoClient.searchSongs` / `requestSongInfo` 的 TODO），而不是在这条原生路径上加码。
 */
const NATIVE_AUDIO_URL = `${BASE_URL}play/music/v2/audioUrl`;

/**
 * 原生请求的 `User-Agent` 与 `ver` 字段。
 *
 * **必须与通用接口的 `okhttp/3.12.2` 区分开**：通用接口走的是 H5 网关，
 * 原生播放接口认的是 App 自己的 UA。用错 UA 不一定立刻报错，
 * 但请求形状就与 App 不一致了，排查时先看这里。
 *
 * 版本号对应 APK `cn.wenyu.bodian` 5.9.1（versionCode 476）。
 */
const NATIVE_CLIENT_VERSION = "5.9.1";
const NATIVE_USER_AGENT = `BoDianMusic/${NATIVE_CLIENT_VERSION} (Android; cn.wenyu.bodian)`;

/**
 * 原生请求头里 `payload` 用的设备标识。
 *
 * **实测它不影响授权结果**：用四个不同的 `devId`（含随机十六进制）请求同一首歌，
 * 业务码逐字相同。所以服务端用固定值即可，不必模拟真实设备。
 *
 * 仍然做成可覆盖（`BODIAN_DEVICE_ID`），是因为「不影响」这个结论只在**当前账号**
 * 上验证过。真出现设备维度的权益（例如渠道赠送的机型专属会员）时，
 * 应当把它改成与开通权益那台设备一致的值，而不是改代码。
 */
const NATIVE_DEVICE_ID =
  process.env.BODIAN_DEVICE_ID || crypto.createHash("md5").update("taotao-music-server").digest("hex");

/**
 * 把 `playbasic` 的 `br` 形状翻译成原生入口的形状。
 *
 * 原生入口的 `br` 是「码率数字 + 字母 k + 格式」直接连接（`128kmp3`、`2000kflac`），
 * 而 `playbasic` 用 `128k`、`320k`、`20000`。规则：**`br` 已经以格式结尾就原样用**，
 * 否则把格式接上去。这样 `48kaac` 不会被拼成 `48kaacaac`。
 *
 * 注意 `flac` 档的 `br` 是 `20000` 而原生要 `2000kflac` —— 光靠拼接凑不出来，
 * 所以那几组写在 [NATIVE_QUALITY_MAP] 里。
 */
export function nativeBr(br: string, format: string): string {
  return br.toLowerCase().endsWith(format.toLowerCase()) ? br : `${br}${format}`;
}

/**
 * 两个入口的 (format, br) 对照表。
 *
 * 只列**确实会走到的**几组（即 [QUALITY_MAP] 的全部取值）。查不到时退回
 * [nativeBr] 的拼接规则，所以新增档位不会静默签出空 `br`。
 */
const NATIVE_QUALITY_MAP: Record<string, [string, string]> = {
  "aac/48kaac": ["aac", "48kaac"],
  "mp3/128k": ["mp3", "128kmp3"],
  "mp3/320k": ["mp3", "320kmp3"],
  // `zp/20000` 是 `playbasic` 的无损档；原生入口认 `2000kflac`。
  "zp/20000": ["flac", "2000kflac"],
};

/** 把 `playbasic` 的档位换算成原生入口的 `(format, br)`。 */
export function nativeQuality(format: string, br: string): [string, string] {
  return NATIVE_QUALITY_MAP[`${format}/${br}`] ?? [format, nativeBr(br, format)];
}

/**
 * 原生音频接口的业务码。**这些码是排查取址失败的唯一线索**，不要压成 `null`。
 *
 * | 码 | 含义 | 说明 |
 * | --- | --- | --- |
 * | `402` | `request invalid` | `payload` 头缺失或形状不对 |
 * | `433` | `param error` | 有 `payload` 但没有 `sign` |
 * | `439` | `sign invalid` | 用了通用 MD5 签名（这个入口只认 KPK） |
 * | `20012` | 歌曲已下线 | 签名没问题，是曲库状态 |
 * | `20018` | 没有解锁付费歌曲 | 签名没问题，是账号权益 |
 *
 * 判据是**签名是否通过**：拿到 `20012` / `20018` 就说明 KPK 已经生效，
 * 再调签名是白费力气，要去查账号或换歌。
 */
const NATIVE_AUDIO_CODES: Record<number, string> = {
  402: "request invalid",
  433: "param error",
  439: "sign invalid",
  20012: "歌曲已下线",
  20018: "没有解锁付费歌曲",
};

/** 文件名里不能出现的字符。**只过滤真正非法的那些**，中文要原样保留。 */
const ILLEGAL_FILENAME_CHARS = /[\\/:*?"<>|\u0000-\u001f]/g;

// ==================== BodianClient ====================

export class BodianClient {
  private token: string;
  private uid: string;

  constructor(config: BodianConfig = {}) {
    this.token = config.token || "";
    this.uid = config.uid || "";
  }

  // ---------- 工具函数 ----------

  /**
   * URL 签名。
   *
   * 取整条 URL 去掉所有非字母数字字符后排序，前后各拼一个固定盐再取 md5。
   *
   * 实测这个 `sign` 头**并不被上游校验**：故意传一个固定错误值，搜索与播放接口
   * 仍然返回 code=200。保留它的意义只在于别的接口将来可能开始校验。
   */
  private signUrl(url: string): string {
    const clean = url.replace(/[^a-zA-Z0-9]/g, "");
    const sorted = clean.split("").sort().join("");
    return crypto.createHash("md5").update(`kuwotest${sorted}${url}`).digest("hex");
  }

  private signBody(bodyStr: string): string {
    return crypto.createHash("md5").update(bodyStr + "kuwotest").digest("hex");
  }

  /** 官方通用 API 签名：query 归一化 + bodyHash + absolutePath。 */
  private officialSign(unsignedUrl: string, body = ""): string {
    const parsed = new URL(unsignedUrl);
    const normalized = parsed.search.slice(1).replace(/[^a-zA-Z0-9]/g, "").split("").sort().join("");
    const bodyHash = body
      ? crypto.createHash("md5").update(body + "kuwotest", "utf8").digest("hex")
      : "";
    return crypto.createHash("md5")
      .update(`kuwotest${normalized}${bodyHash}${parsed.pathname}`, "utf8")
      .digest("hex");
  }

  /** 搜索、登录、账号与资源接口共用的官方设备头。 */
  private officialHeaders(): Record<string, string> {
    const payload = this.xorEncode(JSON.stringify({
      devId: NATIVE_DEVICE_ID,
      ver: NATIVE_CLIENT_VERSION,
      plat: "ar",
      net: "wifi",
      channel: "wenyu",
      qimei36: "",
      ultraHD: 0,
      h265supported: 0,
      svrVer: 50,
      currentOsVersion: 35,
    })).toString("base64");
    return {
      Accept: "application/json",
      client: "android",
      "X-Requested-With": "cn.wenyu.bodian",
      "api-ver": "2",
      payload,
      "User-Agent": NATIVE_USER_AGENT,
      "Content-Type": "application/json",
    };
  }

  private officialUrl(url: string, params?: Record<string, string | number>, body = ""): string {
    const parsed = new URL(url);
    for (const [key, value] of Object.entries(params ?? {})) parsed.searchParams.append(key, String(value));
    parsed.searchParams.append("uid", this.uid || "-1");
    parsed.searchParams.append("token", this.token);
    parsed.searchParams.append("timestamp", Math.floor(Date.now() / 1000).toString());
    const unsigned = parsed.toString();
    parsed.searchParams.append("sign", this.officialSign(unsigned, body));
    return parsed.toString();
  }

  private baseHeaders(): Record<string, string> {
    return {
      appid: "100005",
      "api-ver": "2",
      "User-Agent": "okhttp/3.12.2",
      Accept: "application/json",
      Origin: "https://h5app.kuwo.cn",
      Referer: "https://h5app.kuwo.cn/",
      "Content-Type": "application/json",
      uid: this.uid,
      token: this.token,
      ts: Math.floor(Date.now() / 1000).toString(),
      nc: crypto.randomBytes(8).toString("hex"),
    };
  }

  /**
   * 拼装 query。
   *
   * **原复刻版本直接把值插进 `${k}=${v}`，这是一个会静默出错的缺陷**：
   * 关键字里出现 `&` 时，后面的部分会被上游解析成另一个参数，
   * 于是「A & B」被当成「A 」去搜 —— 返回一批无关结果，且不报错。
   * 实测：传「A & B 测试」时原始写法返回的是搜索「A 」的结果。
   *
   * 现在值一律交给 `URLSearchParams` 编码。签名仍按编码后的 URL 计算，
   * 与真正发出去的请求保持一致（由于上游不校验签名，这一点不影响行为）。
   */
  private buildUrl(url: string, params?: Record<string, string | number>): string {
    if (!params) return url;
    const query = new URLSearchParams(
      Object.entries(params).map(([key, value]) => [key, String(value)] as [string, string]),
    ).toString();
    return `${url}?${query}`;
  }

  /**
   * 带签名的 GET，只返回响应体。
   *
   * 多数调用方只关心 body，所以保留这个签名；需要区分
   * 「上游给了业务码」和「响应根本不可解析」的调用方用 [signedGetMeta]。
   */
  private async signedGet(url: string, params?: Record<string, string | number>): Promise<any> {
    return (await this.signedGetMeta(url, params)).data;
  }

  /** `playbasic` 属于旧 H5 网关，仍使用 uid/token 请求头和旧 MD5 头签名。 */
  private async legacySignedGet(url: string, params?: Record<string, string | number>): Promise<any> {
    const fullUrl = this.buildUrl(url, params);
    const response = await fetch(fullUrl, {
      headers: { ...this.baseHeaders(), sign: this.signUrl(fullUrl) },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    return this.parseJson(response);
  }

  /**
   * 带签名的 GET，**连 HTTP 状态一起返回**。
   *
   * 为什么需要状态：[parseJson] 在响应不是 JSON 时返回 `{}`，
   * 于是「业务码」变成 `NaN` —— 但**被网关/WAF 拦成 403/429** 和
   * **上游自己 502** 是两回事，光看 body 完全分不出来。
   * 排查短信发码失败时就吃过这个亏：一句「上游没接受」把
   * 「号码有问题」和「我们被拦了」混成了同一句话。
   */
  private async signedGetMeta(
    url: string,
    params?: Record<string, string | number>,
  ): Promise<{ status: number; data: any }> {
    const fullUrl = this.officialUrl(url, params);
    const response = await fetch(fullUrl, {
      headers: this.officialHeaders(),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    return { status: response.status, data: await this.parseJson(response) };
  }

  /**
   * 解析 JSON 响应。
   *
   * 上游偶尔在响应体前面带几个换行（实测普通歌词接口返回 `\r\n\r\n\r\n{...}`），
   * 直接 `response.json()` 在有些运行时上会失败。这里统一从第一个 `{` 开始截取。
   */
  private async parseJson(response: Response): Promise<any> {
    const text = await response.text();
    const start = text.indexOf("{");
    if (start < 0) return {};
    try {
      return JSON.parse(text.slice(start));
    } catch {
      return {};
    }
  }

  private xorDecode(data: Buffer, key: Buffer = LRCX_KEY): Buffer {
    const output = Buffer.alloc(data.length);
    for (let i = 0; i < data.length; i++) {
      output[i] = data[i] ^ key[i % key.length];
    }
    return output;
  }

  /**
   * 手机号加密。
   *
   * 返回**未做百分号编码**的 base64 —— 编码由 [buildUrl] 统一完成。
   * 原复刻版本在这里先 `encodeURIComponent` 一次，配合修复后的参数编码
   * 会变成双重编码，上游解不出来。
   */
  private encryptMobile(phone: string): string {
    return this.xorEncode(phone).toString("base64");
  }

  /**
   * 验证码加密。
   *
   * 与手机号是**同一套** XOR + base64，只是字段名不同（`encvVerifyCode`）。
   * 反汇编里 `ApiUrl.encryptVerifyCode` 与 `encryptMobile` 的实现体一模一样，
   * 都是 `base64(XOR(明文))`，区别只在返回字典的键。
   *
   * 原复刻版本把验证码**明文**塞进 `code` 字段，等于同时错了键名和加密 ——
   * 上游只会回「验证码错误」，看不出到底是哪个环节不对。
   */
  private encryptVerifyCode(code: string): string {
    return this.xorEncode(code).toString("base64");
  }

  private xorEncode(dataStr: string): Buffer {
    const data = Buffer.from(dataStr, "utf-8");
    const encrypted = Buffer.alloc(data.length);
    for (let i = 0; i < data.length; i++) {
      encrypted[i] = data[i] ^ LOGIN_XOR_KEY[i % LOGIN_XOR_KEY.length];
    }
    return encrypted;
  }

  /**
   * 带签名的 JSON POST。
   *
   * **不要套 `{data, encrypt, method}` 信封。** 那层信封是原复刻版本自己加的，
   * 上游并不认：实测任何走信封的请求（无论 `method` 是 `null`、`"post"` 还是不带）
   * 都返回 HTTP 500 + `{"code":-1,"msg":"Service error"}` —— 一个**看起来像上游故障**、
   * 实际是请求形状不对的错误。把同一个 JSON 直接当 body 发，上游立刻正常解析并回业务码。
   *
   * 字段的加密是**逐字段**的（见 [encryptMobile] / [encryptVerifyCode]），不是整体加密。
   * 整体 XOR + base64 那套只用于 App 的 `payload` 请求头，本客户端不发那个头
   * （实测不带也能通）。
   */
  private async signedPost(url: string, bodyDict: Record<string, unknown>): Promise<any> {
    return (await this.signedPostMeta(url, bodyDict)).data;
  }

  /** 带签名的 POST，连 HTTP 状态一起返回。理由见 [signedGetMeta]。 */
  private async signedPostMeta(
    url: string,
    bodyDict: Record<string, unknown>,
  ): Promise<{ status: number; data: any }> {
    const body = JSON.stringify(bodyDict);
    const response = await fetch(this.officialUrl(url, undefined, body), {
      method: "POST",
      headers: this.officialHeaders(),
      body,
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    return { status: response.status, data: await this.parseJson(response) };
  }

  /**
   * GBK 解码。
   *
   * **原复刻版本按 UTF-8 解码 LRCX，导致所有中文歌词变成 `\uFFFD`。**
   * 实测 LRCX 正文是 GBK：同一段字节按 UTF-8 解出 `[ti:������]`，
   * 按 GBK 解出 `[ti:七里香]`。
   *
   * 用 Node 内置的 `TextDecoder` 而不是引入 `iconv-lite`：后者目前只是
   * 传递依赖，不该被直接引用。Node 20+ 的官方构建都带 full-icu，支持 gbk。
   * small-icu 构建下这里会抛错而不是静默退回 UTF-8 —— 乱码歌词比报错更难发现。
   */
  private decodeGbk(data: Buffer): string {
    try {
      return new TextDecoder("gbk").decode(data);
    } catch {
      throw new Error("当前 Node 运行时不支持 GBK 解码（需要 full-icu 构建）");
    }
  }

  // ---------- 登录 ----------

  /**
   * 发送登录短信验证码。
   *
   * **参数名是 `encvMobile` 而不是 `phone`** —— 这是从 APK 反汇编里核出来的
   * （`ApiUrl.encryptMobile` 返回的就是 `{"encvMobile": <加密串>}`）。
   * 原复刻版本写成 `phone`，上游不认，直接返回 `11003 短信发送失败`：
   * 一个看不出是「参数名写错」的错误，很容易被误判成号码未注册或需要风控。
   *
   * 手机号值的编码：App 里是 `Uri.encodeComponent(base64)`，等价于把裸 base64
   * 交给 [buildUrl] 统一做百分号编码（base64 的 `+` `/` `=` 两边都会编码，
   * 其余字符集一致），所以这里传裸 base64。
   *
   * 返回值**带上上游的业务码，不压成布尔** —— 理由同 [loginSms]：
   * 上游拒绝的原因可能是号码未注册、发送过于频繁、被风控拦截、或服务本身异常，
   * 它们对管理员的下一步动作完全不同。压成 `false` 之后上层只能猜，
   * 实测就出现过「明明号码没问题却提示确认号码可用」的误导。
   *
   * 用 [signedGetMeta] 而不是 [signedGet]：响应不可解析时还要报出 HTTP 状态。
   */
  async sendSms(phone: string): Promise<SmsOutcome> {
    const url = `${BASE_URL}ucenter/code/sendsms`;
    // query 由 URLSearchParams 编码一次；这里保留裸 base64，避免 `%3D` 变 `%253D`。
    const encvMobile = this.encryptMobile(phone);
    const params = { type: "2", encvMobile };
    const fullUrl = this.officialUrl(url, params);
    const response = await fetch(fullUrl, {
      method: "GET",
      headers: this.officialHeaders(),
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    const status = response.status;
    const d = await this.parseJson(response);
    const code = Number(d?.code);
    if (code === 200) return { ok: true };
    // [parseJson] 在响应不是 JSON 时返回 `{}`，`code` 就成了 NaN。
    // 这和「上游给了一个业务码」是两回事，报成业务码（哪怕是 0）会把排查方向带偏。
    // 把 HTTP 状态一并带出去：「被网关/WAF 拦成 403/429」与「上游自己 502」
    // 是两种完全不同的处置（换网络/降频 vs 等上游恢复），不能混成一句话。
    if (!Number.isFinite(code)) {
      return {
        ok: false,
        code: UPSTREAM_UNPARSABLE,
        message: `HTTP ${status}，响应体不是 JSON（多半被网关/WAF 拦截，或上游异常）`,
      };
    }
    return { ok: false, code, message: String(d?.msg ?? d?.message ?? "").trim() };
  }

  /**
   * 用短信验证码登录，成功后凭据留在本实例上。
   *
   * 请求形状（全部实测确定，不是从反汇编猜的）：
   *
   * - 传输：[signedPost] 的**明文 JSON**，不套信封（信封一律 500 Service error）。
   * - `authType` 取 [SMS_LOGIN_AUTH_TYPE]（`1`）。
   * - 字段名 `encvMobile` / `encvVerifyCode`，值都是 `base64(XOR(明文))`。
   * - 响应里的用户标识字段叫 **`id`，不叫 `uid`**；token 在 `data.token`。
   *
   * 失败时**把上游的业务码原样带回去**，而不是压成 `null`：
   * 「验证码错误」（`11004`）是管理员自己输错或超时，该回 4xx 让前端提示重新获取；
   * 「`-1` Service error」这类是请求/上游出了问题，该回 502。原实现把两者都压成
   * 一句「验证码错误」，等于让管理员对着一个自己没输错的码反复重试。
   *
   * 与 [sendSms] 同样用 [signedPostMeta]：响应不可解析时 `code` 会是 `NaN`，
   * 直接带上去会变成「酷我登录失败：NaN」—— 既不像业务码也不像故障，
   * 所以这里换成哨兵值 `-1` 并附上 HTTP 状态。
   */
  async loginSms(phone: string, code: string): Promise<LoginOutcome> {
    const url = `${BASE_URL}ucenter/users/login`;
    const { status, data: d } = await this.signedPostMeta(url, {
      authType: SMS_LOGIN_AUTH_TYPE,
      encvMobile: encodeURIComponent(this.encryptMobile(phone)),
      encvVerifyCode: encodeURIComponent(this.encryptVerifyCode(code)),
    });
    const upstreamCode = Number(d?.code);
    if (!Number.isFinite(upstreamCode)) {
      return {
        ok: false,
        code: UPSTREAM_UNPARSABLE,
        message: `HTTP ${status}，响应体不是 JSON（多半被网关/WAF 拦截，或上游异常）`,
      };
    }
    if (upstreamCode !== 200) {
      return { ok: false, code: upstreamCode, message: String(d.msg ?? "") };
    }
    // 用户标识字段是 `id`；兼容性地也读一下 `uid`，但主路径是 `id`。
    this.token = String(d.data?.token ?? "");
    this.uid = String(d.data?.id ?? d.data?.uid ?? "");
    if (!this.token || !this.uid) {
      return { ok: false, code: upstreamCode, message: "上游返回成功但没有 token / id" };
    }
    return { ok: true, token: this.token, uid: this.uid };
  }

  /** 强鉴权：有效 token 为 200，无效 token/设备上下文为 -101。 */
  async validateCredentials(): Promise<{ ok: boolean; code: number; message: string }> {
    if (!this.uid || !this.token) return { ok: false, code: -101, message: "缺少 uid/token" };
    const d = await this.signedGet(`${BASE_URL}service/resource/userCount`, { userId: this.uid });
    const code = Number(d?.code);
    return {
      ok: code === 200,
      code: Number.isFinite(code) ? code : UPSTREAM_UNPARSABLE,
      message: String(d?.msg ?? d?.message ?? ""),
    };
  }

  setCredentials(token: string, uid: string): void {
    this.token = token;
    this.uid = uid;
  }

  /**
   * 当前是否持有账号凭据。
   *
   * 给播放链路的档位阶梯用：原生入口**匿名时对所有歌都回 `20018`**，
   * 没有凭据还去试只是白等一次超时。注意判据是「有没有配」而不是「配得对不对」——
   * `uid` 形状写坏属于要暴露的问题，不该被这里静默跳过。
   */
  hasCredentials(): boolean {
    return this.uid !== "";
  }

  // ---------- 搜索 ----------

  /**
   * 把「第几页（1 基）」换算成上游的 `pn`。
   *
   * ⚠️ **上游的 `pn` 是 0 基的，而且偏移量是 `pn × rn`。**
   * 参考实现（`server/bodian/index.ts`）写的是 `pn: page`，本项目照抄了，
   * 于是**第一页实际拿到的是第二页的歌** —— 这是「搜索结果和波点 App 不一样」的根因。
   *
   * 实测（2026-09-19，关键词「周杰伦」，`search/music/list`），
   * 「首条在真实结果序列中的位置」：
   *
   * | pn | rn | 位置 |
   * | --- | --- | --- |
   * | 0 | 20 / 40 / 60 | **第 0 位** |
   * | 1 | 20 | 第 20 位 |
   * | 1 | 40 | 第 40 位 |
   * | 1 | 60 | 第 60 位 |
   * | 2 | 20 | 第 40 位 |
   * | 3 | 20 | 第 60 位 |
   *
   * 即 **`offset = pn × rn`**。
   *
   * ⚠️ 推论：**`rn` 必须跨页保持一致**。`rn` 一变，同一个 `pn` 指向的位置就变了，
   * 翻页会出现空洞或重叠。调用方要固定 `size`。
   *
   * 判据是波点 App 自己（blutter 逆向 `out-bodian`）：
   * `search_result_default_widget` / `search_result_single_widget` 里发的是
   * `pn: 0` 且 `rn = 已加载条数 + 20`；App 综合页 `search/comprehensive/v2/list`
   * 的 `musicpage` 与 `search/music/list?pn=0&rn=20` **逐条一致**。
   * 修复后我们与 App 的列表完全对齐 —— 「周杰伦」前 30 条、「七里香」前 7 条
   * 与 App 综合页逐条相同，**修复前重合 0 条**。
   */
  private upstreamPage(page: number): number {
    // 负数会让上游返回不可预期的结果（实测 pn=0 与 pn=-1 都落到同一段），
    // 这里统一收敛到 0，避免调用方传 0 时翻出别的东西。
    return Math.max(0, page - 1);
  }

  async searchSongs(keyword: string, page: number = 1, size: number = 5): Promise<Song[]> {
    const d = await this.signedGet(`${BASE_URL}search/music/list`, {
      pn: this.upstreamPage(page),
      rn: size,
      keyword,
    });
    return (d.data?.resultList || []).map((s: any) => ({
      id: Number(s.id),
      name: s.songName || s.name || "",
      artist: s.artist || "",
      album: s.album || "",
      cover: s.albumPic || "",
      durationSeconds: Number(s.duration) || 0,
      subtitle: s.subtitle || "",
      // listen_fragment 是匿名/试听权限，不是“歌曲已下线”。会员账号应允许进入
      // 原生权益取址，由 audioUrl 的业务码给出最终结论。
      playable: this.hasCredentials() || String(s.payInfo?.listen_fragment ?? "0") !== "1",
    }));
  }

  async searchArtists(keyword: string, page: number = 1, size: number = 5): Promise<Artist[]> {
    const d = await this.signedGet(`${BASE_URL}search/artist/list`, { pn: this.upstreamPage(page), rn: size, keyword });
    return (d.data?.resultList || []).map((s: any) => ({
      id: s.artistId,
      name: s.name,
      songs: s.songNum || 0,
      albums: s.albumNum || 0,
    }));
  }

  async searchAlbums(keyword: string, page: number = 1, size: number = 5): Promise<Album[]> {
    const d = await this.signedGet(`${BASE_URL}search/album/list`, { pn: this.upstreamPage(page), rn: size, keyword });
    return (d.data?.resultList || []).map((s: any) => ({
      id: s.albumId || s.id,
      name: s.name,
      artist: s.artist,
    }));
  }

  async searchPlaylists(keyword: string, page: number = 1, size: number = 5): Promise<Playlist[]> {
    const d = await this.signedGet(`${BASE_URL}search/playlist/list`, { pn: this.upstreamPage(page), rn: size, keyword });
    return (d.data?.resultList || []).map((s: any) => ({ id: s.id, name: s.name }));
  }

  async searchUsers(keyword: string, page: number = 1, size: number = 5): Promise<User[]> {
    const d = await this.signedGet(`${BASE_URL}search/user/list`, { pn: this.upstreamPage(page), rn: size, keyword });
    return (d.data?.resultList || []).map((s: any) => ({ id: s.id, name: s.name }));
  }

  async searchVideos(keyword: string, page: number = 1, size: number = 5): Promise<Video[]> {
    const d = await this.signedGet(`${BASE_URL}search/video/list`, { pn: this.upstreamPage(page), rn: size, keyword });
    return (d.data?.resultList || []).map((s: any) => ({
      id: s.id,
      name: s.songName || s.name || "",
      artist: s.artist || "",
      album: s.album || "",
    }));
  }

  async searchLyrics(keyword: string, page: number = 1, size: number = 5): Promise<LyricResult[]> {
    const d = await this.signedGet(`${BASE_URL}search/lyric/list`, { pn: this.upstreamPage(page), rn: size, keyword });
    return (d.data?.resultList || []).map((s: any) => ({
      id: s.rid || s.id,
      name: s.songName || "",
      artist: s.artist || "",
    }));
  }

  async searchTips(keyword: string, size: number = 10): Promise<string[]> {
    // 波点 5.9.1 的 SearchKeywordWidget 实际只传 keyword 与 tipsFrom=bd，
    // 没有 pn/rn。条数限制在本地执行，避免自行扩展协议导致排序或召回漂移。
    const d = await this.signedGet(`${BASE_URL}search/tip/v2/list`, { keyword, tipsFrom: "bd" });
    return (d.data?.resultList || [])
      .map((s: any) => String(s?.relword ?? "").trim())
      .filter((word: string) => word.length > 0)
      .slice(0, size);
  }

  async searchHotKeywords(size: number = 20): Promise<HotSearchItem[]> {
    // 波点 5.9.1 搜索首页直接 GET 此端点，不传业务参数；热搜位于 data.hotWord。
    const d = await this.signedGet(`${BASE_URL}search/topic/word/list`);
    return (d.data?.hotWord || [])
      .map((item: any) => ({
        keyword: String(item?.key ?? "").trim(),
        type: Number(item?.type) || 0,
        icon: String(item?.icon ?? ""),
        sort: Number(item?.sort) || 0,
        searchType: Number(item?.searchType) || 0,
        jumpUrl: String(item?.jumpUrl ?? ""),
      }))
      .filter((item: HotSearchItem) => item.keyword.length > 0)
      .slice(0, size);
  }

  async search(type: string, keyword: string, page: number = 1, size: number = 5): Promise<any[]> {
    const map: Record<string, (kw: string, p: number, s: number) => Promise<any[]>> = {
      music: this.searchSongs.bind(this),
      artist: this.searchArtists.bind(this),
      album: this.searchAlbums.bind(this),
      playlist: this.searchPlaylists.bind(this),
      user: this.searchUsers.bind(this),
      video: this.searchVideos.bind(this),
      lyric: this.searchLyrics.bind(this),
    };
    if (type === "tip") return this.searchTips(keyword, size);
    return map[type]?.(keyword, page, size) || [];
  }

  // ---------- 音频与歌词 ----------

  async getMusicInfo(musicId: number | string): Promise<MusicInfo | null> {
    const d = await this.signedGet(`${BASE_URL}service/music/info`, { musicId });
    if (Number(d.code) !== 200) return null;
    return {
      name: d.data?.songName || d.data?.name || "",
      artist: d.data?.artist || "",
      album: d.data?.album || "",
      cover: d.data?.albumPic || "",
      duration: Number(d.data?.duration) || 0,
      lrcInfo: d.data?.lrc_info,
      audios: Array.isArray(d.data?.audios) ? d.data.audios : [],
    };
  }

  /**
   * 取音频地址。
   *
   * 上游**不会因为请求了高码率就给高码率**，而且降级方向很不直观：
   * `mp3/320k`（`br` 怎么写都一样）拿到 **128k mp3**；
   * `flac/*` 拿到 **320k mp3**；只有 `zp/20000`（或 `al/2000`）才是真 FLAC。
   * 三者都不报错，某些曲目还会被统一降级到 128k。
   *
   * 所以真实码率只能看响应里的 `format` / `bitrate`，调用方必须按它回填，
   * 不能按请求值上报 —— 实测表见 `kuwo.client.ts` 的 [KUWO_TIERS]。
   */
  async getAudioUrl(musicId: number | string, fmt: string = "aac", br: string = "48kaac"): Promise<AudioData | null> {
    const d = await this.legacySignedGet(`${BASE_URL}playbasic/music/v2/audioUrl`, { musicId, format: fmt, br });
    // 判据是「有没有可用地址」，而不是「有没有 `audioUrl`」。上游对部分歌曲（尤其
    // https-only CDN）只回 `audioHttpsUrl`，`audioUrl` 为空 —— 此时这首歌明明能放，
    // 只看 `audioUrl` 会误判成「取不到地址」，整条阶梯落空后 resolveLink 报 502。
    // 所有调用方（accept / probeCredential / download）读的都是 `audioHttpsUrl || audioUrl`，
    // 这里必须与它们保持一致。
    if (Number(d.code) === 200 && (d.data?.audioUrl || d.data?.audioHttpsUrl)) return d.data as AudioData;
    return null;
  }

  /**
   * 原生入口取址：`play/music/v2/audioUrl`。
   *
   * 这是波点 App 真正用来播歌的入口，也是**唯一带账号权益**的取址通道。
   * 与 [getAudioUrl] 的四点区别，混用任何一点都会拿到 `439` 或 `402`：
   *
   * | 项目 | `playbasic` | 本方法 |
   * | --- | --- | --- |
   * | 签名 | 通用 MD5，放 `sign` 请求头 | KPK，放 `sign` query 参数（见 [kpk.util]） |
   * | 时间戳 | 秒 | **毫秒** |
   * | `br` | `128k` / `320k` / `20000` | `128kmp3` / `320kmp3` / `2000kflac` |
   * | `payload` 头 | 不发 | **必须发**，且带 `uid`/`token` |
   *
   * `payload` 是这里唯一非可选的头：缺它上游一律回 `402 request invalid`，
   * 而不是回一个「没有凭据」之类的业务码。形状与通用 payload 也不同 ——
   * 原生 payload **含账号字段**，且不含 `ultraHD/h265supported/svrVer/currentOsVersion`。
   *
   * `br` 传 `playbasic` 的形状即可，内部按 [nativeQuality] 换算。
   *
   * **失败时不要重试签名**：拿到 `20012` / `20018` 说明 KPK 已经通过，
   * 再签一次结果一样，该做的是换账号或换歌。
   */
  async getAudioUrlNative(
    musicId: number | string,
    fmt: string = "mp3",
    br: string = "128k",
  ): Promise<NativeAudioOutcome> {
    const [nativeFormat, nativeBitrate] = nativeQuality(fmt, br);
    // 毫秒。用秒级时间戳会与签名一起被判 439 —— 这一点两个入口正好相反。
    const timestamp = Date.now();
    // 顺序固定为 uid/token/timestamp/devId/musicId/format/br/freeSign。
    // 顺序本身不参与签名（归一化会排序），但改变后必须重新签名，所以不要复用旧 sign。
    const query = [
      `uid=${this.uid}`,
      `token=${this.token}`,
      `timestamp=${timestamp}`,
      `devId=${NATIVE_DEVICE_ID}`,
      `musicId=${musicId}`,
      `format=${nativeFormat}`,
      `br=${nativeBitrate}`,
      "freeSign=",
    ].join("&");
    const unsigned = `${NATIVE_AUDIO_URL}?${query}`;
    const headers = {
      Accept: "application/json",
      client: "android",
      "X-Requested-With": "cn.wenyu.bodian",
      "api-ver": "2",
      payload: this.nativePayload().toString("base64"),
      "User-Agent": NATIVE_USER_AGENT,
    };

    try {
      const response = await fetch(`${unsigned}&sign=${kpkSign(unsigned)}`, {
        headers,
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
      const d = await this.parseJson(response);
      const code = Number(d.code);
      // 与 [getAudioUrl] 同理：只回 `audioHttpsUrl` 的歌也是能放的，不能只认 `audioUrl`。
      const audio = code === 200 && (d.data?.audioUrl || d.data?.audioHttpsUrl) ? (d.data as AudioData) : null;
      return {
        audio,
        code: Number.isFinite(code) ? code : 0,
        message: String(d.msg ?? "") || NATIVE_AUDIO_CODES[code] || "",
      };
    } catch (error) {
      // 网络层异常也走同一个返回形状，调用方不必分两套分支。
      return { audio: null, code: 0, message: error instanceof Error ? error.message : String(error) };
    }
  }

  /**
   * 原生请求头的 `payload`。
   *
   * 字段顺序与 App 一致（`devId/ver/plat/channel/net/uid/token/qimei36`），
   * 编码是 `Base64(XOR(UTF8(json), UTF8("bd2025@")))` —— 与登录用同一个密钥，
   * 所以直接复用 [xorEncode]。
   */
  private nativePayload(): Buffer {
    const json = JSON.stringify({
      devId: NATIVE_DEVICE_ID,
      ver: NATIVE_CLIENT_VERSION,
      plat: "ar",
      channel: "wenyu",
      net: "wifi",
      uid: this.uid,
      token: this.token,
      qimei36: "",
    });
    return this.xorEncode(json);
  }

  /** 按档位取地址，拿不到就沿固定阶梯降级。 */
  async getAudioUrlBest(musicId: number | string, quality: string = "best"): Promise<{ audio: AudioData; usedFmt: string; usedBr: string } | null> {
    const [fmt, br] = QUALITY_MAP[quality] || QUALITY_MAP.best;
    const audio = await this.getAudioUrl(musicId, fmt, br);
    if (audio) return { audio, usedFmt: fmt, usedBr: br };
    for (const [fbFmt, fbBr] of [["mp3", "128k"], ["aac", "48kaac"]]) {
      const fallback = await this.getAudioUrl(musicId, fbFmt, fbBr);
      if (fallback) return { audio: fallback, usedFmt: fbFmt, usedBr: fbBr };
    }
    return null;
  }

  /** 批量探测哪些歌能真的取到地址。会为每首歌发一次请求，慎用。 */
  async checkAvailability(musicIds: (number | string)[]): Promise<Map<number | string, boolean>> {
    const result = new Map<number | string, boolean>();
    await Promise.all(musicIds.map(async (id) => {
      result.set(id, !!(await this.getAudioUrl(id, "aac", "48kaac")));
    }));
    return result;
  }

  async getMvInfo(musicId: number | string): Promise<MvInfo | null> {
    const d = await this.signedGet(`${BASE_URL}service/mv/info`, { musicId });
    if (Number(d.code) === 200 && d.data?.mv) return d.data.mv;
    return null;
  }

  /**
   * 普通 LRC。拿不到或解析失败返回 null，不抛异常。
   *
   * ⚠️ 这个接口在 **`m.kuwo.cn`（老移动 Web 接口）**，不是波点 API，所以
   * **不走 [baseHeaders]、不带 `uid` / `token`** —— 那是另一套面向前端网页的接口，
   * 不接受波点凭据。实测刻意保留：它的响应体比 LRCX 更容易解析，
   * 作为 LRCX 拿不到时的兜底。别把它「顺手统一」到 `signedGet`，那会 404。
   */
  async getLyrics(musicId: number | string): Promise<string | null> {
    try {
      const url = `http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${musicId}&httpsStatus=1`;
      const response = await fetch(url, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36",
          Referer: "https://www.kuwo.cn/",
        },
        signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
      });
      const d = await this.parseJson(response);
      const lrclist = d?.data?.lrclist || [];
      if (!lrclist.length) return null;
      return lrclist.map((item: any) => {
        const t = parseFloat(item.time || "0");
        const mm = Math.floor(t / 60);
        const ss = t % 60;
        return `[${mm.toString().padStart(2, "0")}:${ss.toFixed(2).padStart(5, "0")}]${item.lineLyric || ""}`;
      }).join("\n");
    } catch {
      return null;
    }
  }

  /**
   * 逐字歌词（LRCX）。
   *
   * 链路：XOR 解密请求参数 → 响应剥掉 HTTP 头 → zlib 解压 → base64 解码 →
   * 再 XOR 解密 → **GBK 解码**。
   *
   * ⚠️ 和 [getLyrics] 一样，这个接口在 **`newlyric.kuwo.cn`（歌词 CDN）**，
   * 不是波点 API，所以**不带 `uid` / `token`**。实测确认过：接入凭据后，
   * 搜索与取址的请求头里有 uid/token，歌词这两个没有 —— 这是预期行为，不是漏传。
   */
  async getLrcxLyrics(musicId: number | string): Promise<string | null> {
    const paramsStr = `user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_${musicId}&lrcx=1`;
    const encoded = this.xorDecode(Buffer.from(paramsStr, "utf-8"));
    const url = `http://newlyric.kuwo.cn/newlyric.lrc?${encoded.toString("base64")}`;
    const response = await fetch(url, {
      headers: { "User-Agent": "Mozilla/5.0" },
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
    if (response.status !== 200) return null;
    const buffer = Buffer.from(await response.arrayBuffer());
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd < 0) return null;
    const body = buffer.slice(headerEnd + 4);
    let decompressed: Buffer;
    try {
      decompressed = inflateSync(body);
    } catch {
      // 正文不是 zlib 流（上游改协议或返回了错误页），当作没有逐字歌词。
      return null;
    }
    // base64 正文是纯 ASCII，按 latin1 取出即可，不要走 UTF-8 往返 ——
    // 那会在遇到非 ASCII 字节时引入替换字符，损坏后续的 base64 解码。
    const decoded = this.xorDecode(Buffer.from(decompressed.toString("latin1"), "base64"));
    return this.decodeGbk(decoded);
  }

  // ---------- LRCX 解析 ----------

  /**
   * 把 LRCX 解析成带逐字时间的行。
   *
   * 每个字后面跟着一对偏移量 `<o1,o2>`，它同时编码了起始时间和时长：
   *
   * ```
   * start    = (o1 + o2) / (offsetFactor  * 2)
   * duration = (o1 - o2) / (offset2Factor * 2)
   * ```
   *
   * 两个除数来自文件头的 `[kuwo:N]`：按**八进制**解析后，十位以上是起点的除数、
   * 个位是时长的除数（`[kuwo:125]` → 85 → 8 与 5），缺省 1 / 6。
   *
   * 这套公式已用实际数据验证：`[00:27.745]` 那一行解出
   * 窗 0+401、外 401+927、的 1328+370、麻 1698+174、雀 1872+1504、在 3376+417 ——
   * 首尾严格相接、单调递增，与行内文字宽度一致。同一行内各字时长相同是正常的，
   * 中文等宽，不是数据错误。
   *
   * 注意**不是所有歌都有逐字数据**：实测有的歌整份 LRCX 一个 `<…>` 都没有，
   * 此时解出来的每个字时间都等于行时间，调用方应按「只有行时间」处理。
   */
  parseLrcx(lrcxText: string): LrcxLine[] {
    if (!lrcxText) return [];
    let offsetFactor = 1, offset2Factor = 6;
    const linesOut: LrcxLine[] = [];
    // 正文是 CRLF 行尾，只按 \n 切会把 \r 留在行尾，混进最后一个字的文本里。
    for (const line of lrcxText.split(/\r?\n/)) {
      const kuwoMatch = line.match(/\[kuwo:(\d+)\]/);
      if (kuwoMatch) {
        const val = parseInt(kuwoMatch[1], 8);
        offsetFactor = Math.floor(val / 10) || 1;
        offset2Factor = (val % 10) || 1;
        continue;
      }
      const lineMatch = line.match(/\[(\d+):(\d+\.?\d*)\](.*)/);
      if (!lineMatch) continue;
      const lineMs = parseInt(lineMatch[1]) * 60000 + Math.floor(parseFloat(lineMatch[2]) * 1000);
      const content = lineMatch[3];
      const words: LrcxWord[] = [];
      const tokenPattern = /<(-?\d+),(-?\d+)>/g;
      const tokens = [...content.matchAll(tokenPattern)];
      if (tokens.length === 0) {
        for (const char of Array.from(content)) words.push({ char, startMs: lineMs, endMs: lineMs });
      } else {
        // 一个时间组后面可能跟多个字符，例如 `<720,-720>词：`。旧实现只取第一个
        // “词”，随后从“：”开始向后搜索下一组时间，却忽略 match.index，最终把
        // `<1804,356>` 错位拆成截图中的 `词>>>>春`。这里按时间组边界切完整文本段。
        for (let index = 0; index < tokens.length; index++) {
          const token = tokens[index];
          const segmentStart = (token.index ?? 0) + token[0].length;
          const segmentEnd = index + 1 < tokens.length ? (tokens[index + 1].index ?? content.length) : content.length;
          const chars = Array.from(content.slice(segmentStart, segmentEnd));
          if (chars.length === 0) continue;
          const o1 = Number(token[1]), o2 = Number(token[2]);
          const startMs = lineMs + Math.abs(o1 + o2) / (offsetFactor * 2);
          const endMs = startMs + Math.abs(o1 - o2) / (offset2Factor * 2);
          const duration = Math.max(0, endMs - startMs);
          chars.forEach((char, charIndex) => {
            words.push({
              char,
              startMs: startMs + duration * charIndex / chars.length,
              endMs: startMs + duration * (charIndex + 1) / chars.length,
            });
          });
        }
      }
      if (words.length > 0) linesOut.push({ lineMs, words });
    }
    return linesOut;
  }

  /** LRCX 回写成 `<起始,时长>` 形态，仅用于调试与核对。 */
  formatLrcx(parsed: LrcxLine[]): string {
    return parsed.map(({ lineMs, words }) => {
      const mm = Math.floor(lineMs / 60000);
      const ss = (lineMs % 60000) / 1000;
      let s = `[${mm.toString().padStart(2, "0")}:${ss.toFixed(2).padStart(5, "0")}]`;
      for (const { char, startMs, endMs } of words) {
        s += startMs !== endMs ? `<${startMs},${endMs - startMs}>${char}` : char;
      }
      return s;
    }).join("\n");
  }

  // ---------- 下载 ----------

  /**
   * 下载单个文件。
   *
   * 相比原复刻版本修掉了三处：不处理背压会让慢速磁盘 + 快速 CDN 把整个文件
   * 缓进内存；`end()` 不等待就返回会让调用方拿到一个还没写完的文件；
   * 失败路径不关句柄、不删半截文件，会留下一个看起来能播但实际损坏的音频。
   */
  async download(audioUrl: string, outputPath: string): Promise<boolean> {
    let fileStream: fs.WriteStream | undefined;
    try {
      const response = await fetch(audioUrl, {
        headers: { "User-Agent": "Mozilla/5.0", Referer: "https://h5app.kuwo.cn/", Accept: "*/*" },
        redirect: "follow",
        signal: AbortSignal.timeout(DOWNLOAD_TIMEOUT_MS),
      });
      if (!response.ok || !response.body) return false;
      // 目标目录不存在时 createWriteStream 会抛 ENOENT。
      await fs.promises.mkdir(path.dirname(outputPath), { recursive: true });
      fileStream = fs.createWriteStream(outputPath);
      for await (const chunk of response.body as unknown as AsyncIterable<Uint8Array>) {
        if (!fileStream.write(chunk)) await once(fileStream, "drain");
      }
      await new Promise<void>((done, fail) => {
        fileStream!.end((error?: Error | null) => (error ? fail(error) : done()));
      });
      return true;
    } catch {
      fileStream?.destroy();
      await fs.promises.rm(outputPath, { force: true }).catch(() => undefined);
      return false;
    }
  }

  /** 下载音频并顺手抓取同名的 `.lrc` 与 `.lrcx`。 */
  async downloadSong(musicId: number | string, outputDir: string, quality: string = "best"): Promise<string | null> {
    const info = await this.getMusicInfo(musicId);
    const result = await this.getAudioUrlBest(musicId, quality);
    if (!result) return null;
    const { audio } = result;
    const url = audio.audioHttpsUrl || audio.audioUrl;
    if (!url) return null;
    const extMap: Record<string, string> = { aac: "m4a", mp3: "mp3", flac: "flac", ogg: "ogg" };
    const ext = extMap[audio.format || ""] || "mp3";
    // 只过滤文件系统真正非法的字符，中文歌名必须原样保留。
    // 原复刻版本用的是 `[^a-zA-Z0-9 -_.]`，会把中文全部换成下划线，
    // 「晴天 - 周杰伦.flac」变成「__ - __.flac」，大量歌曲互相覆盖。
    const baseName = `${info?.name || musicId} - ${info?.artist || "未知歌手"}`
      .replace(ILLEGAL_FILENAME_CHARS, "_")
      // Windows 不允许文件名以点或空格结尾。
      .replace(/[. ]+$/, "")
      .trim() || String(musicId);
    const outputPath = path.join(outputDir, `${baseName}.${ext}`);
    if (!(await this.download(url, outputPath))) return null;
    const stem = outputPath.replace(/\.[^.]+$/, "");
    const lrc = await this.getLyrics(musicId);
    if (lrc) await fs.promises.writeFile(`${stem}.lrc`, lrc, "utf-8");
    const lrcx = await this.getLrcxLyrics(musicId);
    if (lrcx) await fs.promises.writeFile(`${stem}.lrcx`, lrcx, "utf-8");
    return outputPath;
  }
}

export default BodianClient;
