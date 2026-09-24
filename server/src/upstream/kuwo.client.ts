import { Injectable, Logger } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { BodianClient, SMS_CODE_REJECTED, UPSTREAM_UNPARSABLE } from "./bodian.client";
import type { LrcxLine, Song } from "./bodian.client";
import type {
  MusicSource,
  MusicSourceClient,
  MusicSourceCredentialManager,
  SongKey,
} from "./music-source.client";
import type { MusicSourceCredential } from "./music-source-account.repository";
import { MusicSourceAccountRepository } from "./music-source-account.repository";
import type { QualityTier, RichLyric, SearchResult, UpstreamLink, UpstreamSong, UpstreamSongInfo } from "./tencent.client";

/**
 * 酷我（波点）音源适配。
 *
 * 把 [BodianClient] 的协议能力翻译成桃桃音乐的歌曲模型。**协议本身不在这个文件里** ——
 * 签名、加密、编码还原都在传输层，换协议不影响这里的业务映射。
 *
 * 接入前实测出的几条约束决定了下面大部分实现，逐条说明见各自方法的注释：
 *
 * 1. **档位会被静默降级，且降级方向反直觉**：`mp3/320k` 恒为 128k，
 *    `flac` 反而能拿到 320k，`zp` 才是真 FLAC。实测表见 [KUWO_TIERS]。
 *    音质必须按响应里的 `format` / `bitrate` 回填，不能按请求值上报。
 * 2. 搜索结果的 `payInfo.listen_fragment` 为 `"1"` 时拿不到播放地址。
 *    **不再据此丢歌**（2026-09-19 改）：波点 App 自己也不滤，滤掉会让整个列表空掉。
 *    现在逐首下发 `playable`，由客户端置灰。见 [searchSongs]。
 * 3. 普通歌词接口极不稳定（6 首样本只有 1 首返回内容），逐字歌词接口反而稳定，
 *    所以歌词**以 LRCX 为主**，行级 LRC 由它派生。
 * 4. **`uid` 必须是纯数字，`token` 的取值不影响取址**：传非数字 uid（如
 *    `"contract-uid"`）时上游拒绝下发任何播放地址，而搜索、单曲信息、歌词都照常，
 *    表现为「搜得到、放不出」。早先的结论「一份无效的凭据比不带凭据更糟」是**误诊** ——
 *    当时对照用的凭据恰好是非数字 uid，两个变量没拆开。见 [BodianConfig] 的说明。
 * 5. **凭据接回播放链路**（2026-09-18 决定）。见 [clientOf] —— 搜索、单曲信息、取址、
 *    歌词四处都会带上后台配置的账号。
 *
 *    ⚠️ **2026-09-19 复核：带不带凭据，上游的响应完全一样。** 用生产库里的真实 VIP 账号
 *    对 10 个关键词做了逐字段对照（含原始 `payInfo`），搜索结果、单曲信息（含 `audios`）、
 *    取址**与匿名完全相同**，`listen_fragment` 一字不差；伪造凭据那轮同样零差异。
 *    请求头消融测试进一步确认：**去掉 `uid` / `token` 后响应一个字节都不变**，
 *    `sign` / `appid` / `api-ver` / `ts` / `nc` 也都是装饰性的，
 *    真正必需的只有 `Origin` 与 `Referer`（缺任一个上游回 402，且 `msg` 为空）。
 *
 *    结构性原因：**这个复刻版上游没有任何用户维度接口**（没有收藏 / 我的歌单 / 用户信息），
 *    凭据根本没有能被消费的地方。所以「接回」这件事目前是**零收益**的，
 *    保留它只是为了**留一个唯一的接回点**（见 [clientOf]），不是因为它有用。
 *    历史结论「实测确认登录与不登录拿到的内容不一样」**未能复现，已判定为误诊**。
 * 6. 后台的音源账号因此**目前不影响任何输出**。唯一被真正读取的是 `uid` 的**形状**：
 *    非纯数字时上游拒绝下发播放地址（第 4 条），这是「坏账号必须能被发现」的理由，
 *    属于正确性而不是功能。[invalidateCredentialCache] 仍然要调 ——
 *    它是为了让「账号状态」和「坏账号」及时可见，不是因为改了账号播放会变。
 */

