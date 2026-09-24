import type { MusicSourceCredential } from "./music-source-account.repository";
import type {
  RichLyric,
  SearchResult,
  UpstreamLink,
  UpstreamSongInfo,
} from "./tencent.client";

/**
 * 已接入的音源。
 *
 * 这个类型刻意放在上游适配层而不是 `music/` 里：`music/` 和 `upstream/` 都要用它，
 * 放在业务侧会让适配层反向依赖业务层，形成循环。
 *
 * 新增音源时的完整清单见 `plans/006-kuwo-bodian-source.md`。
 */
export const MUSIC_SOURCES = ["tencent", "netease", "kuwo"] as const;

export type MusicSource = (typeof MUSIC_SOURCES)[number];

/**
 * 判断一个字符串是不是已接入的音源。
 *
 * 各处白名单（音乐路由、歌单、分享）统一用它，避免手写
 * `value !== "tencent" && value !== "netease"` 这种数组 —— 每加一个音源都要改一遍，
 * 漏掉一处就是「新音源在某个入口被静默拒绝」或反过来「把未知来源放进去」。
 */
export function isMusicSource(value: string): value is MusicSource {
  return (MUSIC_SOURCES as readonly string[]).includes(value);
}

/**
 * 参与聚合搜索（`source=all`）的音源。
 *
 * **酷我暂时不在其中**：实测它的正版曲库大量返回「歌曲已下线」，能播的多是
 * 伴奏、Remix、翻唱和网友改编。而 Android 端没有音源选择器、搜索也不传 `source`，
 * 一旦把它并进聚合结果，Android 用户会立刻在搜索结果里看到一批这类内容。
 *
 * 等人工抽样确认内容质量可接受之后，把 "kuwo" 加进这个数组即可，其余代码不用动。
 * 在此之前酷我只能被显式指定 `?source=kuwo` 访问。
 */
/**
 * 聚合搜索（客户端传 `source=all`）要查的音源。
 *
 * ⚠️ **酷我不在这里**，所以选「全部」搜索**不会查酷我**，后台配的酷我账号
 * 在聚合搜索里自然也不起作用。这不是遗漏，是刻意先只开放显式
 * `source=kuwo`，观察一段时间再决定要不要并进 `all`（见
 * `plans/006-kuwo-bodian-source.md` 的「待决策」）。
 *
 * 排查「配了账号但搜索没变化」时先看这里：**得先选中酷我，账号才有机会参与**。
 * 注意这与「歌曲身份兜底音源」是两件事 —— 那个是 [MusicSourceRegistry.of]
 * 里 `/songs/:id/link` 不带 `source` 时的默认值，跟搜索范围无关。
 */
export const AGGREGATED_SOURCES: readonly MusicSource[] = ["tencent", "netease"];

/**
 * 歌曲身份。
 *
 * ## 数字 ID 只在所属音源内有意义，绝不能跨音源混用
 *
 * `id` 是各上游自己发放的序号，**同一个数字在不同音源里指向完全不同的两首歌**。
 * 把酷我的 `51685507` 当成 QQ 的 ID 发出去，不会报错 —— 它会安静地返回另一首歌，
 * 或者一首「已下线」。用户看到的是「点了这首，放的是别的」，而不是一个错误提示。
 *
 * 因此本项目里**凡是出现歌曲 ID 的地方都必须同时带着 `source`**，这一条已经在
 * 数据层落实：`favorites` 是 `UNIQUE (user_id, source, song_id)`，
 * `playlist_songs` 是 `PRIMARY KEY (playlist_id, source, song_id)`，
 * `song_share` 是 `UNIQUE (user_id, source, song_id)`；客户端也用
 * `"$source:$id"` 拼复合键。**新增任何存歌曲的表或缓存，都照这个形状来。**
 *
 * 代码里的两道防线：
 *
 * - [MusicSourceRegistry.of] 是唯一的音源分派点，未注册的 source 直接拒绝，
 *   不会静默落到某个默认音源；
 * - 适配器的 `numericIdOnly` 会拒绝 mid-only 身份，避免把别的音源的 mid 传进来。
 *
 * 还有一道**故意保留的缺口**：`/songs/:id/link` 不带 `source` 时默认按腾讯音乐解析，
 * 这是装机客户端的既有契约（它们历史上只发 id），改掉会让旧版本全部失声。
 * 所以新接口、新客户端**一律显式传 `source`**，不要依赖这个默认值。
 */
export type SongKey = { id?: number; mid?: string; type?: number };

/**
 * 一个音源适配器必须提供的能力。
 *
 * 抽这个接口的直接动机：在此之前「选哪个上游」是散在 6 个地方的
 * `source === "netease" ? netease : tencent` 三元表达式，而**默认分支是腾讯**。
 * 放行第三个音源时，任何一处漏改都会把新音源的 ID 当成腾讯 ID 发出去 ——
 * 不报错、不进日志，只表现为「这首歌没声音」。有了接口，分派只剩注册表一处。
 */
export interface MusicSourceClient {
  /** 这个适配器服务的音源。注册表按它建索引。 */
  readonly source: MusicSource;

  /**
   * 音源的中文名。
   *
   * 只用于错误提示（「酷我歌曲必须提供正整数 ID」）。放在接口里是为了让调用方
   * 不必写 `source === "netease" ? "网易云" : "QQ 音乐"` 这种分支 ——
   * 那种写法每加一个音源就要多改一处，正是本次抽接口要消灭的东西。
   */
  readonly displayName: string;

