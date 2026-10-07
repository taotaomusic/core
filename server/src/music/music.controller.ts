import { Controller, Get, Param, ParseIntPipe, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { RawResponse } from "../common/decorators/raw-response.decorator";
import type { SessionUser } from "../common/request.types";
import { AppConfigService } from "../config/app-config.service";
import { FavoritesRepository } from "../favorites/favorites.repository";
import { MUSIC_SOURCES } from "../upstream/music-source.client";
import type { MusicSource, SongKey } from "../upstream/music-source.client";
import { MusicSourceRegistry } from "../upstream/music-source.registry";
import type { UpstreamSong } from "../upstream/tencent.client";
import type { SearchSource } from "./search.service";
import { SearchService } from "./search.service";
import { SongMapper } from "./song.mapper";
import { StreamService } from "./stream.service";

/** 搜索单页默认条数。客户端会显式要 60，这里的默认值只对手工调试生效。 */
const DEFAULT_PAGE_SIZE = 60;
const MAX_PAGE_SIZE = 60;
const MAX_INFO_BATCH_SIZE = 60;
const DEFAULT_SUGGESTION_SIZE = 10;
const MAX_SUGGESTION_SIZE = 20;
/** 歌手/专辑详情的歌曲列表默认条数，与对外契约示例（`?num=30`）一致。 */
const DEFAULT_DETAIL_SONG_PAGE_SIZE = 30;
/** 歌手专辑列表默认条数，与对外契约示例（`?num=20`）一致。 */
const DEFAULT_DETAIL_ALBUM_PAGE_SIZE = 20;

/**
 * 搜索、播放与歌词。
 *
 * 搜索、播放转发、歌词三个端点自己写响应体（NDJSON 流、音频流、纯文本），标了 [RawResponse]；
 * `/link` 和 `/info` 是普通 JSON，走统一信封。
 *
 * 上游一律经 [MusicSourceRegistry] 取，**这个文件里不允许出现按 `source` 分支的逻辑**：
 * 判断该由适配器自己的能力标记（`numericIdOnly` / `supportsTierProbe`）表达，
 * 否则每加一个音源就要回来改一次。
 */
@Controller()
export class MusicController {
  constructor(
    private readonly config: AppConfigService,
    private readonly search: SearchService,
    private readonly stream: StreamService,
    private readonly registry: MusicSourceRegistry,
    private readonly mapper: SongMapper,
    private readonly favorites: FavoritesRepository,
  ) {}

  /**
   * 搜索框联想词。返回普通 JSON 信封，`data` 是按上游顺序排列的字符串数组。
   * 当前只有酷我（波点）提供此能力，因此默认 source=kuwo。
   */
  @Get("search/suggestions")
  async searchSuggestions(
    @Query("keyword") keyword?: string,
    @Query("limit") limit?: string,
    @Query("source") source?: string,
  ): Promise<string[]> {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const client = this.registry.of(this.sourceOf(source ?? "kuwo"));
    if (!client.searchSuggestions) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持搜索联想`);
    }
    return client.searchSuggestions(
      trimmed,
      Math.min(MAX_SUGGESTION_SIZE, this.positiveIntOr(limit, DEFAULT_SUGGESTION_SIZE)),
    );
  }

  /** 获取官方搜索首页热词，默认取酷我（波点）数据。 */
  @Get("search/hot")
  async searchHotKeywords(
    @Query("limit") limit?: string,
    @Query("source") source?: string,
  ) {
    const client = this.registry.of(this.sourceOf(source ?? "kuwo"));
    if (!client.searchHotKeywords) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持热搜`);
    }
    return client.searchHotKeywords(
      Math.min(MAX_SUGGESTION_SIZE, this.positiveIntOr(limit, DEFAULT_SUGGESTION_SIZE)),
    );
  }

  @RawResponse()
  @Get("search")
  async searchSongs(
    @Req() request: Request,
    @Res() response: Response,
    @CurrentUser() user: SessionUser,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("limit") limit?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    // 客户端历史参数名是 num，v3 上游叫 limit，两个都接受。
    await this.search.stream(
      response,
      user.id,
      trimmed,
      this.positiveIntOr(page, 1),
      Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num ?? limit, DEFAULT_PAGE_SIZE)),
      this.mapper.qualityOf(quality),
      this.playBaseOf(request),
      this.searchSourceOf(source),
    );
  }

  // ---------- 搜索页标签：歌手 / 专辑分页搜索（2026-10 新增）----------
  //
  // `/search` 流里的 artist / album 区块是搜索落地页顶部的展示位（最多 3 / 6 条、
  // 仅第 1 页）；搜索页「歌手 / 专辑」独立标签的完整列表走这两条分页路由。要登录
  // （未标 @Public，走全局访问令牌守卫）；`source` 缺省 kuwo、`source=all` 与未知音源
  // 4001、音源未实现该能力 4007 —— 全部照详情族 [detailSourceOf] 的既有写法。
  // 行结构与 `/search` 流的实体行**完全同构**（含 source）；`total` 用上游 `data.total`
  // 的整表总数（不拿本页条数凑数），`hasMore = page * num < total`，分页信封与详情族
  // （`artists/:id/albums` 等）一致。

  /**
   * 歌手分页搜索。行结构与 `/search` 流的 artist 行完全同构，客户端原样复用行组件；
   * `data.source` 是契约字段，适配器不含它，由这里按当前音源统一补上。
   * `num` 缺省 30 是对外契约钉死的（与详情族歌曲列表共用同一个常量）。
   */
  @Get("search/artists")
  async searchArtists(
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("source") source?: string,
  ) {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.searchArtists) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持歌手搜索`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.searchArtists(trimmed, resolvedPage, resolvedNum);
    return {
      artists: detail.artists.map((artist) => ({ ...artist, source: client.source })),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 专辑分页搜索。行结构与 `/search` 流的 album 行完全同构；其余约定同 [searchArtists]。
   */
  @Get("search/albums")
  async searchAlbums(
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("source") source?: string,
  ) {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.searchAlbums) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持专辑搜索`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.searchAlbums(trimmed, resolvedPage, resolvedNum);
    return {
      albums: detail.albums.map((album) => ({ ...album, source: client.source })),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  // ---------- 搜索页新标签：歌单 / 视频 / 歌词分页搜索（2026-10 新增）----------
  //
  // 与上面歌手 / 专辑两条同一套约定：要登录（未标 @Public，走全局访问令牌守卫）；
  // keyword 空 4001；source 缺省 kuwo、`source=all` 与未知音源 4001（走
  // [detailSourceOf]，聚合模式没有单一 client 可分派）；音源未实现该能力 4007；
  // num 缺省 30、上限 60；`meta = {page, num, total, hasMore}`，total 用上游
  // `data.total` 的整表总数（实测「周杰伦」歌单 143 / 视频 603、「晴天」歌词 100），
  // hasMore = page * num < total。上游 `search/playlist|video|lyric/list` 与
  // search/artist/list 同族：pn 0 基、偏移 `pn × rn`，协议层过 `upstreamPage()` 换算。

  /**
   * 歌单分页搜索。行结构即对外契约：上游的 `creator_name` / `musicnum` / `playnum`
   * 已在协议层改名 `creator` / `trackCount` / `playCount`，http 明文的 `pic` 已升级
   * https；`creator_id` / `sltype` / `hitcontent` 与上游的数字 `source` 标记不透传。
   * `data.source` 是契约字段，适配器不含它，由这里按当前音源统一补上。
   * 点击行为是进歌单详情页，详情接口另行接入。
   */
  @Get("search/playlists")
  async searchPlaylists(
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("source") source?: string,
  ) {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.searchPlaylists) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持歌单搜索`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.searchPlaylists(trimmed, resolvedPage, resolvedNum);
    return {
      playlists: detail.playlists.map((playlist) => ({ ...playlist, source: client.source })),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 视频分页搜索。上游条目是完整歌曲对象，先过与歌曲行**同一条映射链路**
   * （[toClientSongs]，playable / 身份语义与搜索歌曲一致），再从中挑选契约字段并
   * 就地改名（title→name、coverUrl→cover）；`durationSeconds` 取上游的秒数，
   * 不走歌曲行的 mm:ss 字符串。`mv*` 三键取上游 `mv` 子对象（name/pic/duration）。
   *
   * **`vid≤0` 的条目在这里整行丢弃**（不是 MV）—— 过滤放在映射之前，收藏批量
   * 查询就不会为丢掉的行白查一次库；`meta.total` 仍是上游整表总数，不受过滤影响
   * （被滤后本页条数可能少于 num，客户端按 hasMore 翻页即可）。
   *
   * `quality` 上游不需要：它只进 SongMapper 的 `audioUrl` 占位地址生成，而本路由的
   * 契约行不透传 `audioUrl`，带它只是为了与歌曲行保持同一条链路。点击行为是播 MV。
   */
  @Get("search/videos")
  async searchVideos(
    @Req() request: Request,
    @CurrentUser() user: SessionUser,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ) {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.searchVideos) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持视频搜索`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.searchVideos(trimmed, resolvedPage, resolvedNum);
    // vid≤0 表示该条目不是 MV（上游对纯音频条目给 0 或缺失），整行丢弃。
    const mvItems = detail.songs.filter((item) => Number(item.vid) > 0);
    const mapped = await this.toClientSongs(
      request,
      user,
      selectedSource,
      mvItems,
      this.mapper.qualityOf(quality),
    );
    return {
      videos: mvItems.map((item, index) => {
        const song = mapped[index];
        return {
          source: song.source,
          id: song.id,
          name: song.title,
          artist: song.artist,
          // 歌手 / 专辑 ID 上游没给时整个键缺席（JSON.stringify 丢 undefined），绝不发 0。
          artistId: song.artistId,
          album: song.album,
          albumId: song.albumId,
          cover: song.coverUrl,
          durationSeconds: Number(item.interval) || 0,
          vid: Number(item.vid),
          mvName: item.mv?.name ?? "",
          mvCover: item.mv?.pic ?? "",
          mvDurationSeconds: item.mv?.duration ?? 0,
          playable: song.playable,
        };
      }),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 歌词分页搜索。每项与 `/search` 的 song data **完全同构**（完整 [toClientSongs]
   * 输出：自拼 `audioUrl`、相对路径 `lyricUrl`、`favorited` 批量查询，一样不少），
   * 客户端可直接复用歌曲行组件与点播逻辑，只是外挂一个纯文本摘要 `lyricSnippet`
   * （上游 `lyric` 字段，可能为空串）。点击行为就是播放该歌，客户端无需新交互。
   */
  @Get("search/lyrics")
  async searchLyrics(
    @Req() request: Request,
    @CurrentUser() user: SessionUser,
    @Query("keyword") keyword?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ) {
    const trimmed = (keyword ?? "").trim();
    if (!trimmed) throw ApiErrors.badRequest(4001, "请输入搜索关键词");
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.searchLyrics) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持歌词搜索`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.searchLyrics(trimmed, resolvedPage, resolvedNum);
    const mapped = await this.toClientSongs(
      request,
      user,
      selectedSource,
      detail.songs,
      this.mapper.qualityOf(quality),
    );
    return {
      // 行序与上游 resultList 一一对应，按下标回填摘要即可（toClientSongs 不改序）。
      songs: mapped.map((song, index) => ({
        ...song,
        lyricSnippet: detail.songs[index]?.lyricSnippet ?? "",
      })),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  // ---------- 歌手与专辑详情（2026-10 新增）----------
  //
  // 数据来源是 `/search` 流里的 artist / album 行：客户端拿到 `source` + `id` 后回这里
  // 取详情与列表。详情族全部要登录（未标 @Public，走全局访问令牌守卫），`source`
  // 缺省 kuwo —— 这两族详情当前只有酷我（波点）提供；`source=all` 一律 4001 拒绝
  // （详情必须有确定的音源，聚合模式没有单一 client 可分派，见 [detailSourceOf]）。
  //
  // 列表统一 `{page, num, total, hasMore}` 分页信封：`total` 用上游给的**整表总数**，
  // 不拿本页条数凑数（整倍数页会把「有下一页」误判成「没有」），
  // `hasMore = page * num < total`。歌曲列表的每一项与 `/search` 的 song data
  // **完全同构**（走 [SongMapper.toSong] 同一条链路），客户端原样复用歌曲行组件。

  /**
   * 歌手详情。`data.artist` 的字段就是下发契约名 —— 协议层已把上游的
   * `fansCnt` / `musicCnt` / `albumCnt` 改成 `fansCount` / `musicCount` / `albumCount`；
   * `guardDesc`（守护标语）与 `isshowtype`（上游展示开关）是波点运营内容，不透传。
   */
  @Get("artists/:id")
  async artistInfo(
    @Param("id") id?: string,
    @Query("source") source?: string,
  ) {
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.getArtistInfo) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持歌手详情`);
    }
    const artist = await client.getArtistInfo(this.detailIdOf(id, "歌手"));
    if (!artist) throw ApiErrors.upstream("歌手详情不可用");
    // data.source 是契约字段，适配器不含它，与 /search 的实体行同一套补法。
    return { artist: { ...artist, source: client.source } };
  }

  /**
   * 歌手的歌曲列表。每项与 `/search` 的 song data 完全同构：playBase 拼自家播放地址、
   * quality 归一、mid/type 下发、favorited 批量查询，全部照抄 search 的现有做法
   * （见 [toClientSongs]）。上游的整表总数放在 `meta.total`，翻页判断客户端自己做。
   */
  @Get("artists/:id/songs")
  async artistSongs(
    @Req() request: Request,
    @CurrentUser() user: SessionUser,
    @Param("id") id?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.getArtistSongs) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持歌手歌曲列表`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.getArtistSongs(this.detailIdOf(id, "歌手"), resolvedPage, resolvedNum);
    return {
      songs: await this.toClientSongs(
        request,
        user,
        selectedSource,
        detail.songs,
        this.mapper.qualityOf(quality),
      ),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 排行榜目录：模块分组 + 榜单简述 + 前 5 首预览。行形状与 `/search` 的 song data
   * 同构；目录是首屏预览，收藏态批量查询刻意跳过（`skipFavorites`）。
   */
  @Get("rankings")
  async rankings(@Req() request: Request, @Query("source") source?: string) {
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.getRankingGroups) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持排行榜`);
    }
    const groups = await client.getRankingGroups();
    if (!groups) throw ApiErrors.upstream("榜单目录不可用");
    return {
      groups: await Promise.all(
        groups.map(async (group) => ({
          moduleName: group.moduleName,
          bangs: await Promise.all(
            group.bangs.map(async (bang) => ({
              ...bang,
              source: client.source,
              preview: await this.toClientSongs(
                request,
                undefined,
                client.source,
                bang.preview,
                this.mapper.qualityOf(undefined),
                true,
              ),
            })),
          ),
        })),
      ),
    };
  }

  /**
   * 榜单歌曲。**上游不支持分页**——固定返回全部（匿名权益约 20 首），本路由刻意
   * 不收 page/num；`meta.total` 是上游写死的展示值（实测恒 100），仅用于展示。
   */
  @Get("rankings/:id/songs")
  async rankingSongs(
    @Req() request: Request,
    @CurrentUser() user: SessionUser,
    @Param("id") id?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.getRankingSongs) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持排行榜`);
    }
    const detail = await client.getRankingSongs(this.detailIdOf(id, "榜单"));
    if (!detail) throw ApiErrors.upstream("榜单不可用");
    return {
      ranking: { ...detail.ranking, source: selectedSource },
      songs: await this.toClientSongs(
        request,
        user,
        selectedSource,
        detail.songs,
        this.mapper.qualityOf(quality),
      ),
      meta: {
        page: 1,
        // 上游不支持分页，这里不是「每页条数」而是当前返回的全部条数。
        num: detail.songs.length,
        // total 是上游写死的展示值（实测恒 100），不是真实条数。
        total: detail.ranking.total,
        hasMore: false,
      },
    };
  }

  /**
   * 歌手的专辑列表。行形状与 `/search` 的 album 行同构（上游的 `musicCount` 已在
   * 协议层改成 `songCount`，超长 `info` 简介与 `lastPlayTime` / `isshow` 照旧丢弃）。
   */
  @Get("artists/:id/albums")
  async artistAlbums(
    @Param("id") id?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.getArtistAlbums) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持歌手专辑列表`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_ALBUM_PAGE_SIZE));
    const detail = await client.getArtistAlbums(this.detailIdOf(id, "歌手"), resolvedPage, resolvedNum);
    return {
      // data.source 是契约字段，适配器不含它，由这里按当前音源统一补上。
      albums: detail.albums.map((album) => ({ ...album, source: selectedSource })),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 相似歌手。不分页 —— 详情页只展示一排，翻页没有界面承载。行形状与
   * `/search` 的 artist 行同构，顺序保持上游返回。
   */
  @Get("artists/:id/similar")
  async similarArtists(
    @Param("id") id?: string,
    @Query("source") source?: string,
  ) {
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.getSimilarArtists) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持相似歌手`);
    }
    const artists = await client.getSimilarArtists(this.detailIdOf(id, "歌手"));
    // data.source 是契约字段，适配器不含它，由这里按当前音源统一补上。
    return { artists: artists.map((artist) => ({ ...artist, source: client.source })) };
  }

  /**
   * 专辑详情。这是唯一保留上游长简介的对外契约：`data.album.desc` 就是上游的
   * `info`（列表行照旧丢弃它），专辑详情页要整段展示。
   */
  @Get("albums/:id")
  async albumInfo(
    @Param("id") id?: string,
    @Query("source") source?: string,
  ) {
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.getAlbumInfo) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持专辑详情`);
    }
    const album = await client.getAlbumInfo(this.detailIdOf(id, "专辑"));
    if (!album) throw ApiErrors.upstream("专辑详情不可用");
    // data.source 是契约字段，适配器不含它，与 /search 的实体行同一套补法。
    return { album: { ...album, source: client.source } };
  }

  /**
   * 专辑的歌曲列表。条目映射、收藏批量查询与分页信封同 [artistSongs]，
   * 只是数据源端点与路径参数不同。
   */
  @Get("albums/:id/songs")
  async albumSongs(
    @Req() request: Request,
    @CurrentUser() user: SessionUser,
    @Param("id") id?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("quality") quality?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.getAlbumSongs) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持专辑歌曲列表`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.getAlbumSongs(this.detailIdOf(id, "专辑"), resolvedPage, resolvedNum);
    return {
      songs: await this.toClientSongs(
        request,
        user,
        selectedSource,
        detail.songs,
        this.mapper.qualityOf(quality),
      ),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 在线歌单详情。这是**音源侧的公开歌单**（搜索歌单标签点进去看到的），与账号
   * 自己的云端歌单（playlists 模块、`/api/v1/playlists/:id`）完全是两套东西 ——
   * 那套路由只认当前账号的歌单，拿不到波点在线歌单的歌曲，所以这里用
   * `online-playlists` 前缀刻意区分，避免与云端的增删改接口撞形。
   *
   * `sourceMarker` 是搜索行带回的数字标记（`/search/playlists` 的 `sourceMarker`
   * 字段），上游要求**原样回传**；缺省 4 是搜索条目的实测常数（协议层说明见
   * `bodian.client.ts`），只服务于不带标记的直连调用，客户端应始终显式携带。
   */
  @Get("online-playlists/:id")
  async onlinePlaylistInfo(
    @Param("id") id?: string,
    @Query("sourceMarker") sourceMarker?: string,
    @Query("source") source?: string,
  ) {
    const client = this.registry.of(this.detailSourceOf(source));
    if (!client.getPlaylistInfo) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持在线歌单详情`);
    }
    const playlist = await client.getPlaylistInfo(
      this.detailIdOf(id, "歌单"),
      this.positiveIntOr(sourceMarker, 4),
    );
    if (!playlist) throw ApiErrors.upstream("歌单详情不可用");
    // data.source 是契约字段，适配器不含它，与 /search 的实体行同一套补法。
    return { playlist: { ...playlist, source: client.source } };
  }

  /**
   * 在线歌单的歌曲列表。条目映射、收藏批量查询与分页信封同 [artistSongs]，
   * 只是数据源端点与路径参数不同；`sourceMarker` 语义同 [onlinePlaylistInfo]。
   */
  @Get("online-playlists/:id/songs")
  async onlinePlaylistSongs(
    @Req() request: Request,
    @CurrentUser() user: SessionUser,
    @Param("id") id?: string,
    @Query("page") page?: string,
    @Query("num") num?: string,
    @Query("quality") quality?: string,
    @Query("sourceMarker") sourceMarker?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.detailSourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.getPlaylistSongs) {
      throw ApiErrors.badRequest(4007, `${client.displayName}不支持在线歌单歌曲列表`);
    }
    const resolvedPage = this.positiveIntOr(page, 1);
    const resolvedNum = Math.min(MAX_PAGE_SIZE, this.positiveIntOr(num, DEFAULT_DETAIL_SONG_PAGE_SIZE));
    const detail = await client.getPlaylistSongs(
      this.detailIdOf(id, "歌单"),
      resolvedPage,
      resolvedNum,
      this.positiveIntOr(sourceMarker, 4),
    );
    return {
      songs: await this.toClientSongs(
        request,
        user,
        selectedSource,
        detail.songs,
        this.mapper.qualityOf(quality),
      ),
      meta: {
        page: resolvedPage,
        num: resolvedNum,
        total: detail.total,
        hasMore: resolvedPage * resolvedNum < detail.total,
      },
    };
  }

  /**
   * 解析播放地址。
   *
   * 从搜索里拆出来的独立接口：搜索只给元信息，客户端点播时才来要地址。
   * 返回的是**上游直链**，客户端直接拉 QQ 的 CDN，音频字节不再经过本服务。
   *
   * `mid` 与 `type` 由搜索结果原样带回：`songID` 为 0 的歌只能靠 `mid` 解析，
   * 而 `type` 不带会让部分歌曲拿不到地址。
   *
   * 拿不到地址时按既有约定走 **502**，绝不能 401 —— 那会触发客户端的续期重放，
   * 二次失败后把用户踢回登录页。
   */
  @Get("songs/:id/link")
  async link(
    @Param("id", ParseIntPipe) id: number,
    @Query("quality") quality?: string,
    @Query("mid") mid?: string,
    @Query("type") type?: string,
    @Query("source") source?: string,
  ) {
    const requested = this.mapper.qualityOf(quality);
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const client = this.registry.of(selectedSource);
    // 先问一次可用档位，能直接命中真实存在的档，省掉逐级试错的多次请求。
    // info 自己失败不算致命，退回音质阶梯。不支持分档的音源跳过这一步，
    // 否则只是白白多打一次上游。
    const available = client.supportsTierProbe
      ? await client
          .requestSongInfo(key)
          .then((info) => new Set(info.tiers.filter((tier) => tier.size > 0).map((tier) => tier.type)))
          .catch(() => undefined)
      : undefined;

    const link = await client.resolveLink(
      { ...key, type: this.optionalInt(type) },
      requested,
      undefined,
      available,
    );
    return {
      songId: id,
      url: link.url,
      quality: link.quality,
      requestedQuality: requested,
      kbps: link.kbps,
      fallback: link.quality !== requested,
    };
  }

  /**
   * 歌曲信息与可用音质档位。
   *
   * 客户端的音质选择器用它只列出这首歌**真实存在**的档位，避免选了无损却静默降到
   * 标准音质；`size` 同时用来提示流量。
   */
  @Get("songs/:id/info")
  async info(
    @Param("id", ParseIntPipe) id: number,
    @Query("mid") mid?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.sourceOf(source);
    return this.songInfo(this.songKeyOf(id, mid, selectedSource), selectedSource);
  }

  @Get("songs/:id/mv")
  async mv(
    @Param("id", ParseIntPipe) id: number,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.sourceOf(source);
    const client = this.registry.of(selectedSource);
    if (!client.getMvInfo) throw ApiErrors.badRequest(4001, `${client.displayName}暂不支持 MV`);
    const mv = await client.getMvInfo(id);
    if (!mv) throw ApiErrors.upstream("MV 信息不可用");
    return mv;
  }

  /**
   * 最近播放补全资料的批量入口。客户端一次最多请求 60 首，服务端分批并发访问上游，
   * 避免新设备拉 500 条历史时发出 500 个移动端 HTTP 请求。
   *
   * 单首上游资料失败不应让整个批次退化为移动端 N 次逐首请求：成功项照常返回，
   * 缺失项由客户端以本地可播放占位项展示，下一次刷新再尝试补全。
   */
  @Get("songs/batch-info")
  async infoBatch(
    @Query("ids") ids?: string,
    @Query("mids") mids?: string,
    @Query("source") source?: string,
  ) {
    const selectedSource = this.sourceOf(source);
    const client = this.registry.of(selectedSource);
    const uniqueIds = [...new Set((ids ?? "").split(",").map((value) => Number(value.trim())))]
      .filter((value) => Number.isInteger(value) && value > 0);
    const uniqueMids = [
      ...new Set(
        (mids ?? "")
          .split(",")
          .map((value) => value.trim())
          .filter(Boolean),
      ),
    ];
    if (client.numericIdOnly && uniqueMids.length > 0) {
      throw ApiErrors.badRequest(4001, `${client.displayName}歌曲必须提供正整数 ID`);
    }
    const keys: SongKey[] = [
      ...uniqueIds.map((id) => ({ id })),
      ...uniqueMids.map((mid) => ({ mid })),
    ];
    if (keys.length === 0) throw ApiErrors.badRequest(4001, "请提供歌曲 ID 或 mid");
    if (keys.length > MAX_INFO_BATCH_SIZE) {
      throw ApiErrors.badRequest(4001, `单次最多查询 ${MAX_INFO_BATCH_SIZE} 首歌曲`);
    }
    const songs = [];
    for (let index = 0; index < keys.length; index += 8) {
      const batch = await Promise.allSettled(
        keys.slice(index, index + 8).map((key) => this.songInfo(key, selectedSource)),
      );
      songs.push(...batch.flatMap((result) => (result.status === "fulfilled" ? [result.value] : [])));
    }
    return { songs };
  }

  private async songInfo(key: SongKey, source: MusicSource = "tencent") {
    const info = await this.registry.of(source).requestSongInfo({
      id: key.id,
      mid: key.mid,
    });
    return {
      songId: info.songID,
      mid: info.songMID,
      title: info.title,
      artist: info.singer,
      album: info.album,
      coverUrl: info.cover,
      vip: info.pay.includes("付费"),
      durationSeconds: info.interval,
      // 音源内的歌手 / 专辑 ID（仅酷我提供）：老队列 / 收藏恢复的歌曲没有这组 ID，
      // 客户端在播放期用它回填，「查看歌手 / 查看专辑」才能对非搜索来源生效。
      artistId: info.artistId,
      albumId: info.albumId,
      refrainStartMs: info.refrainStartMs,
      refrainEndMs: info.refrainEndMs,
      // size 为 0 的档位这首歌没有，直接不下发，客户端不用自己过滤。
      qualities: info.tiers
        .filter((tier) => tier.size > 0)
        .map((tier) => ({ quality: tier.type, label: tier.label, size: tier.size })),
    };
  }

  @RawResponse()
  @Get("songs/:id/play")
  async play(
    @Req() request: Request,
    @Res() response: Response,
    @Param("id", ParseIntPipe) id: number,
    @Query("quality") quality?: string,
    @Query("mid") mid?: string,
    @Query("type") type?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const target = await this.stream.resolvePlayUrl(
      { ...key, type: this.optionalInt(type) },
      this.mapper.qualityOf(quality),
      selectedSource,
    );
    await this.stream.proxy(request, response, target);
  }

  /**
   * 歌词。
   *
   * 默认返回**纯 LRC 文本** —— 装机的旧客户端把响应体直接当歌词展示，不能改成 JSON。
   * 只有显式带 `format=json` 才给出逐字时间轴（yrc）与翻译。
   *
   * 「没有歌词」由上游抛出并归成 502，**绝不能返回 401**：那会触发客户端的续期重放，
   * 二次失败后把用户踢回登录页。
   */
  @RawResponse()
  @Get("songs/:id/lyrics")
  async lyrics(
    @Res() response: Response,
    @Param("id", ParseIntPipe) id: number,
    @Query("format") format?: string,
    @Query("mid") mid?: string,
    @Query("source") source?: string,
  ): Promise<void> {
    const selectedSource = this.sourceOf(source);
    const key = this.songKeyOf(id, mid, selectedSource);
    const rich = await this.registry.of(selectedSource).requestLyric(key);
    if (format === "json") {
      response.status(200).json({ code: 0, message: "success", data: rich });
      return;
    }
    response.writeHead(200, { "content-type": "text/plain; charset=utf-8" });
    response.end(rich.lrc || rich.yrc);
  }

  /**
   * 拼装 `audioUrl` 的基地址。
   * 优先用配置的对外地址，未配置时按请求推导 —— 与安装包下载地址同一套逻辑。
   */
  private playBaseOf(request: Request): string {
    if (this.config.publicBaseUrl) return this.config.publicBaseUrl;
    const forwarded = String(request.headers["x-forwarded-proto"] ?? "").split(",")[0].trim();
    const secure = (request.socket as { encrypted?: boolean }).encrypted === true;
    return `${forwarded || (secure ? "https" : "http")}://${request.headers.host ?? "localhost"}`;
  }

  /**
   * 查询参数转正整数，非法或缺省时用兜底值。
   *
   * 不能直接 `Math.max(1, Number(value))`：`Number("abc")` 是 NaN，而 `Math.max` 会
   * 把 NaN 原样传下去，最终拼出字面量 `page=NaN` 打给上游。
   */
  private positiveIntOr(value: string | undefined, fallback: number): number {
    if (value === undefined || value.trim() === "") return fallback;
    const parsed = Number(value);
    return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
  }

  private optionalInt(value: string | undefined): number | undefined {
    if (value === undefined || value.trim() === "") return undefined;
    const parsed = Number(value);
    return Number.isInteger(parsed) ? parsed : undefined;
  }

  /**
   * 单曲接口统一校验身份。
   *
   * **`id` 必须和 `source` 一起用**：数字 ID 只在所属音源内有意义，同一个数字在
   * QQ 和酷我里是两首完全不同的歌，混用不会报错、只会安静地返回另一首歌。
   *
   * 哪些音源允许 mid-only 由适配器的 `numericIdOnly` 决定，这里不写按音源的分支 ——
   * 新增音源时只需要在适配器上标一个标记。
   */
  private songKeyOf(id: number, mid: string | undefined, source: MusicSource): SongKey {
    const client = this.registry.of(source);
    const normalizedMid = mid?.trim();
    if (Number.isInteger(id) && id > 0) {
      return { id, ...(normalizedMid ? { mid: normalizedMid } : {}) };
    }
    if (!client.numericIdOnly && normalizedMid) return { mid: normalizedMid };
    throw ApiErrors.badRequest(
      4001,
      client.numericIdOnly ? `${client.displayName}歌曲必须提供正整数 ID` : "请提供歌曲 ID 或 mid",
    );
  }

  /**
   * 解析音源参数。默认 QQ 音乐，未知来源必须明确拒绝 ——
   * 静默兜底会把别的音源的 ID 发给 QQ 上游。
   */
  private sourceOf(value: string | undefined): MusicSource {
    if (value === undefined || value === "") return "tencent";
    const matched = MUSIC_SOURCES.find((source) => source === value);
    if (!matched) throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    return matched;
  }

  /** 搜索默认聚合；其余单曲接口必须指定为某一个实际来源。 */
  private searchSourceOf(value: string | undefined): SearchSource {
    if (value === undefined || value === "" || value === "all") return "all";
    return this.sourceOf(value);
  }

  /**
   * 歌手/专辑详情族的音源解析。缺省 kuwo —— 这两族详情当前只有酷我（波点）提供，
   * 而且客户端是从 `/search` 的 artist / album 行（必带 source）跳过来的，
   * 缺省值只服务于手工调试。`source=all` 由 [sourceOf] 4001 拒绝：详情必须有
   * 确定的音源，聚合模式没有单一 client 可分派。
   */
  private detailSourceOf(value: string | undefined): MusicSource {
    const trimmed = value?.trim();
    return this.sourceOf(trimmed ? trimmed : "kuwo");
  }

  /**
   * 详情族的路径 ID 校验：必须是正整数，否则 4001。
   *
   * **不能像单曲接口那样套 `ParseIntPipe`**：它抛的是框架异常，统一异常过滤按状态码
   * 推导成 4005，而这族接口的契约钉死 4001（与未知音源同一个码，客户端按同一套
   * 「参数不合法」提示处理）。空串、负数、小数、非数字一律拦在这里。
   */
  private detailIdOf(value: string | undefined, label: string): number {
    const parsed = Number(value);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw ApiErrors.badRequest(4001, `${label}必须提供正整数 ID`);
    }
    return parsed;
  }

  /**
   * 把上游歌曲列表映射成与 `/search` 的 song data 完全同构的客户端模型。
   *
   * 收藏批量查询、playBase、quality 与身份选择**逐行照抄 search**（[SearchService.stream]）：
   * - favorited 走 [FavoritesRepository.favoritedIds] 一次查完本页全部 ID，
   *   绝不逐首查库；
   * - 无用户身份时整段跳过查询，favorited 全 false（绝不能把 undefined 传进 SQL）；
   * - 身份优先正数字 ID、缺了退回 mid，与收藏/队列共用同一稳定身份。
   */
  private async toClientSongs(
    request: Request,
    user: SessionUser | undefined,
    source: MusicSource,
    items: UpstreamSong[],
    quality: number,
    /** 首屏预览场景置 true：跳过收藏批量查询（榜单目录的 5 首预览不需要收藏态）。 */
    skipFavorites = false,
  ): Promise<Array<ReturnType<SongMapper["toSong"]>>> {
    const client = this.registry.of(source);
    const favorited = user === undefined || skipFavorites
      ? new Set<string>()
      : await this.favorites.favoritedIds(
          user.id,
          source,
          items.flatMap((item) => {
            const identity = this.songIdentityOf(item);
            return identity ? [identity] : [];
          }),
        );
    return items.map((item) => {
      const identity = this.songIdentityOf(item);
      return this.mapper.toSong(
        item,
        this.playBaseOf(request),
        quality,
        identity !== undefined && favorited.has(identity),
        source,
        !client.numericIdOnly,
      );
    });
  }

  /** 收藏与队列使用同一稳定身份：优先正数字 ID，否则退回上游 mid。与 search 的 identityOf 同一套。 */
  private songIdentityOf(item: UpstreamSong): string | undefined {
    const id = Number(item.songID);
    if (Number.isInteger(id) && id > 0) return String(id);
    const mid = String(item.songMID ?? "").trim();
    return mid || undefined;
  }
}