/**
 * 酷我真实提供的档位。
 *
 * ⚠️ **第一档「高品质 320k」是虚标，实测恒为 128k。**
 * 2026-09-18 实测（5 首样本）：请求 `format=mp3`，`br` 写成 `320k` / `320` /
 * `320kbps` / `320000` / `320K` **全部**返回 `format: "mp3"`, `bitrate: 128`，
 * 且字节数与 128k 档逐字节相同。**带真实凭据也一样** —— 不是匿名降级，
 * 是这个入口本身拿不到 320k。后果有两处：客户端看到「高品质 320k」标签，
 * 以及 `size` 按 320k 估算，**下载前的大小预估会虚报约 2.5 倍**。
 *
 * 想拿 320k 得换入口：**`format=flac`** 会被上游降级成 **mp3 320k**
 * （实测 4/5 首歌拿到 320~324k，1 首只有 129k）。反直觉，但可复现。
 *
 * **无损档没接进来，而它对匿名也开放**：`format=zp&br=20000`
 * （`format=al&br=2000` 等价）返回真 FLAC（响应 `format: "flac"`，
 * 实测 4/5 首歌 727~1702kbps）。没接进来是因为档位编号（8 / 4 / 2）要回填到
 * 本项目的 0–18 档位模型，新增一档需要同步确认客户端能接受更高的 `quality`
 * 取值 —— 属于契约变更，不能在后端单方面加。
 *
 * 三档都可能**静默降级**（样本里有一首歌三档全部只给 129k），所以音质一律按
 * 响应里的 `format` / `bitrate` 回填，不能按请求值上报。
 */
const KUWO_TIERS = [
  // 原生入口能精确返回 mp3/320；playbasic 要请求 flac 才会降级成 320k mp3。
  { format: "mp3", br: "320k", basicFormat: "flac", basicBr: "2000", quality: 8, label: "高品质", catalogBitrate: 320 },
  { format: "mp3", br: "128k", basicFormat: "mp3", basicBr: "128k", quality: 4, label: "标准音质", catalogBitrate: 128 },
  { format: "aac", br: "48kaac", basicFormat: "aac", basicBr: "48kaac", quality: 2, label: "流畅音质", catalogBitrate: 48 },
] as const;

/** 凭据缓存的存活时间。后台改了账号后，播放链路最多 30 秒后生效。 */
const CREDENTIAL_TTL_MS = 30_000;

/**
 * 挑探测曲目用的关键词。
 *
 * **刻意不写死单个歌曲 ID**：酷我在持续下线正版曲（实测七里香 94237、
 * 告白气球 7149583、兰亭序 440616 都返回「歌曲已下线」），写死的 ID 会让
 * 连通性测试在那一首歌下线之后永久误报「凭据失效」。改成搜一次取当前可播的歌，
 * 测试就不会随曲库变动失效。
 */
const PROBE_KEYWORDS = ["轻音乐", "经典老歌"] as const;

/**
 * 向上游取搜索结果的**页大小**。固定 20，不跟随调用方传来的 `limit`。
 *
 * 两个原因，都在 [searchSongs] 的注释里写了实测数据：
 *
 * 1. 上游偏移量是 `pn × rn`，`rn` 一变同一个 `pn` 指向的位置就变 —— 翻页会出现空洞或重叠。
 * 2. `rn` 会参与排序（上游先取 top-`rn` 再排），`rn` 越大排序越可能变。
 *    实测「稻香」`rn=20` 与 `rn=60` 从第 10 首起分叉；`rn=20` 与 `rn=40` 的前 20 首
 *    在 5 个关键词上完全一致。
 *
 * 20 也是波点 App 自己的首页页大小（`rn = 已加载条数 + 20`，首屏 20 条）。
 */
const UPSTREAM_PAGE_SIZE = 20;

/**
 * 把上游失败结果拼成一句管理员看得懂的话。
 *
 * 哨兵码 [UPSTREAM_UNPARSABLE] 要单独处理：**它不是上游的业务码，是我们造的**，
 * 写成「上游业务码 -1」只会让人更困惑。这种情况直接用它自带的说明 ——
 * 里面含真实 HTTP 状态，而「403 被拦」和「502 上游挂了」的处置完全不同。
 *
 * 不认识的业务码一律原样透出：没实测过的映射不写死，上游原文比我们的猜测有用。
 */
function describeUpstreamFailure(code: number, message: string): string {
  const text = String(message ?? "").trim();
  if (code === UPSTREAM_UNPARSABLE) return text || "上游响应无法解析";
  return text ? `上游业务码 ${code}：${text}` : `上游业务码 ${code}`;
}

