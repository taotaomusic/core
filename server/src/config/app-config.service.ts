import { Injectable, Logger } from "@nestjs/common";
import { ConfigService } from "@nestjs/config";
import { resolve } from "node:path";

/**
 * 类型化的配置访问。
 *
 * 变量名与默认值与迁移前逐一对应，避免部署时还要改 .env。
 * 各处注入这个服务而不是到处写 `configService.get("字符串键")`，
 * 拼错键名会在编译期就被发现。
 */
@Injectable()
export class AppConfigService {
  readonly port: number;

  /**
   * PostgreSQL 连接串，形如 `postgres://user:pass@host:5432/music`。
   * 没有默认值 —— 网络数据库配错了应该启动即失败，而不是连上一个空库继续跑。
   */
  readonly databaseUrl: string;

  /**
   * 访问令牌的 HMAC 签名密钥。
   *
   * **没有兜底值**：缺失或短于 32 字符会在配置加载阶段直接启动失败
   * （见 `env.validation.ts`）。这里只做类型收敛，绝不静默降级成某个
   * 源码里写死的字符串 —— 那等于把令牌伪造能力公开。
   */
  readonly authSecret: string;

  readonly apkDirectory: string;
  /** Windows 模块、清单差分和原生更新工具的内容寻址存储目录。 */
  readonly desktopReleaseDirectory: string;
  /** 可选的 Courgette 可执行文件；仅用于 .dll/.exe 等 PE 原生二进制。 */
  readonly courgettePath: string;
  readonly defaultChannel: string;

  /** 兼容旧部署的 bsdiff 路径字段；当前桌面差分实现使用 bsdiff-wasm。 */
  readonly bsdiffExecutable: string;

  /**
   * 对外可访问的基地址，用于拼装安装包下载地址。
   * 留空时按请求头推导，但反向代理未透传 `X-Forwarded-Proto` 时可能推出错误的协议，
   * 生产环境建议显式配置。
   */
  readonly publicBaseUrl: string;

  /** 兼容旧部署的搜索并发字段；当前搜索只返回元信息，不解析播放地址。 */
  readonly searchConcurrency: number;

  /** ApiSweet 图片生成服务地址。API Key 存在数据库的 api_key 表中。 */
  readonly apiSweetBaseUrl: string;
  /** 兰空图床上传配置，仅服务端使用，绝不下发客户端。 */
  readonly lskyUploadUrl: string;
  readonly lskyApiKey: string;
  /**
   * 允许写入 `avatar_url` 的图床主机白名单（小写，可带端口）。
   *
   * 头像地址是**由上游响应决定**的，不是用户填的，所以更要校验：图床被劫持或
   * 配置写错时，返回的可能是任意域名的 URL，落库后就会出现在别人客户端的
   * 头像请求里（等于把每个用户的 IP 送给第三方）。留空表示只做「必须是 https
   * 且能解析成合法 URL」的基础校验。
   */
  readonly lskyPublicHosts: string[];

  /**
   * 允许跨域读取接口响应的来源白名单（形如 `https://a.example`）。
   *
   * **留空表示不下发任何 CORS 响应头**，这是有意的默认值：管理后台与分享页
   * 都由本服务同源托管，第一方场景根本不需要 CORS；而通配 `*` 会让任意站点
   * 在用户浏览器里读取公开接口的响应。只有确实存在第三方网页调用时才逐条加入。
   *
   * 注意：音视频流与搜索接口有自己的路由级 `*`（见 stream.service / search.service），
   * 那是给原生播放器与分享页跨域拉流用的，不受本白名单约束。
   */
  readonly corsAllowedOrigins: string[];

  /** 注册验证码邮件的 SMTP 配置；缺失时注册会保持关闭，不能绕过邮箱验证。 */
  readonly smtpHost: string;
  readonly smtpPort: number;
  readonly smtpUser: string;
  readonly smtpPassword: string;
  readonly smtpFrom: string;

