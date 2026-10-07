import type { MusicSourceCredential } from "./music-source-account.repository";
import type {
  RichLyric,
  SearchResult,
  UpstreamLink,
  UpstreamSong,
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
 * `/search` 流里歌手行（`type: "artist"`）的 data。
 *
 * 字段名就是**下发契约名** —— 上游的 `songNum` / `albumNum` 必须在适配器里就地改成
 * `songCount` / `albumCount`，出流后不再有第二层改名。唯独 `source` 不在这里：
 * 它由 SearchService 按当前请求的音源统一补上。
 */
export interface UpstreamArtist {
  id: number;
  name: string;
  /** 封面绝对地址。上游可能给空串。 */
  pic: string;
  songCount: number;
  albumCount: number;
}

/**
 * `/search` 流里专辑行（`type: "album"`）的 data。命名约定同 [UpstreamArtist]。
 *
 * 上游条目里还有一条**体积很大的 `info`**（专辑简介，单条可达几十 KB），对列表展示
 * 毫无用处，适配器映射时**必须丢弃** —— 透传只会白白撑大搜索响应。
 */
export interface UpstreamAlbum {
  id: number;
  name: string;
  /** 封面绝对地址。上游可能给空串。 */
  pic: string;
  artist: string;
  artistId: number;
  /** 收录歌曲数。上游叫 `musicCount`。 */
  songCount: number;
  /** 发行日期（例如 `2026-03-25`）。上游可能缺失或空串。 */
  showtime: string;
}

/**
 * 专辑详情（`GET /api/v1/albums/:id` 的 `data.album`，去掉 `source`）。命名约定同
 * [UpstreamArtistDetail]。
 *
 * 这是**唯一保留上游长简介 `info` 的契约**（就地改名为 `desc`）：专辑详情页要整段展示
 * 它，单条几十 KB 也照收。搜索列表行（[UpstreamAlbum]）与歌手专辑列表仍照旧丢弃 ——
 * 那里只是封面加歌名的网格，透传简介只会白白撑大响应。
 */
export interface UpstreamAlbumDetail {
  id: number;
  name: string;
  /** 封面绝对地址。上游可能给空串。 */
  pic: string;
  artist: string;
  artistId: number;
  /** 收录歌曲数。上游叫 `musicCount`。 */
  songCount: number;
  /** 发行日期（例如 `2026-03-25`）。上游可能缺失或空串。 */
  showtime: string;
  /** 专辑长简介。上游字段叫 `info`，仅详情保留。 */
  desc: string;
}

/**
 * 歌手详情（`GET /api/v1/artists/:id` 的 `data.artist`，去掉 `source`）。
 *
 * 字段名就是**下发契约名** —— 上游的 `fansCnt` / `musicCnt` / `albumCnt` 必须在适配器里
 * 就地改成 `fansCount` / `musicCount` / `albumCount`，出流后不再有第二层改名。与
 * [UpstreamArtist] 的搜索行不同：详情保留 `aliasName`（艺名）与 `desc`（简介），但同样
 * 丢弃 `guardDesc`（守护标语，波点运营内容）与 `isshowtype`（上游展示开关）。
 */
export interface UpstreamArtistDetail {
  id: number;
  name: string;
  /** 艺名/别名。上游可能给空串。 */
  aliasName: string;
  /** 封面绝对地址。上游可能给空串。 */
  pic: string;
  /** 歌手简介。上游可能给空串。 */
  desc: string;
  fansCount: number;
  musicCount: number;
  albumCount: number;
}

/**
 * 歌单搜索（`GET /search/playlists` 的 `data.playlists[]`，去掉 `source`）。
 *
 * 字段名就是**下发契约名** —— 上游的 `creator_name` / `musicnum` / `playnum` 必须
 * 在适配器里就地改成 `creator` / `trackCount` / `playCount`，出流后不再有第二层改名。
 * 上游条目里的 `creator_id` / `sltype` / `hitcontent` 是列表展示用不到的内容，
 * 适配器映射时**必须丢弃**；`pic` 上游给 http 明文，适配器必须升级 https。
 * 数字 `source` 标记是唯一的例外：它平时确实展示用不到，但进歌单详情时
 * 要作为寻址参数**原样回传**给上游（见 [getPlaylistInfo]），所以以
 * [UpstreamSearchPlaylist.sourceMarker] 的形式保留。
 */
export interface UpstreamSearchPlaylist {
  id: number;
  name: string;
  /** 封面绝对地址。上游给 http 明文，适配器必须升级成 https。 */
  pic: string;
  /** 歌单创建者昵称。上游叫 `creator_name`。 */
  creator: string;
  /** 歌单内歌曲数。上游叫 `musicnum`。 */
  trackCount: number;
  /** 播放次数。上游叫 `playnum`。 */
  playCount: number;
  /**
   * 上游条目的数字 `source` 标记（实测恒为 4）。**在线歌单详情端点的寻址参数**：
   * 波点 App 打开歌单详情时把它放进请求体原样回传。缺失或非数值收敛成 0，
   * 由调用方决定缺省口径。
   */
  sourceMarker: number;
}

/**
 * 在线歌单详情（`GET /online-playlists/:id` 的 `data.playlist`，去掉 `source`）。
 *
 * 字段名就是**下发契约名** —— 上游的 `playnum` / `musicCount` / `collectedCnt`
 * 必须在适配器里就地改成 `playCount` / `trackCount` / `collectedCount`，出流后
 * 不再有第二层改名。与搜索行 [UpstreamSearchPlaylist] 的关键差别：详情保留长简介
 * `description` 与创建者信息 —— 歌单详情页要整段展示简介与创建者行。上游的 `isFond`（对 App 当前用户的收藏态，服务端匿名态恒无意义）
 * 与 `traceId` 是用户/链路维度内容，不透传。
 */
export interface UpstreamPlaylistDetail {
  id: number;
  name: string;
  /** 封面绝对地址。上游可能给 http 明文，适配器必须升级成 https。 */
  pic: string;
  /** 歌单长简介。上游可能给空串。 */
  description: string;
  /** 播放次数。上游叫 `playnum`。 */
  playCount: number;
  /** 歌单内歌曲数。上游叫 `musicCount`（注意与搜索行的 `musicnum` 不同名）。 */
  trackCount: number;
  /** 收藏次数。上游叫 `collectedCnt`。 */
  collectedCount: number;
  creatorId: number;
  creatorName: string;
  /** 创建者头像。上游可能给 http 明文，适配器必须升级成 https。 */
  creatorIcon: string;
  /** 是否私密歌单。 */
  isPrivate: boolean;
}

/**
 * 视频搜索 / 歌词搜索条目：在 [UpstreamSong] 上交叉出两条搜索族端点才有的扩展字段。
 *
 * 刻意用**交叉类型扩展**而不是把 `vid` / `mv` / `lyricSnippet` 直接塞进
 * [UpstreamSong]：这三个字段只有 `search/video/list` 与 `search/lyric/list` 两个
 * 端点的条目才有，塞进共享类型会让腾讯 / 网易的歌曲行永远背着一组「恒缺席」的
 * 字段，读代码的人分不清「可能存在」和「不可能存在」。上游条目本身就是完整歌曲
 * 对象，所以主体复用 [UpstreamSong] —— SongMapper.toSong 的映射链路（playable、
 * `artistId`/`albumId`、高潮区间）对它们原样生效。
 *
 * 扩展字段经适配器的 `toUpstreamSong` 一并透传；普通歌曲条目三键恒为 undefined，
 * `JSON.stringify` 会丢掉 undefined 键，既有路由的下发形状不受影响。
 */
export type UpstreamSongWithExtras = UpstreamSong & {
  /** MV 的播放 ID（上游 `vid`）。≤0 表示该条目不是 MV，路由层据此整行丢弃。 */
  vid?: number;
  /** MV 元数据，来自上游 `mv` 子对象（App 侧取 name/pic/duration）。 */
  mv?: { name: string; pic: string; duration: number };
  /** 歌词搜索条目的纯文本歌词摘要（上游 `lyric` 字段），可能为空串。 */
  lyricSnippet?: string;
};

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

  /**
   * 歌手搜索（分页），产出 `/search` 流的 artist 行与 `GET /search/artists` 分页路由的
   * 行列表。只有上游提供该能力时才实现；调用方必须先判断方法存在。
   *
   * [page] 是 1 基页码，由适配器自行换算成上游的分页参数：`/search` 流的区块行固定传
   * 1（区块只在第 1 页下发），分页路由透传调用方的页码。返回值保持上游排序；
   * [total] 是上游给的**整表总数**，调用方据此算 `hasMore`，不要拿本页条数凑数 ——
   * 整倍数页会误判成「没有下一页」。
   */
  searchArtists?(keyword: string, page: number, limit: number): Promise<{ artists: UpstreamArtist[]; total: number }>;

  /**
   * 专辑搜索（分页），产出 `/search` 流的 album 行与 `GET /search/albums` 分页路由的
   * 行列表。页码与 `total` 的语义同 [searchArtists]。
   */
  searchAlbums?(keyword: string, page: number, limit: number): Promise<{ albums: UpstreamAlbum[]; total: number }>;

  /**
   * 歌单搜索（分页），产出 `GET /search/playlists` 分页路由的行列表。只有上游提供
   * 该能力时才实现；调用方必须先判断方法存在。页码与 `total` 的语义同 [searchArtists]
   * （`total` 是上游整表总数，实测「周杰伦」143）。
   */
  searchPlaylists?(
    keyword: string,
    page: number,
    limit: number,
  ): Promise<{ playlists: UpstreamSearchPlaylist[]; total: number }>;

  /**
   * 视频搜索（分页），产出 `GET /search/videos` 分页路由的行列表。上游条目是完整
   * 歌曲对象（主体与 [searchSongs] 同构）外挂 `vid` / `mv`（见
   * [UpstreamSongWithExtras]）；`vid≤0` 的非 MV 条目由路由层丢弃，适配器照常透传，
   * `total` 才能保持整表总数语义。页码与 `total` 的语义同 [searchArtists]。
   */
  searchVideos?(
    keyword: string,
    page: number,
    limit: number,
  ): Promise<{ songs: UpstreamSongWithExtras[]; total: number }>;

  /**
   * 歌词搜索（分页），产出 `GET /search/lyrics` 分页路由的行列表。上游条目是完整
   * 歌曲对象外挂纯文本摘要 `lyricSnippet`；点击行为与普通歌曲一致（播放），
   * 客户端不需要新交互。页码与 `total` 的语义同 [searchArtists]。
   */
  searchLyrics?(
    keyword: string,
    page: number,
    limit: number,
  ): Promise<{ songs: UpstreamSongWithExtras[]; total: number }>;

  /**
   * 歌手详情，产出 `GET /api/v1/artists/:id` 的 `data.artist`（`source` 由调用方补）。
   * 只有上游提供该能力时才实现；调用方必须先判断方法是否存在。
   * 取不到（上游业务码非 200，即查无此人或上游故障）时返回 `null`，由调用方统一归成
   * 502 —— 与 MV 信息同一套做法，不在这里抛异常，让「取不到」有一个统一的出口。
   */
  getArtistInfo?(artistId: number): Promise<UpstreamArtistDetail | null>;

  /**
   * 歌手的歌曲列表（分页），形状与 [searchSongs] 的单页结果对齐。只有上游提供该能力时
   * 才实现；调用方必须先判断方法存在。[total] 是上游给的整表总数，调用方据此算
   * `hasMore`，不要拿本页条数凑数 —— 整倍数页会误判成「没有下一页」。
   */
  getArtistSongs?(
    artistId: number,
    page: number,
    limit: number,
  ): Promise<{ songs: UpstreamSong[]; total: number }>;

  /**
   * 歌手的专辑列表（分页），行形状与 [searchAlbums] 一致（超长 `info` 简介照旧丢弃）。
   * 只有上游提供该能力时才实现；调用方必须先判断方法存在。
   */
  getArtistAlbums?(
    artistId: number,
    page: number,
    limit: number,
  ): Promise<{ albums: UpstreamAlbum[]; total: number }>;

  /**
   * 相似歌手，行形状与 [searchArtists] 一致，顺序由上游决定。只有上游提供该能力时才
   * 实现；调用方必须先判断方法存在。不分页 —— 详情页只展示一排，翻页没有界面承载。
   */
  getSimilarArtists?(artistId: number): Promise<UpstreamArtist[]>;

  /**
   * 专辑详情，产出 `GET /api/v1/albums/:id` 的 `data.album`（`source` 由调用方补）。
   * 这是**唯一保留上游长简介的契约**：详情页要整段展示它，列表行仍照旧丢弃。
   * 取不到时返回 `null`，理由同 [getArtistInfo]。
   */
  getAlbumInfo?(albumId: number): Promise<UpstreamAlbumDetail | null>;

  /**
   * 专辑的歌曲列表（分页），形状与 [getArtistSongs] 一致。只有上游提供该能力时才实现；
   * 调用方必须先判断方法存在。
   */
  getAlbumSongs?(
    albumId: number,
    page: number,
    limit: number,
  ): Promise<{ songs: UpstreamSong[]; total: number }>;

  /**
   * 在线歌单详情，产出 `GET /online-playlists/:id` 的 `data.playlist`（`source` 由调用方补）。
   *
   * 这是**音源侧的公开歌单**（搜索歌单标签点进去看到的），与账号自己的云端歌单
   * （playlists 模块、`/api/v1/playlists/:id`）完全是两套东西，路由前缀刻意分开。
   * [sourceMarker] 是搜索行 [UpstreamSearchPlaylist.sourceMarker] 带回来的数字标记，
   * 上游要求原样回传 —— 调用链是「搜索拿到标记 → 客户端点歌单 → 详情请求带上标记」。
   * 取不到时返回 `null`，理由同 [getArtistInfo]。
   * 只有上游提供该能力时才实现；调用方必须先判断方法存在。
   */
  getPlaylistInfo?(
    playlistId: number,
    sourceMarker: number,
  ): Promise<UpstreamPlaylistDetail | null>;

  /**
   * 在线歌单的歌曲列表（分页），形状与 [getArtistSongs] 一致；[sourceMarker] 的语义
   * 同 [getPlaylistInfo]。只有上游提供该能力时才实现；调用方必须先判断方法存在。
   */
  getPlaylistSongs?(
    playlistId: number,
    page: number,
    limit: number,
    sourceMarker: number,
  ): Promise<{ songs: UpstreamSong[]; total: number }>;

  /** 单曲信息与**真实可用**的音质档位。 */
  requestSongInfo(key: SongKey): Promise<UpstreamSongInfo>;

  getMvInfo?(musicId: number | string): Promise<unknown | null>;

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

export type { RichLyric, SearchResult, UpstreamLink, UpstreamSong, UpstreamSongInfo };