@Injectable()
export class KuwoClient implements MusicSourceClient, MusicSourceCredentialManager {
  readonly source: MusicSource = "kuwo";
  readonly displayName = "酷我";
  /** 歌曲 ID：酷我没有 mid，身份完全由数字 id 承载。 */
  readonly numericIdOnly = true;
  /**
   * 账号 ID 也必须是纯数字 —— **和 `numericIdOnly` 是两件事**：
   * 前者说的是歌曲 ID 的形状，这里说的是凭据里 uid 的形状。
   * 实测非数字 uid 会让上游拒绝下发任何播放地址（换 token 无效），所以写入时就要拦。
   */
  readonly numericUidOnly = true;
  /** 单曲信息里的时长用来估算各档大小，取一次能让档位选择少试错。 */
  readonly supportsTierProbe = true;

  private cached: { at: number; credential: MusicSourceCredential | undefined } | null = null;

  private readonly logger = new Logger(KuwoClient.name);

  constructor(private readonly accounts: MusicSourceAccountRepository) {}

  /**
   * 搜索。
   *
   * 结果里**不再丢掉拿不到地址的歌**，而是逐首带上 `playable` 让客户端置灰。
   *
   * ## 为什么不滤（2026-09-19 改）
   *
   * 判据一直是可靠的：上游 `payInfo.listen_fragment === "1"` 与「播放接口取不到地址」
   * 严格对应 —— 32 条样本（4 组关键词 × 每组 6–8 首）零误判；被滤掉的 40 首再用
   * **9 个入口**（现用三档之外的 `flac` / `zp` / `al` / `mgg` / `ogg`）逐个试，
   * **360 次尝试全部取不到地址**，确认没有丢任何可播的歌。
   *
   * **问题出在「滤掉」这个动作本身。** 实测同一关键词下波点 App 综合页
   * （`search/comprehensive/v2/list` 的 `musicpage`）与我们的原始列表逐条一致，
   * 也就是说 **App 不滤，它把放不了的歌也照常列出来**。于是搜「周杰伦」：
   *
   * | | 条数 |
   * | --- | --- |
   * | 上游原始返回 | 30 |
   * | 其中 `listen_fragment === "1"` | **30**（全部） |
   * | 旧实现下发 | **0** |
   * | 波点 App 展示 | 30 |
   *
   * 用户看到的是「搜不到歌」，第一反应是账号或音源坏了 —— 而真实原因是
   * 这 30 首在酷我上**都没有版权**（周杰伦是 TME 独家），上游逐首回
   * `code 20012 歌曲已下线`，取址与 `baseMode` 试听端点都拿不到地址。
   * App 那边同样一条都放不了，区别只在于它列出来了。
   *
   * 所以现在**照列 + 置灰**：列表与 App 逐条对齐，可播性如实标在每首歌上。
   * 详见 [SearchResult.dropped]（该字段因此恒为 0）。
   *
   * ## 分页：**上游页大小固定 20，不能用调用方的 `limit`**
   *
   * 这是「搜索结果和波点 App 对不上」的另一处根因（2026-09-19 定位并修复）。上游
   * `search/music/list` 有两个反直觉之处：
   *
   * 1. **偏移量是 `pn × rn`，且 `pn` 是 0 基的。** 参考实现写的是 `pn: page`，
   *    本项目照抄过 —— 于是**第一页实际拿到的是第二页的歌**（`pn=1&rn=60` → 偏移 60）。
   *    见 [BodianClient] 里 `upstreamPage()` 的实测表格。
   * 2. **`rn` 还会影响排序**：上游先取 top-`rn` 再排，`rn` 越大结果集越大、排序可能变。
   *    实测「稻香」`rn=20` 与 `rn=60` 从第 10 首起分叉；而 `rn=20` 与 `rn=40` 的前 20 首
   *    在 5 个关键词上完全一致 —— 所以**小窗口是稳定的**，20 是安全值。
   *
   * 波点 App 单曲页用的就是 `rn = 已加载条数 + 20`（首页 20），综合页用 `pn=0, rn=40`。
   * 两者前 20 首实测一致，所以取 20。
   *
   * 调用方要的条数（最多 60）**由本方法多取几个上游页再拼接**满足，而不是把 `limit`
   * 直接当 `rn` 传下去 —— 那样既会让起点漂移，也会让排序换一套。
   *
   * `nextPage` 按上游实际返回的条数判断，不按过滤后的条数 ——
   * 否则被滤掉几首之后分页会提前断掉。
   */
  async searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult> {
    const client = await this.clientOf();

    // 调用方要的是第 [start, end) 首；换算成上游需要哪几页（每页 UPSTREAM_PAGE_SIZE 条）。
    const start = (page - 1) * limit;
    const end = start + limit;
    const firstUpstreamPage = Math.floor(start / UPSTREAM_PAGE_SIZE) + 1;
    const lastUpstreamPage = Math.floor((end - 1) / UPSTREAM_PAGE_SIZE) + 1;

    const wanted: number[] = [];
    for (let p = firstUpstreamPage; p <= lastUpstreamPage; p++) wanted.push(p);
    const fetched = await Promise.all(
      wanted.map((p) => client.searchSongs(keyword, p, UPSTREAM_PAGE_SIZE)),
    );

    // 拼接后切出窗口：`fetched` 覆盖的是从 firstUpstreamPage 首条开始的连续一段。
    const windowStart = start - (firstUpstreamPage - 1) * UPSTREAM_PAGE_SIZE;
    const raw = fetched.flat().slice(windowStart, windowStart + limit);

    // 不做过滤：拿不到地址的歌也下发，由 `playable` 标记，客户端置灰。
    const list = raw.map((song) => this.toUpstreamSong(song));
    // 最后一个上游页取满了整页，说明上游后面还有货。
    const lastFetched = fetched.at(-1) ?? [];

    return {
      total: list.length,
      perPage: limit,
      nextPage: lastFetched.length === UPSTREAM_PAGE_SIZE ? page + 1 : null,
      list,
      // 恒为 0：可播性改由逐首的 `playable` 承载，见 [SearchResult.dropped]。
      dropped: 0,
    };
  }