  /** 是否启用悟空 IM 会话签发。未启用时旧客户端与其它业务完全不受影响。 */
  readonly imEnabled: boolean;

  /** 悟空 IM 产品 API，仅允许桃桃音乐服务通过本机回环地址访问。 */
  readonly imInternalApiBaseUrl: string;

  /** 下发给 Android 客户端的悟空 IM 原生 TCP Gateway 地址。 */
  readonly imExternalGatewayUrl: string;

  /** 悟空 IM 产品 API 的服务端访问令牌；为空表示由内网隔离提供边界。 */
  readonly imApiToken: string;

  /** IM 设备 Token 的客户端续签周期；实际连接校验仍必须由悟空 IM Gateway 完成。 */
  readonly imSessionLifetimeSeconds: number;

  /** 管理员 2FA TOTP 的发行者名称 */
  readonly totpIssuer: string;

  /**
   * 默认超级管理员的初始口令。
   *
   * 留空时启动阶段会随机生成一个并只在日志里打印一次。无论哪种来源，
   * 该账号首次登录都必须改密。长度下限由 `env.validation.ts` 把关。
   */
  readonly adminInitialPassword: string;

  /** LDAP/SSO 配置（可选） */
  readonly ldapUrl: string;
  readonly ldapBindDn: string;
  readonly ldapBindPassword: string;
  readonly ldapUserSearchBase: string;
  readonly ldapUserSearchFilter: string;
  readonly ldapGroupSearchBase: string;
  readonly ldapGroupSearchFilter: string;
  readonly ldapRoleMapping: Record<string, string>; // LDAP group -> role mapping
  /**
   * LDAPS 是否校验证书。
   *
   * 默认 `true`：无条件信任自签证书等于把目录口令交给任何能中间人的人。
   * 内网自签证书环境显式设 `LDAP_TLS_REJECT_UNAUTHORIZED=false` 才关闭。
   */
  readonly ldapTlsRejectUnauthorized: boolean;
  /** LDAP 单次操作的超时（毫秒）。连接和搜索都要有，否则目录无响应会一直挂着。 */
  readonly ldapTimeoutMs: number;

  /**
   * 仅供独立契约验证使用的固定验证码。环境校验已限制它只能出现在
   * `NODE_ENV=test`，生产和普通开发进程绝不会启用此分支。
   */
  readonly emailVerificationTestCode: string | null;

  /**
   * 传输加密的内嵌 PSK（`plans/009`）。`CRYPTO_PSK_ID` 是密钥标识，
   * `CRYPTO_PSK_HEX` 是 64 位十六进制原始 PSK。二者缺一则握手全部失败、链路保持明文。
   * per-device 派生在握手时按 ClientHello 里的 device_id 现算，不在这里配置。
   */
  readonly cryptoPskId: string;
  readonly cryptoPskHex: string;

  /**
   * 上游接口基地址。
   * 搜索与播放链接用 v3（v3 的路径里不带版本号前缀），歌词仍用 v2 ——
   * v2 的歌词接口同时给出 lrc、逐字 yrc、翻译和音译，v3 没有等价接口。
   */
  readonly upstreamV3BaseUrl = "https://api.vkeys.cn/music/tencent";
  readonly upstreamV2BaseUrl = "https://api.vkeys.cn/v2/music/tencent";

  /** 访问令牌 15 分钟，刷新令牌 30 天。客户端按 expiresIn 计算过期时刻并预留 30 秒余量。 */
  readonly accessLifetimeSeconds = 15 * 60;
  readonly refreshLifetimeSeconds = 60 * 60 * 24 * 30;

