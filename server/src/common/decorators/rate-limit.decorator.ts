import { SetMetadata } from "@nestjs/common";

export const RATE_LIMIT_METADATA_KEY = "taotao:rate-limit";

/**
 * 限流分桶。
 *
 * - `auth:<scope>`：登录/注册，按来源地址 10 次 / 15 分钟
 * - `app`：客户端引导与安装包下载，按设备号 60 次 + 按来源地址 900 次 / 15 分钟
 * - `admin`：发布管理，按来源地址 60 次 / 15 分钟
 *
 * 三者计数器必须互相独立：更新检查是周期性调用，与登录共用会烧掉用户的登录额度，
 * 表现成「登录提示请求过于频繁」。
 */
export type RateLimitBucket = `auth:${string}` | "app" | "admin";

export const RateLimit = (bucket: RateLimitBucket) => SetMetadata(RATE_LIMIT_METADATA_KEY, bucket);