  /**
   * 单曲信息与可用档位。
   *
   * 读取官方 `audios` 目录，但只下发本服务能交给普通播放器的 AAC/MP3 档位。
   * `mflac` / `mgg` / `mmp4` 需要官方 EKey 解密链，接口返回 URL 不代表客户端能解码，
   * 所以在解密能力接入前不能把这些档位虚报为可播放。
   *
   * 时长未知时不声明任何档位：`size: 0` 在本项目的契约里表示「这首歌没有这一档」，
   * 拿一个编造的数字去占位比不给档位更糟。
   */
  async requestSongInfo(key: SongKey): Promise<UpstreamSongInfo> {
    const id = this.requireId(key);
    const client = await this.clientOf();
    const info = await client.getMusicInfo(id);
    if (!info) throw ApiErrors.upstream("酷我歌曲信息不可用");
    const duration = Number(info.duration) || 0;
    const tiers: QualityTier[] = KUWO_TIERS.flatMap((tier) => {
      const advertised = info.audios?.find((audio) =>
        String(audio.format ?? "").toLowerCase() === tier.format &&
        Number(audio.bitrate ?? 0) === tier.catalogBitrate,
      );
      if (info.audios?.length && !advertised) return [];
      const size = this.audioSizeBytes(advertised?.size) ||
        (duration > 0 ? this.estimateSize(tier.catalogBitrate, duration) : 0);
      return size > 0 ? [{ type: tier.quality, size, label: tier.label }] : [];
    });
    return {
      songID: id,
      // 酷我没有 mid，身份完全由数字 id 承载。
      songMID: "",
      title: info.name ?? "",
      singer: info.artist ?? "",
      album: info.album ?? "",
      cover: info.cover ?? "",
      // 上游的付费标记（feeType.vip）对几乎所有歌都是 1，据此标 VIP 会满屏都是，
      // 反而失去意义，所以不报。
      pay: "",
      interval: duration,
      tiers,
    };
  }

  /**
   * 解析播放地址，按档位阶梯降级。
   *
   * 回填的 `quality` 取自**响应里的真实 `bitrate` / `format`**，不是请求值 ——
   * 上游会静默降级（请求 320k 可能只给 128k），照请求值上报会让客户端把 128k
   * 当成高音质写进下载记录，`/link` 的 `fallback` 也会恒为 false。
   *
   * 带凭据时优先走官方原生 KPK 入口，它会按账号权益精确请求目标档位；
   * `playbasic` 只作为原生入口失败或未配置账号时的兼容兜底。
   *
   * 这里**刻意不做「带凭据失败就退回匿名重试」的兜底** ——
   * 兜底会把「uid 写坏了」这种坏账号伪装成正常播放，正是要修掉的那种沉默失败。
   * 取不到地址就按 502 报出来，让问题可见。
   */
  async resolveLink(
    key: SongKey,
    quality: number,
    verify?: (url: string) => Promise<boolean>,
  ): Promise<UpstreamLink> {
    const id = this.requireId(key);
    const link = await this.attemptLadder(await this.clientOf(), id, quality, verify);
    // 拿不到地址：要么这首歌已下线，要么凭据/上游有问题。按既有约定走 502，绝不能 401。
    if (!link) throw ApiErrors.upstream("酷我没有返回可用的播放地址");
    return link;
  }

