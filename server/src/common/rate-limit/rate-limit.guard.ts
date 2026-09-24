import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { createHash } from "node:crypto";
import { ApiErrors } from "../api.exception";
import { RATE_LIMIT_METADATA_KEY, type RateLimitBucket } from "../decorators/rate-limit.decorator";
import { clientAddressOf, type AuthenticatedRequest } from "../request.types";
import { RateLimitService } from "./rate-limit.service";

/** 从请求头取开放 API Key 原文（`X-API-Key` 或 `Authorization: Bearer`）。 */
function readOpenApiKey(request: AuthenticatedRequest): string {
  const header = String(request.headers["x-api-key"] ?? "").trim();
  if (header) return header;
  const authorization = String(request.headers.authorization ?? "");
  const bearer = authorization.replace(/^Bearer\s+/i, "").trim();
  // 访问令牌形如 payload.signature（含点），开放 key 形如 tt_...，据此区分，
  // 避免把用户访问令牌误算进开放接口的 key 维度额度。
  return bearer.startsWith("tt_") ? bearer : "";
}

/** 按路由上标注的分桶做限流；没标注的路由不限流。 */
@Injectable()
export class RateLimitGuard implements CanActivate {
  constructor(
    private readonly reflector: Reflector,
    private readonly rateLimit: RateLimitService,
  ) {}

  canActivate(context: ExecutionContext): boolean {
    const bucket = this.reflector.getAllAndOverride<RateLimitBucket>(RATE_LIMIT_METADATA_KEY, [
      context.getHandler(),
      context.getClass(),
    ]);
    if (!bucket) return true;

    const request = context.switchToHttp().getRequest<AuthenticatedRequest>();
    const address = clientAddressOf(request);
    if (bucket === "image" || bucket === "image-status" || bucket === "im-session" || bucket === "im-sync") {
      if (!request.user) throw ApiErrors.unauthorized(4010, "请先登录");
      const allowed =
        bucket === "image"
          ? this.rateLimit.allowImageRequest(request.user.id, address)
          : bucket === "image-status"
            ? this.rateLimit.allowImageStatusRequest(request.user.id, address)
            : bucket === "im-session"
              ? this.rateLimit.allowImSessionRequest(request.user.id, address)
              : this.rateLimit.allowImSyncRequest(request.user.id, address);
      if (!allowed) throw ApiErrors.tooManyRequests();
      return true;
    }
    const allowed = this.check(bucket, request, address);
    if (!allowed) throw ApiErrors.tooManyRequests();
    return true;
  }

  private check(bucket: RateLimitBucket, request: AuthenticatedRequest, address: string): boolean {
    if (bucket === "admin") return this.rateLimit.allowAdminRequest(address);
    if (bucket === "app") {
      const deviceId = String(request.query?.deviceId ?? "").slice(0, 64);
      return this.rateLimit.allowAppRequest(address, deviceId);
    }
    if (bucket === "email-verification") return this.rateLimit.allowEmailVerification(address);
    if (bucket === "auth:admin-login") return this.rateLimit.allowAdminLoginAttempt(address);
    if (bucket === "music-source-sms") {
      // 手机号来自请求体。全局守卫跑在 body 解析之后，这里能拿到；
      // 取不到时退回按地址计数，不能因为字段缺失就跳过限流。
      const phone = String(request.body?.phone ?? "").trim();
      return this.rateLimit.allowMusicSourceSms(address, phone || address);
    }
    if (bucket === "open-api") {
      // 此时 ApiKeyGuard（方法级）还没跑，key 未经验证 —— 直接取 header
      // 原文的 sha256 作为计数键即可：伪造 key 也照样占用该 key 的额度，
      // 取不到 header 时退回只按地址计数。
      const raw = readOpenApiKey(request);
      const identity = raw ? createHash("sha256").update(raw).digest("hex") : "";
      return this.rateLimit.allowOpenApiRequest(identity, address);
    }
    return this.rateLimit.allowAuthAttempt(bucket.slice("auth:".length), address);
  }
}
