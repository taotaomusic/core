import { Body, Controller, Get, HttpCode, HttpStatus, Post, Query, Req, Res } from "@nestjs/common";
import type { Request, Response } from "express";
import { ApiErrors, ApiException } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import {
  forwardedClientAddress, type AdminActor, type AdminAuthenticatedRequest,
} from "../common/request.types";
import { AdminAuditService } from "./admin-audit.service";
import { AdminGuarded } from "./admin-guarded.decorator";
import { GithubOauthService } from "./github-oauth.service";

/** 管理后台 SPA 的落地页。GitHub 回调结束后带片段跳回这里，由前端消费。 */
const ADMIN_SPA_PATH = "/admin";

/**
 * GitHub OAuth 登录与绑定接口。
 *
 * 与口令登录平行的第二条**进入**门：登录和回调是公开路由（用户在到达这里
 * 时还没有管理员会话），绑定 / 解绑是已认证路由、只操作本人账号。
 */
@Public()
@Controller("admin/auth/github")
export class GithubOauthController {
  constructor(
    private readonly oauth: GithubOauthService,
    private readonly audit: AdminAuditService,
  ) {}

  /**
   * 登录入口：跳转到 GitHub 授权页。
   *
   * 前端直接 `location.href` 到本路由即可，这里 302 到 GitHub；回调（[callback]）
   * 会把会话令牌带回管理后台。
   */
  @RateLimit("auth:admin-github")
  @Get("login")
  login(@Res() res: Response): void {
    this.assertConfigured();
    res.redirect(this.oauth.beginLogin().url);
  }

  /** 是否已启用 GitHub 登录。登录页靠它决定要不要显示「使用 GitHub 登录」入口。 */
  @Get("status")
  status() {
    return { enabled: this.oauth.configured };
  }

  /**
   * GitHub 授权回调。
   *
   * 无论成功失败都 302 回管理后台，结果放在 URL 片段里让前端落地：
   * `#github_token=`（登录成功）、`#github_bind=ok`（绑定成功）、`#github_error=`
   * （失败文案）。片段不会被浏览器发到服务器，令牌也就不会出现在访问日志里。
   */
  @RateLimit("auth:admin-github")
  @Get("callback")
  async callback(
    @Query("code") code: string,
    @Query("state") state: string,
    @Req() req: Request,
    @Res() res: Response,
  ): Promise<void> {
    if (!code || !state) {
      res.redirect(`${ADMIN_SPA_PATH}#github_error=${encodeURIComponent("授权参数缺失")}`);
      return;
    }
    const ip = forwardedClientAddress(req);
    const userAgent = String(req.headers["user-agent"] ?? "");
    try {
      const result = await this.oauth.handleCallback(code, state, ip, userAgent);
      if (result.kind === "login") {
        res.redirect(`${ADMIN_SPA_PATH}#github_token=${result.token}`);
        return;
      }
      res.redirect(`${ADMIN_SPA_PATH}#github_bind=ok`);
    } catch (error) {
      const message = error instanceof ApiException ? error.message : "GitHub 登录失败，请重试";
      res.redirect(`${ADMIN_SPA_PATH}#github_error=${encodeURIComponent(message)}`);
    }
  }

  /**
   * 发起绑定：校验本人当前口令后返回 GitHub 授权 URL，前端跳过去完成绑定。
   *
   * 绑定结果的落库在回调里完成，因为那一步已经没有 Bearer 会话，真实身份
   * 靠 state 里的 admin_id 携带。
   */
  @AdminGuarded()
  @RateLimit("auth:admin-password")
  @Post("bind")
  async bind(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    this.assertConfigured();
    const actor = this.requireActor(req);
    const admin = await this.oauth.verifyBindPassword(actor.id, String(body?.password ?? ""));
    if (admin.github_id) throw ApiErrors.conflict(4091, "当前账号已绑定 GitHub，请先解绑");
    return { authorize_url: this.oauth.beginBind(actor.id).url };
  }

  /** 解绑：校验本人当前口令后清空 github_id 三列。 */
  @AdminGuarded()
  @RateLimit("auth:admin-password")
  @Post("unbind")
  @HttpCode(HttpStatus.NO_CONTENT)
  async unbind(@Req() req: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>): Promise<void> {
    const actor = this.requireActor(req);
    const admin = await this.oauth.verifyBindPassword(actor.id, String(body?.password ?? ""));
    if (!admin.github_id) throw ApiErrors.badRequest(4000, "当前账号未绑定 GitHub");
    await this.oauth.unbind(actor.id);
    await this.audit.record(req, "auth.github_unbind", "admin_user", String(actor.id), {
      github_login: admin.github_login,
    });
  }

  private assertConfigured(): void {
    if (!this.oauth.configured) throw ApiErrors.serviceUnavailable(5030, "GitHub 登录未启用");
  }

  private requireActor(req: AdminAuthenticatedRequest): AdminActor {
    const actor = req.adminUser;
    if (!actor) throw ApiErrors.unauthorized(4013, "管理员认证失败");
    return actor;
  }
}