  /**
   * 走完整个档位阶梯。全部拿不到地址时返回 null。
   *
   * [client] 由调用方传进来而不是在这里 `new` —— 这个客户端的凭据身份是
   * 「当前生效的那个账号」，在内部新建会把凭据丢掉，表现为「配了账号但取址仍按匿名走」。
   *
   * 有凭据时必须先走原生轮。旧实现先接受 `playbasic` 的任意可播结果，320k 请求
   * 常在这里被静默满足成 128k，导致原生入口永远没有机会使用会员权益。
   *
   * 拿到地址后仍然要求 `verify` 通过 —— 原生入口返回的 URL 同样可能是过期签名。
   */
  private async attemptLadder(
    client: BodianClient,
    id: number,
    quality: number,
    verify: ((url: string) => Promise<boolean>) | undefined,
  ): Promise<UpstreamLink | null> {
    const accept = async (audio: { audioUrl?: string; audioHttpsUrl?: string; bitrate?: string; format?: string }) => {
      const url = String(audio.audioHttpsUrl || audio.audioUrl || "").replace(/^http:/, "https:");
      if (!url) return null;
      if (verify && !(await verify(url))) return null;
      return { url, kbps: String(audio.bitrate ?? ""), quality: this.qualityOfAudio(audio) };
    };

    const failures: string[] = [];
    if (client.hasCredentials()) {
      for (const tier of this.requestLadder(quality)) {
        const outcome = await client.getAudioUrlNative(id, tier.format, tier.br).catch(() => null);
        if (!outcome) continue;
        if (!outcome.audio) {
          failures.push(`code=${outcome.code} ${outcome.message}`);
          continue;
        }
        const link = await accept(outcome.audio);
        if (link) return link;
      }
    }

    for (const tier of this.requestLadder(quality)) {
      const audio = await client.getAudioUrl(id, tier.basicFormat, tier.basicBr).catch(() => null);
      if (!audio) continue;
      const link = await accept(audio);
      if (link) return link;
    }

    // 两条入口都拿不到。**把原生的业务码带进日志** —— 它是区分「这首歌要付费」
    // 和「签名/账号坏了」的唯一线索，而这两件事的处置完全相反。
    // 不要把它升级成错误抛出：调用方按既有约定报 502，这里只负责留下证据。
    if (failures.length) {
      this.logger?.warn(`酷我原生取址失败 musicId=${id} ${failures.join(" | ")}`);
    }
    return null;
  }

  /**
   * 歌词。
   *
   * **以 LRCX 为主**：实测普通歌词接口 6 首样本只有 1 首返回内容，而 LRCX 6 首全中。
   * 行级 LRC 由 LRCX 派生，逐字时间轴只在真的有字级时间组时才给 ——
   * 有些歌的 LRCX 只有行时间，那种情况下发空的 yrc，客户端会退回整行高亮。
   */
  async requestLyric(key: SongKey): Promise<RichLyric> {
    const id = this.requireId(key);
    const client = await this.clientOf();
    const lrcx = await client.getLrcxLyrics(id).catch(() => null);
    const lines = lrcx ? client.parseLrcx(lrcx) : [];
    if (lines.length > 0) {
      return {
        lrc: this.lrcOf(lines),
        yrc: this.hasWordTimings(lines) ? this.yrcOf(lines) : "",
        // 酷我没有翻译/音译接口。
        trans: "",
      };
    }
    const lrc = await client.getLyrics(id);
    if (!lrc) throw ApiErrors.upstream("没有歌词");
    return { lrc, yrc: "", trans: "" };
  }

