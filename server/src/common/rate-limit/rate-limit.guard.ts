import { type CanActivate, type ExecutionContext, Injectable } from "@nestjs/common";
import { Reflector } from "@nestjs/core";
import { ApiErrors } from "../api.exception";
import { RATE_LIMIT_METADATA_KEY, type RateLimitBucket } from "../decorators/rate-limit.decorator";
import { clientAddressOf, type AuthenticatedRequest } from "../request.types";
import { RateLimitService } from "./rate-limit.service";

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
    if (bucket === "image" || bucket === "image-status" || bucket === "im-session") {
      if (!request.user) throw ApiErrors.unauthorized(4010, "请先登录");
      const allowed =
        bucket === "image"
          ? this.rateLimit.allowImageRequest(request.user.id, address)
          : bucket === "image-status"
            ? this.rateLimit.allowImageStatusRequest(request.user.id, address)
            : this.rateLimit.allowImSessionRequest(request.user.id, address);
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
    return this.rateLimit.allowAuthAttempt(bucket.slice("auth:".length), address);
  }
}
