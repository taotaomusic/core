import { Module } from "@nestjs/common";
import { APP_FILTER, APP_GUARD, APP_INTERCEPTOR } from "@nestjs/core";
import { AuthModule } from "./auth/auth.module";
import { AccessTokenGuard } from "./auth/guards/access-token.guard";
import { AllExceptionsFilter } from "./common/filters/all-exceptions.filter";
import { EnvelopeInterceptor } from "./common/interceptors/envelope.interceptor";
import { SecurityHeadersInterceptor } from "./common/interceptors/security-headers.interceptor";
import { RateLimitGuard } from "./common/rate-limit/rate-limit.guard";
import { RateLimitService } from "./common/rate-limit/rate-limit.service";
import { AppConfigModule } from "./config/config.module";
import { DatabaseModule } from "./database/database.module";
import { FavoritesModule } from "./favorites/favorites.module";
import { HealthController } from "./health/health.controller";
import { MusicModule } from "./music/music.module";
import { ReleaseModule } from "./release/release.module";

/**
 * 根模块。
 *
 * 全局守卫的注册顺序即执行顺序：先校验访问令牌，再限流 ——
 * 与迁移前「先查限流桶、再过门禁」相反的那一小段差异只影响公开路由，
 * 而公开路由在令牌守卫里本就直接放行，实际行为一致。
 */
@Module({
  imports: [
    AppConfigModule,
    DatabaseModule,
    AuthModule,
    FavoritesModule,
    MusicModule,
    ReleaseModule,
  ],
  controllers: [HealthController],
  providers: [
    RateLimitService,
    { provide: APP_GUARD, useClass: AccessTokenGuard },
    { provide: APP_GUARD, useClass: RateLimitGuard },
    { provide: APP_INTERCEPTOR, useClass: SecurityHeadersInterceptor },
    { provide: APP_INTERCEPTOR, useClass: EnvelopeInterceptor },
    { provide: APP_FILTER, useClass: AllExceptionsFilter },
  ],
})
export class AppModule {}