  /**
   * 用给定凭据做一次真实取址，返回探测到的音质摘要。
   *
   * 供后台的连通性测试使用。它的职责是**确认这份凭据能拿到播放地址**，
   * 并把真实码率报出来。
   *
   * 有凭据时走原生 KPK 接口；匿名时走 `playbasic`。带凭据探测成功可以证明
   * uid/token 已进入官方权益取址链路，摘要也来自原生接口返回的真实格式与码率。
   *
   * 失败时**不能说成「凭据失效」**：实测取址失败最可能的原因是 `uid` 不是纯数字
   * （见 [BodianConfig]），此时换任何 token 都没用。所以错误文案把两个可能都列出来。
   *
   * 凭据为空时按匿名探测，结果带上「匿名」前缀 —— 匿名本身是能取到地址的，
   * 不标出来会让管理员误以为这个账号的凭据已经生效。
   *
   * 注意探测**故意不退回匿名**：兜底是播放链路的事，测试的目的就是把问题暴露出来。
   */
  async probeCredential(credential: MusicSourceCredential, musicId?: number): Promise<string> {
    const client = new BodianClient({ token: credential.token, uid: credential.uid });
    const anonymous = credential.token === "";
    if (!anonymous) {
      const auth = await client.validateCredentials();
      if (!auth.ok) {
        throw ApiErrors.upstream(`酷我凭据强鉴权失败（code=${auth.code} ${auth.message}）`);
      }
    }
    // 112051 是张震岳《再见》，已用官方客户端协议验证过 128K/320K/FLAC 完整资源。
    // 用固定的会员控制曲，避免随机挑到免费歌曲造成“账号测试成功”的假阳性。
    const target = musicId ?? (anonymous ? await this.pickProbeId(client) : 112051);
    const native = anonymous
      ? null
      : await client.getAudioUrlNative(target, "mp3", "320k");
    const audio = anonymous
      ? await client.getAudioUrl(target, "flac", "2000")
      : native?.audio;
    const url = audio?.audioHttpsUrl || audio?.audioUrl;
    if (!url) {
      throw ApiErrors.upstream(
        anonymous
          ? "匿名探测取不到播放地址，上游可能暂时不可用"
          : `原生权益取址失败（code=${native?.code ?? 0} ${native?.message ?? ""}）：uid 必须是纯数字，或凭据已失效，或该曲不可用`,
      );
    }
    const bitrate = audio?.bitrate ? `${audio.bitrate}kbps` : "未知码率";
    const summary = `${audio?.format ?? "未知格式"} ${bitrate}`;
    return anonymous ? `匿名 ${summary}` : summary;
  }

  /**
   * 挑一首当前确实能播的歌做探测。
   *
   * 走搜索而不是写死 ID，理由见 [PROBE_KEYWORDS] 的注释。**必须用带凭据的同一个
   * 客户端**：换一个匿名客户端去搜，就变成「用匿名身份验证这个账号」，测试失去意义。
   */
  private async pickProbeId(client: BodianClient): Promise<number> {
    for (const keyword of PROBE_KEYWORDS) {
      const songs = await client.searchSongs(keyword, 1, 10).catch(() => [] as Song[]);
      const candidate = songs.find((song) => song.playable !== false);
      if (candidate) return candidate.id;
    }
    throw ApiErrors.upstream("酷我搜索没有返回可播放的歌曲，无法完成探测");
  }

  /**
   * 往该手机号发登录验证码。
   *
   * **报错必须带上上游的业务码，不要压成一句「请确认号码可用」。**
   *
   * 原来的写法把 `sendSms` 的布尔结果翻译成「请确认号码可用」，于是所有失败
   * ——号码未注册、刚发过还在冷却期、被风控拦、上游响应根本不是 JSON——
   * 都呈现成同一句话。实测中就出现过这条文案把人往「换号」的方向带，
   * 而实际上号码没问题、是上游那边的事。
   *
   * 现在按 [SmsOutcome] 分流：
   * - 哨兵码（响应不可解析）直接用它自带的说明，里面含真实 HTTP 状态；
   * - 其余业务码连同上游原文一起透出。**不在这里替上游猜**哪个码代表
   *   「发送过于频繁」——没实测过的映射不写死，原文比猜测有用。
   *
   * 仍然回 502（`ApiErrors.upstream`）：发码失败是上游侧的事，不是客户端请求有错。
   */
  async sendLoginSms(phone: string): Promise<void> {
    const outcome = await new BodianClient().sendSms(phone);
    if (outcome.ok) return;
    throw ApiErrors.upstream(
      `酷我没有接受这次验证码请求 —— ${describeUpstreamFailure(outcome.code, outcome.message)}`,
    );
  }

  /**
   * 用验证码换取凭据。
   *
   * 换到的东西**只返回给调用方落库，不写进本实例** —— 本适配器是无状态单例，
   * 后台登录的是「要存进数据库的那份凭据」，与当前生效的凭据是两件事：
   * 管理员完全可能是在测试一个新号，不该顺带把线上的号顶掉。
   *
   * 失败时按上游业务码分流：`11004` 是验证码本身不对（管理员输错或超时），
   * 回 4xx 让前端提示重新获取；其余（哨兵码、`-10 参数错误` 等）
   * 是我们或上游出了问题，回 502。**不要把两者混成一句「验证码错误」** ——
   * 那会让管理员对着一个自己没输错的码反复重试。
   */
  async loginBySms(phone: string, code: string): Promise<{ token: string; uid: string }> {
    const result = await new BodianClient().loginSms(phone, code);
    if (result.ok) return { token: result.token, uid: result.uid };
    if (result.code === SMS_CODE_REJECTED) {
      throw ApiErrors.badRequest(4007, "验证码错误或已过期，请重新获取");
    }
    throw ApiErrors.upstream(
      `酷我登录失败 —— ${describeUpstreamFailure(result.code, result.message)}`,
    );
  }

