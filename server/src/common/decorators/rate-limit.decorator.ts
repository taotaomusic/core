import { SetMetadata } from "@nestjs/common";

export const RATE_LIMIT_METADATA_KEY = "taotao:rate-limit";

/**
 * 限流分桶。
 *
 * - `auth:<scope>`：登录/注册，按来源地址 10 次 / 15 分钟
 *   （`auth:admin-login` 例外：30 次 / 15 分钟，主防线是账号维度退避，
 *   见 [RateLimitService.allowAdminLoginAttempt]）
 * - `email-verification`：发验证码，按来源地址 5 次 / 15 分钟
 * - `app`：客户端引导与安装包下载，按设备号 60 次 + 按来源地址 900 次 / 15 分钟
 * - `admin`：发布管理，按来源地址 60 次 / 15 分钟
 * - `image`：图片生成，按用户 10 次 + 按来源地址 60 次 / 15 分钟
 * - `image-status`：图片任务轮询，按用户 300 次 + 按来源地址 1800 次 / 15 分钟
 * - `im-session`：IM 连接凭据签发，按用户与来源地址限制频率
 * - `im-sync`：IM 消息同步，按用户与来源地址限制频率
 * - `music-source-sms`：音源账号短信验证码，按来源地址 5 次 + 按手机号 3 次 / 15 分钟
 * - `open-api`：开放搜歌接口，按 API Key 120 次 + 按来源地址 600 次 / 15 分钟
 *
 * 各用途计数器必须互相独立：更新检查和图片轮询都是周期性调用，
 * 与登录或图片创建共用会烧掉其它用途的额度。
 */
export type RateLimitBucket =
  | `auth:${string}`
  | "email-verification"
  | "app"
  | "admin"
  | "image"
  | "image-status"
  | "im-session"
  | "im-sync"
  | "music-source-sms"
  | "open-api";

export const RateLimit = (bucket: RateLimitBucket) => SetMetadata(RATE_LIMIT_METADATA_KEY, bucket);