  /**
   * 只允许转发这些主机的媒体地址，防止把服务端当成任意 URL 的代理。
   *
   * 酷我的四个音频 CDN 是**实测枚举出来的**（8 组关键词 × 每首 5 个档位，
   * 共 220 个地址样本）：`bd-er` 114 次、`bd-lv` 58 次、`bd-lw` 30 次、`bd-bj` 18 次。
   * 四个都要写全 —— 只写见过的那两个会挡掉约三分之一的请求，而且表现为
   * 「有的歌能放、有的放不了」，很难定位。
   *
   * **刻意不用 `*.kuwo.cn` 通配**：那等于把整个 kuwo.cn 域开放成代理目标，
   * 而 CDN 主机会随上游调整变化，多出来的主机应该显式加进来，而不是自动放行。
   * 四个主机都验证过 https + Range 可用（206 + `audio/mpeg`）。
   */
  readonly allowedMediaHosts = new Set([
    "ws.stream.qqmusic.qq.com",
    "y.qq.com",
    "bd-er.kuwo.cn",
    "bd-lv.kuwo.cn",
    "bd-lw.kuwo.cn",
    "bd-bj.kuwo.cn",
  ]);

  /** 只允许已接入音源的 CDN，避免音频代理被用于请求任意站点。 */
  isAllowedMediaHost(hostname: string): boolean {
    return this.allowedMediaHosts.has(hostname) || hostname.endsWith(".music.126.net");
  }

  constructor(config: ConfigService) {
    this.port = Number(config.get("PORT") ?? 4500);
    this.databaseUrl = String(config.get("DATABASE_URL") ?? "");
    this.authSecret = String(config.get("AUTH_SECRET") ?? "");
    this.apkDirectory = resolve(String(config.get("APK_DIR") ?? "./data/apk"));
    this.desktopReleaseDirectory = resolve(String(config.get("DESKTOP_RELEASE_DIR") ?? "./data/desktop"));
    this.courgettePath = String(config.get("COURGETTE_PATH") ?? "").trim();
    this.defaultChannel = String(config.get("DEFAULT_CHANNEL") ?? "release");
    this.bsdiffExecutable = String(config.get("BSDIFF_BIN") ?? "bsdiff");
    this.publicBaseUrl = String(config.get("PUBLIC_BASE_URL") ?? "").replace(/\/+$/, "");
    this.searchConcurrency = Math.max(1, Number(config.get("SEARCH_CONCURRENCY") ?? 8));
    this.apiSweetBaseUrl = String(config.get("APISWEET_BASE_URL") ?? "https://apisweet.com").replace(
      /\/+$/,
      "",
    );
    this.lskyUploadUrl = String(config.get("LSKY_UPLOAD_URL") ?? "https://img.kiwiyyds.cn/api/index.php");
    this.lskyApiKey = String(config.get("LSKY_API_KEY") ?? "");
    this.lskyPublicHosts = splitCommaList(config.get("LSKY_PUBLIC_HOSTS"));
    this.corsAllowedOrigins = splitCommaList(config.get("CORS_ALLOWED_ORIGINS"));
    this.smtpHost = String(config.get("SMTP_HOST") ?? "");
    this.smtpPort = Number(config.get("SMTP_PORT") ?? 587);
    this.smtpUser = String(config.get("SMTP_USER") ?? "");
    this.smtpPassword = String(config.get("SMTP_PASSWORD") ?? "");
    this.smtpFrom = String(config.get("SMTP_FROM") ?? "");
    this.imEnabled = String(config.get("IM_ENABLED") ?? "false").toLowerCase() === "true";
    this.imInternalApiBaseUrl = String(config.get("IM_INTERNAL_API_BASE_URL") ?? "http://127.0.0.1:5001").replace(
      /\/+$/,
      "",
    );
    this.imExternalGatewayUrl = String(config.get("IM_EXTERNAL_GATEWAY_URL") ?? "tcp://im.xydaigua.cn:5100");
    this.imApiToken = String(config.get("IM_API_TOKEN") ?? "");
    this.imSessionLifetimeSeconds = Number(config.get("IM_SESSION_LIFETIME_SECONDS") ?? 900);
    this.emailVerificationTestCode = String(config.get("EMAIL_VERIFICATION_TEST_CODE") ?? "") || null;
    this.cryptoPskId = String(config.get("CRYPTO_PSK_ID") ?? "");
    this.cryptoPskHex = String(config.get("CRYPTO_PSK_HEX") ?? "");
    this.totpIssuer = String(config.get("TOTP_ISSUER") ?? "桃桃音乐管理后台");
    this.adminInitialPassword = String(config.get("ADMIN_INITIAL_PASSWORD") ?? "");
    this.ldapUrl = String(config.get("LDAP_URL") ?? "");
    this.ldapBindDn = String(config.get("LDAP_BIND_DN") ?? "");
    this.ldapBindPassword = String(config.get("LDAP_BIND_PASSWORD") ?? "");
    this.ldapUserSearchBase = String(config.get("LDAP_USER_SEARCH_BASE") ?? "");
    this.ldapUserSearchFilter = String(config.get("LDAP_USER_SEARCH_FILTER") ?? "(uid={{username}})");
    this.ldapGroupSearchBase = String(config.get("LDAP_GROUP_SEARCH_BASE") ?? "");
    this.ldapGroupSearchFilter = String(config.get("LDAP_GROUP_SEARCH_FILTER") ?? "");
    // LDAP 角色映射：LDAP group DN -> admin role
    const mappingStr = String(config.get("LDAP_ROLE_MAPPING") ?? "");
    this.ldapRoleMapping = this.parseRoleMapping(mappingStr);
    // 只有显式写成 false/0/no 才关闭校验，其余一律按校验证书处理。
    const tlsFlag = String(config.get("LDAP_TLS_REJECT_UNAUTHORIZED") ?? "true").toLowerCase();
    this.ldapTlsRejectUnauthorized = !["false", "0", "no", "off"].includes(tlsFlag);
    this.ldapTimeoutMs = Number(config.get("LDAP_TIMEOUT_MS") ?? 10_000);
  }

