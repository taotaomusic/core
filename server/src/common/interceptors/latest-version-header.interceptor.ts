import { type CallHandler, type ExecutionContext, Injectable, type NestInterceptor } from "@nestjs/common";
import type { Request, Response } from "express";
import type { Observable } from "rxjs";
import { AppConfigService } from "../../config/app-config.service";
import { LatestVersionCache } from "../../release/latest-version.cache";
import { ReleaseRepository } from "../../release/release.repository";

/**
 * 在每个响应上带出当前全量可用的最高版本号。
 *
 * 目的是让客户端在会话中途就能发现新版本 —— 此前只有冷启动会查一次 `/app/bootstrap`,
 * 一直开着应用的用户可能几天都收不到更新。客户端在任意接口的响应头上看到比自己高的
 * 版本号,就去走一次正常的检查流程。
 *
 * **这个头只是提示,不是权威。** 真正的判定(灰度分桶、SDK 兼容、强制更新)始终在
 * `/app/bootstrap`。所以取值刻意只包含放量 100% 的版本,见
 * [ReleaseRepository.latestFullyRolledOut]。
 *
 * 用 `setHeader` 而不是在各处 `writeHead` 里加:Node 会把两者合并,只有同名才以
 * `writeHead` 为准,所以搜索的 NDJSON、音频代理、APK 二进制这些自己写响应头的
 * 端点也能带上它。现有代码已经在依赖这个语义(`cache-control` 就是这么分层的)。
 *
 * 已知边界:守卫跑在拦截器之前,所以 **401 与 429 不会带这个头**。可以接受 ——
 * 客户端只在成功响应上嗅探。
 */
@Injectable()
export class LatestVersionHeaderInterceptor implements NestInterceptor {
  constructor(
    private readonly config: AppConfigService,
    private readonly cache: LatestVersionCache,
    private readonly releases: ReleaseRepository,
  ) {}

  intercept(context: ExecutionContext, next: CallHandler): Observable<unknown> {
    if (context.getType() !== "http") return next.handle();
    const cached = this.cache.get();
    if (cached === undefined) {
      // 缓存是冷的：本次不带头，顺手在后台填上，下一个请求就有了。
      // 刻意不 await —— 这个拦截器跑在每个请求上，包括音频代理，不能为一个
      // 提示性的头去等一次数据库往返。失败也不要紧，下次再填。
      void this.releases.latestFullyRolledOut(this.config.defaultChannel).catch(() => undefined);
    } else if (cached !== null) {
      context.switchToHttp().getResponse<Response>().setHeader("x-latest-version-code", String(cached));
    }
    const request = context.switchToHttp().getRequest<Request>();
    // 使用 Node/Express 的标准 headers 映射；不能调用浏览器 Fetch Request 上不存在的
    // header()，否则 tsc 无法生成线上要部署的 dist。
    const appVersionHeader = request.headers["x-app-version-code"];
    const versionCode = Number(Array.isArray(appVersionHeader) ? appVersionHeader[0] : appVersionHeader);
    if (Number.isSafeInteger(versionCode) && versionCode > 0) {
      const response = context.switchToHttp().getResponse<Response>();
      const patch = this.cache.patchFor(versionCode);
      if (patch === undefined) {
        void this.releases.latestFullyRolledOutPatch(this.config.defaultChannel, versionCode).catch(() => undefined);
      } else if (patch !== null) {
        response.setHeader("x-latest-patch-version", String(patch));
      }
    }
    return next.handle();
  }
}