  /**
   * 凭据变更后清缓存。
   *
   * 后台登录成功、改 token / uid、切换启用状态之后都会调用它。**必须调用**：
   * 播放链路读凭据走 30 秒 TTL 缓存，不清就会出现「后台显示登录成功，
   * 但播放仍然在用旧凭据」，管理员只能靠等 30 秒或重启来排除，
   * 那比多查一次库的代价大得多。
   */
  invalidateCredentialCache(): void {
    this.cached = null;
  }

  // ---------- 内部工具 ----------

  /**
   * 构造传输层客户端，并带上当前生效的凭据。
   *
   * **这是凭据进入播放链路的唯一入口** —— 搜索、单曲信息、取址、歌词全部经过它，
   * 所以只要在这里读一次库，四处就都带上了。别在别处直接 `new BodianClient()`：
   * 那会绕开凭据，而**凭据里唯一被上游真正读取的是 `uid` 的形状** ——
   * 绕开它就意味着「uid 写坏了」这类坏账号再也不会被发现（见类注释第 5、6 条）。
   *
   * ⚠️ **不要以为在这里带上凭据就会改变播放结果。** 2026-09-19 实测真实 VIP 账号
   * 与匿名的搜索 / 单曲信息 / 取址结果完全相同。这个入口现在的职责是
   * **保证凭据只从一处进入、便于日后接回**，以及**让坏账号暴露**，不是提升音质。
   *
   * 没有启用的账号（或 token 为空）时按匿名请求 —— 上游匿名也能取到地址，
   * 所以这是合法状态，不是错误。
   *
   * 缓存由 [credential] 负责，30 秒 TTL：搜索与取址都是高频路径，
   * 不能每次请求都查一次库。
   */
  private async clientOf(): Promise<BodianClient> {
    const client = new BodianClient();
    const credential = await this.credential();
    if (credential) client.setCredentials(credential.token, credential.uid);
    return client;
  }

  /**
   * 取当前生效的凭据（带 30 秒缓存）。
   *
   * 只认**启用状态**里最新的那一条（`credentialFor`），禁用 / 未配 / token 为空
   * 都返回 undefined，调用方按匿名处理。
   *
   * 30 秒 TTL 是为了避免每次请求都查库。代价是后台刚改完账号最多 30 秒才生效，
   * 所以登录 / 改凭据的接口会主动调 [invalidateCredentialCache] 把这个窗口抹掉。
   */
  private async credential(): Promise<MusicSourceCredential | undefined> {
    const now = Date.now();
    if (this.cached && now - this.cached.at < CREDENTIAL_TTL_MS) return this.cached.credential;
    const credential = await this.accounts.credentialFor(this.source);
    this.cached = { at: now, credential };
    return credential;
  }

  /** 请求档位阶梯。高档拿不到就往下退，最多试三次。 */
  private requestLadder(quality: number): readonly (typeof KUWO_TIERS)[number][] {
    if (quality >= 8) return KUWO_TIERS;
    if (quality >= 3) return KUWO_TIERS.slice(1);
    return KUWO_TIERS.slice(2);
  }

  /** 把上游返回的格式与码率映射回本项目的 0–18 档位。 */
  private qualityOfAudio(audio: { format?: string; bitrate?: string }): number {
    const format = String(audio.format ?? "").toLowerCase();
    const bitrate = Number(audio.bitrate ?? 0);
    if (format.includes("flac")) return 10;
    if (format.includes("mp3")) return bitrate >= 320 ? 8 : bitrate >= 192 ? 6 : 4;
    if (format.includes("aac") || format.includes("m4a")) return 2;
    return 2;
  }

  private bitrateOf(br: string): number {
    const matched = br.match(/^(\d+)k$/);
    return matched ? Number(matched[1]) : 128;
  }

  /** 按码率与时长估算字节数。上游不告诉我们真实大小时用它填 `size`。 */
  private estimateSize(bitrateKbps: number, durationSeconds: number): number {
    return Math.round((bitrateKbps * 1000 / 8) * durationSeconds);
  }

