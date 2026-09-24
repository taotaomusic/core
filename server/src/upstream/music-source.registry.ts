import { Injectable } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { KuwoClient } from "./kuwo.client";
import { AGGREGATED_SOURCES } from "./music-source.client";
import type { MusicSource, MusicSourceClient, MusicSourceCredentialManager } from "./music-source.client";
import { NeteaseClient } from "./netease.client";
import { TencentClient } from "./tencent.client";

/**
 * 音源分派。
 *
 * 这是全项目**唯一**决定「某个 source 该走哪个上游」的地方。
 *
 * 在此之前这条判断散在 6 处三元表达式里（`music/` 3 处、`shares/` 3 处），
 * 而且默认分支是腾讯音乐 —— 新增一个音源时任何一处漏改，都会把新音源的 ID
 * 当成腾讯的 ID 发出去。这类错误不抛异常、不进日志，只表现为「这首歌没声音」。
 *
 * 现在调用方一律 `registry.of(source)`，漏改只可能是「忘了注册」，而那会在
 * 构造注册表时就暴露出来（见 `MUSIC_SOURCES` 与实际注册项的比对）。
 */
@Injectable()
export class MusicSourceRegistry {
  private readonly bySource: Map<MusicSource, MusicSourceClient>;

  /**
   * 具备「后台登录」能力的音源。
   *
   * 与 `bySource` 分开维护：播放链路要求每个音源都在 `bySource` 里，而凭据管理
   * 是可选能力。这张表是**唯一**回答「这个音源能不能在后台登录账号」的地方 ——
   * 后台页面据此决定是否显示登录表单，而不是让前端去硬编码「只有酷我能登录」。
   */
  private readonly credentialManagers: Map<MusicSource, MusicSourceCredentialManager>;

  constructor(tencent: TencentClient, netease: NeteaseClient, kuwo: KuwoClient) {
    this.bySource = new Map<MusicSource, MusicSourceClient>([
      [tencent.source, tencent],
      [netease.source, netease],
      [kuwo.source, kuwo],
    ]);
    // 目前只有酷我支持手机号 + 短信验证码登录；腾讯/网易的凭据不走这条路。
    this.credentialManagers = new Map<MusicSource, MusicSourceCredentialManager>([
      [kuwo.source, kuwo],
    ]);
  }

  /** 取某个音源的适配器。未注册的音源在这里被拒绝，而不是悄悄落到默认分支。 */
  of(source: MusicSource): MusicSourceClient {
    const client = this.bySource.get(source);
    if (!client) throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    return client;
  }

  /** 已注册的音源，供后台展示可选来源。 */
  sources(): MusicSource[] {
    return [...this.bySource.keys()];
  }

  /**
   * 取某个音源的凭据管理器。
   *
   * 不支持后台登录的音源在这里被拒绝 —— 后台页面应当先查 [supportsCredentialLogin]
   * 再决定要不要显示登录入口，走到这里才报错说明前端漏了判断。
   */
  credentialManagerOf(source: MusicSource): MusicSourceCredentialManager {
    const manager = this.credentialManagers.get(source);
    if (!manager) {
      throw ApiErrors.badRequest(4007, `${this.of(source).displayName}不支持在后台登录账号`);
    }
    return manager;
  }

  /** 该音源是否支持手机号 + 短信验证码登录。 */
  supportsCredentialLogin(source: MusicSource): boolean {
    return this.credentialManagers.has(source);
  }

  /**
   * 该音源的账号 ID（`uid`）是否必须是纯数字。
   *
   * 与 [credentialManagerOf] 的区别：那个是「取管理器，取不到就是前端漏判」，
   * 会抛错；这里只是**问一条规则**，不支持后台登录的音源就是「没有这条规则」，
   * 返回 false。写入账号时用它决定要不要拦非数字 uid。
   */
  numericUidOnly(source: MusicSource): boolean {
    return this.credentialManagers.get(source)?.numericUidOnly === true;
  }

  /**
   * 凭据变更后清掉该音源的凭据缓存。
   *
   * **不支持后台登录的音源静默跳过**，不抛错 —— 它没有凭据缓存可清，
   * 而调用方（账号的增删改 / 启停）是所有音源共用的写入路径，
   * 让它们各自去判断「这个音源能不能登录」就是把能力判断散回调用方了。
   *
   * ⚠️ 所有会改变「当前生效凭据」的写入都必须调它：新增、改 token/uid、启停、删除。
   * 漏一处就会出现「后台改完了但播放还在用旧凭据」，且最长要等 30 秒 TTL 才自愈。
   */
  invalidateCredentialCache(source: MusicSource): void {
    this.credentialManagers.get(source)?.invalidateCredentialCache();
  }

  /**
   * 聚合搜索要用的音源列表。
   *
   * 具体包含哪些由 [AGGREGATED_SOURCES] 决定 —— **酷我不在其中**，
   * 所以「全部」搜索不会查酷我，那里的注释写了原因。
   */
  aggregated(): MusicSourceClient[] {
    return AGGREGATED_SOURCES
      .map((source) => this.bySource.get(source))
      .filter((client): client is MusicSourceClient => client !== undefined);
  }
}
