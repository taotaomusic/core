import { Injectable } from "@nestjs/common";
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

  /** 访问令牌的 HMAC 签名密钥。生产环境的长度校验在配置加载阶段完成。 */
  readonly authSecret: string;

  /** 发布管理接口的静态令牌，通过请求头 `X-Admin-Token` 校验；留空则整组管理接口关闭。 */
  readonly adminToken: string;

  readonly apkDirectory: string;
  readonly defaultChannel: string;

  /**
   * 对外可访问的基地址，用于拼装安装包下载地址。
   * 留空时按请求头推导，但反向代理未透传 `X-Forwarded-Proto` 时可能推出错误的协议，
   * 生产环境建议显式配置。
   */
  readonly publicBaseUrl: string;

  /**
   * 搜索时解析播放地址的并发上限。
   * 串行解析 20 首要十几秒，并发后总耗时取决于最慢的一批而不是累加。
   */
  readonly searchConcurrency: number;

  /** ApiSweet 图片生成服务地址。API Key 存在数据库的 api_key 表中。 */
  readonly apiSweetBaseUrl: string;

  /** 注册验证码邮件的 SMTP 配置；缺失时注册会保持关闭，不能绕过邮箱验证。 */
  readonly smtpHost: string;
  readonly smtpPort: number;
  readonly smtpUser: string;
  readonly smtpPassword: string;
  readonly smtpFrom: string;

  /**
   * 仅供独立契约验证使用的固定验证码。环境校验已限制它只能出现在
   * `NODE_ENV=test`，生产和普通开发进程绝不会启用此分支。
   */
  readonly emailVerificationTestCode: string | null;

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

  /** 只允许转发这些主机的媒体地址，防止把服务端当成任意 URL 的代理。 */
  readonly allowedMediaHosts = new Set(["ws.stream.qqmusic.qq.com", "y.qq.com"]);

  constructor(config: ConfigService) {
    this.port = Number(config.get("PORT") ?? 4500);
    this.databaseUrl = String(config.get("DATABASE_URL") ?? "");
    this.authSecret = String(config.get("AUTH_SECRET") ?? "taotao-development-secret-change-me");
    this.adminToken = String(config.get("ADMIN_TOKEN") ?? "");
    this.apkDirectory = resolve(String(config.get("APK_DIR") ?? "./data/apk"));
    this.defaultChannel = String(config.get("DEFAULT_CHANNEL") ?? "release");
    this.publicBaseUrl = String(config.get("PUBLIC_BASE_URL") ?? "").replace(/\/+$/, "");
    this.searchConcurrency = Math.max(1, Number(config.get("SEARCH_CONCURRENCY") ?? 8));
    this.apiSweetBaseUrl = String(config.get("APISWEET_BASE_URL") ?? "https://apisweet.com").replace(
      /\/+$/,
      "",
    );
    this.smtpHost = String(config.get("SMTP_HOST") ?? "");
    this.smtpPort = Number(config.get("SMTP_PORT") ?? 587);
    this.smtpUser = String(config.get("SMTP_USER") ?? "");
    this.smtpPassword = String(config.get("SMTP_PASSWORD") ?? "");
    this.smtpFrom = String(config.get("SMTP_FROM") ?? "");
    this.emailVerificationTestCode = String(config.get("EMAIL_VERIFICATION_TEST_CODE") ?? "") || null;
  }

  /** 发信配置必须完整，避免错误部署时静默跳过邮箱验证。 */
  get isSmtpConfigured(): boolean {
    return !!this.smtpHost && !!this.smtpUser && !!this.smtpPassword && !!this.smtpFrom;
  }
}
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
    this.imEnabled = String(config.get("IM_ENABLED") ?? "false").toLowerCase() === "true";
    this.imInternalApiBaseUrl = String(config.get("IM_INTERNAL_API_BASE_URL") ?? "http://127.0.0.1:5001").replace(
      /\/+$/,
      "",
    );
    this.imExternalGatewayUrl = String(config.get("IM_EXTERNAL_GATEWAY_URL") ?? "tcp://114.66.23.232:5100");
    this.imApiToken = String(config.get("IM_API_TOKEN") ?? "");
    this.imSessionLifetimeSeconds = Number(config.get("IM_SESSION_LIFETIME_SECONDS") ?? 900);