  /**
   * 波点搜索框联想词。协议参数与解析字段均来自官方 5.9.1 客户端：
   * `search/tip/v2/list` + `keyword` + `tipsFrom=bd`，读取 `resultList[].relword`。
   */
  async searchSuggestions(keyword: string, limit: number): Promise<string[]> {
    const client = await this.clientOf();
    return client.searchTips(keyword, limit);
  }

  /** 波点搜索首页热词，来自官方 `search/topic/word/list` 的 `hotWord`。 */
  async searchHotKeywords(limit: number) {
    const client = await this.clientOf();
    return client.searchHotKeywords(limit);
  }

  /** 官方目录的 size 可能是字节数，也可能是 `9.94Mb` 这类显示字符串。 */
  private audioSizeBytes(value: unknown): number {
    if (typeof value === "number" && Number.isFinite(value) && value > 0) return Math.round(value);
    const text = String(value ?? "").trim();
    const matched = text.match(/^(\d+(?:\.\d+)?)\s*(kb|mb|gb|b)?$/i);
    if (!matched) return 0;
    const amount = Number(matched[1]);
    const unit = (matched[2] ?? "b").toLowerCase();
    const multiplier = unit === "gb" ? 1024 ** 3 : unit === "mb" ? 1024 ** 2 : unit === "kb" ? 1024 : 1;
    return Math.round(amount * multiplier);
  }

  /**
   * 取正整数 ID。
   *
   * 注意这个 id 是**酷我自己的序号**，和 QQ 音乐的 songID 完全无关 ——
   * 同一个数字在两边指向不同的歌。适配器的存在就是为了让上层只处理
   * `(source, id)` 这一对身份，任何绕过 `source` 单独传 id 的调用点都是错的。
   */
  private requireId(key: SongKey): number {
    const id = Number(key.id);
    if (!Number.isInteger(id) || id <= 0) {
      throw ApiErrors.badRequest(4001, "酷我歌曲必须提供正整数 ID");
    }
    return id;
  }

  private toUpstreamSong(song: Song): UpstreamSong {
    return {
      songID: song.id,
      // 酷我没有 mid 概念，身份完全由数字 id 承载。
      songMID: "",
      title: song.name,
      singer: song.artist,
      album: song.album,
      subtitle: song.subtitle ?? "",
      time: "",
      interval: song.durationSeconds ?? 0,
      cover: song.cover ?? "",
      pay: "",
      // `undefined` 按可播处理，只有明确为 false 才置灰 —— 判据见 [searchSongs]。
      playable: song.playable !== false,
    };
  }

  // ---------- 歌词格式转换 ----------

  /** 逐字时间轴：只要有一行的字级时间真的推进过，就认为这份歌词有字级数据。 */
  private hasWordTimings(lines: LrcxLine[]): boolean {
    return lines.some((line) => line.words.some((word) => word.endMs > word.startMs));
  }

  /** 由 LRCX 派生行级 LRC。客户端的老接口与纯文本展示都用它。 */
  private lrcOf(lines: LrcxLine[]): string {
    return lines.map(({ lineMs, words }) => {
      const minutes = Math.floor(lineMs / 60000);
      const seconds = (lineMs % 60000) / 1000;
      const text = words.map((word) => word.char).join("");
      return `[${String(minutes).padStart(2, "0")}:${seconds.toFixed(2).padStart(5, "0")}]${text}`;
    }).join("\n");
  }

  /**
   * 由 LRCX 派生逐字时间轴。
   *
   * 客户端已有的 yrc 格式是 `[行起始ms,行时长ms]文本(字起始ms,字时长ms)…`，
   * 与酷我的 `<o1,o2>` 双偏移量编码完全不同 —— 在这里换算成前者，
   * **客户端因此不需要任何改动**。
   *
   * 行时长取下一行的起始时间；最后一行用它的最后一个字的结束时间。
   * 没有字级时间的字符按 0 时长下发，客户端的 `progressOf` 会把 0 时长当作已唱完。
   */
  private yrcOf(lines: LrcxLine[]): string {
    return lines.map(({ lineMs, words }, index) => {
      const nextLineMs = lines[index + 1]?.lineMs;
      const lastWordEnd = words[words.length - 1]?.endMs ?? lineMs;
      const lineDuration = Math.max(
        (nextLineMs !== undefined ? nextLineMs : lastWordEnd) - lineMs,
        0,
      );
      const body = words
        .map(({ char, startMs, endMs }) => `${char}(${Math.round(startMs)},${Math.max(Math.round(endMs - startMs), 0)})`)
        .join("");
      return `[${lineMs},${lineDuration}]${body}`;
    }).join("\n");
  }
}