  /** 只接受正整数 ID，不接受 mid-only 身份。腾讯音乐是唯一支持 mid-only 的音源。 */
  readonly numericIdOnly: boolean;

  /**
   * 是否支持按「已知可用档位」精确挑档。
   *
   * 为真时调用方会先取一次单曲信息，把最坏情况从 4 次上游请求降到 1 次。
   * 为假的音源（网易云不分档）多问一次只是白白多一个请求，所以调用方会跳过。
   */
  readonly supportsTierProbe: boolean;

  /** 搜索只返回元数据；播放地址按需在 [resolveLink] 里获取。 */
  searchSongs(keyword: string, page: number, limit: number): Promise<SearchResult>;

  /**
   * 搜索框联想词。只有上游提供该能力时才实现；调用方必须先判断方法是否存在。
   * 返回值保持上游排序，只包含可以直接回填搜索框的文本。
   */
  searchSuggestions?(keyword: string, limit: number): Promise<string[]>;

  /** 搜索首页热词；顺序由上游决定，结构只保留展示和点击所需字段。 */
  searchHotKeywords?(limit: number): Promise<Array<{
    keyword: string;
    type: number;
    icon: string;
    sort: number;
    searchType: number;
    jumpUrl: string;
  }>>;

  /** 单曲信息与**真实可用**的音质档位。 */
  requestSongInfo(key: SongKey): Promise<UpstreamSongInfo>;

  /**
   * 解析播放地址。
   *
   * [verify] 用于在音质阶梯内部筛掉死链；[available] 是已知可用的档位集合，
   * 传了就能少打几次上游请求。两者都是可选优化，实现可以忽略。
   */
  resolveLink(
    key: SongKey,
    quality: number,
    verify?: (url: string) => Promise<boolean>,
    available?: Set<number>,
  ): Promise<UpstreamLink>;

  /** 歌词三件套。没有歌词时抛上游异常，调用方归成 502。 */
  requestLyric(key: SongKey): Promise<RichLyric>;
}

/**
 * 音源账号的凭据管理能力。
 *
 * **只有支持「用手机号 + 短信验证码换取凭据」的音源才实现它** —— 腾讯音乐与
 * 网易云的凭据来自账号密码或第三方授权，没有这条路，后台的音源账号页面
 * 对它们只会显示「不支持登录」。
 *
 * 与 [MusicSourceClient] 分开而不是塞进同一个接口：前者是播放链路每个音源都必须
 * 提供的，后者是管理面才用得到的。合成一个接口会逼着腾讯/网易去实现一堆
 * 抛 `NotImplemented` 的空方法 —— 那种「接口在但一调就炸」的形状比缺一个能力更糟。
 *
 * 注册表按音源查得到它才说明该音源支持后台登录（见 [MusicSourceRegistry.credentialManagerOf]）。
 */
export interface MusicSourceCredentialManager {
  /**
   * 该音源是否要求账号 ID（`uid`）必须是纯数字。
   *
   * 酷我**是**：传非数字 uid 时上游会拒绝下发**任何播放地址**，而搜索、单曲信息、
   * 歌词全部照常 —— 表现为「搜得到、放不出」，且换任何 token 都救不回来
   * （见 `bodian.client.ts` 里 [BodianConfig] 的实测表）。这类账号一旦存进库就是坏的，
   * 还很难看出原因，所以在**写入时就拦掉**，而不是等播放时才发现。
   *
   * 用能力标记而不是在控制器里写 `if (source === "kuwo")`：分派规则只有注册表一处，
   * 加新音源时改适配器即可。
   */
  readonly numericUidOnly: boolean;

  /**
   * 往该手机号发一条登录验证码。
   *
   * 上游不区分「号码格式不对」和「发得太频繁」，统一返回非成功码，所以实现只能
   * 把失败报成上游异常。**验证码本身不落在我们这边**：第二步拿它去上游换凭据时
   * 由上游校验，我们既不存也不比对，避免自己实现一套有漏洞的验证码校验。
   */
  sendLoginSms(phone: string): Promise<void>;

  /**
   * 用验证码换取凭据。返回值就是要落库的 `token` / `uid`。
   *
   * 验证码错误、过期、号码未注册都会抛异常，调用方按 4xx 报给管理员 ——
   * 这是管理员自己输错，不是上游故障，不该显示成 502。
   */
  loginBySms(phone: string, code: string): Promise<{ token: string; uid: string }>;

  /**
   * 用给定凭据真实取一次播放地址，返回探测到的音质摘要（例如 `mp3 128kbps`）。
   *
   * **只测「通不通」是不够的**：上游不会因为登录了就自动给更高码率，必须把真实
   * 码率报出来，管理员才能看出这个账号到底有没有换来音质提升。取不到地址时抛异常。
   */
  probeCredential(credential: MusicSourceCredential, musicId?: number): Promise<string>;

  /**
   * 凭据变更后清掉播放链路的缓存。
   *
   * 适配器会把「当前生效凭据」缓存一小段时间（避免每次请求都查库）。后台刚登录完
   * 若不清缓存，管理员会看到「登录成功但测试仍然失败」，然后去怀疑凭据本身 ——
   * 这个误解的代价比多一次查库大得多。
   */
  invalidateCredentialCache(): void;
}

export type { RichLyric, SearchResult, UpstreamLink, UpstreamSongInfo };