  /**
   * 解析 LDAP 角色映射。
   *
   * 配置写坏了不能让进程起不来 —— 那会把「LDAP 映射有问题」升级成
   * 「整个后台服务挂掉」。这里退化成空映射并打日志，登录流程会因此
   * 把所有 LDAP 用户映射成 viewer（最小权限），比崩掉好得多。
   */
  private parseRoleMapping(raw: string): Record<string, string> {
    if (!raw) return {};
    try {
      const parsed = JSON.parse(raw) as unknown;
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        throw new Error("LDAP_ROLE_MAPPING 必须是 JSON 对象");
      }
      return parsed as Record<string, string>;
    } catch (error) {
      new Logger(AppConfigService.name).warn(
        `LDAP_ROLE_MAPPING 解析失败，按空映射处理（LDAP 用户将全部是 viewer）：${(error as Error).message}`);
      return {};
    }
  }

  /** LDAP 是否配置了最小必填字段（地址、绑定 DN、用户搜索基）。 */
  get isLdapConfigured(): boolean {
    return !!this.ldapUrl && !!this.ldapBindDn && !!this.ldapUserSearchBase;
  }

  /** 发信配置必须完整，避免错误部署时静默跳过邮箱验证。 */
  get isSmtpConfigured(): boolean {
    return !!this.smtpHost && !!this.smtpUser && !!this.smtpPassword && !!this.smtpFrom;
  }
}

/**
 * 解析逗号分隔的配置项。
 *
 * 统一转小写并去掉空白项，让 `a.com, B.com` 与 `a.com,b.com` 等价 —— 主机名
 * 大小写不敏感，配置文件里写成大写不该导致白名单静默失效。
 */
function splitCommaList(raw: unknown): string[] {
  return String(raw ?? "")
    .split(",")
    .map((item) => item.trim().toLowerCase())
    .filter((item) => item.length > 0);
}
