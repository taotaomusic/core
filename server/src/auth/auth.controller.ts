import { Body, Controller, Get, HttpCode, HttpStatus, Post } from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { SessionUser } from "../common/request.types";
import { AuthService } from "./auth.service";
import { UsersRepository } from "./users.repository";

/** 用户名 3 至 32 位，允许字母数字下划线与汉字。与迁移前完全一致。 */
const USERNAME_PATTERN = /^[\w一-龥]{3,32}$/;
const MIN_PASSWORD_LENGTH = 6;

/**
 * 认证接口。
 *
 * 这些路由刻意**不使用 DTO 校验**：客户端把 4xx 一律当成「凭据被拒绝」，
 * 在刷新令牌接口上还会因此清空本地会话把用户踢回登录页。
 * 校验失败必须给出精确的业务码（注册 4003、登录 4011、刷新 4012），
 * 交给 ValidationPipe 会统一变成 400/4005，语义就错了。
 */
@Controller("auth")
export class AuthController {
  constructor(
    private readonly auth: AuthService,
    private readonly users: UsersRepository,
  ) {}

  @Public()
  @RateLimit("auth:register")
  @Post("register")
  @HttpCode(HttpStatus.CREATED)
  async register(@Body() body: Record<string, unknown>) {
    const username = this.auth.textOf(body?.username, true);
    const password = this.auth.textOf(body?.password);
    if (!USERNAME_PATTERN.test(username) || password.length < MIN_PASSWORD_LENGTH) {
      throw ApiErrors.badRequest(4003, "用户名为3至32位，密码至少6位");
    }
    // 这次查重只是为了在常见路径上给出准确的 409；真正的兜底是 users.create 里
    // 对唯一约束冲突的翻译 —— 两步之间夹着约 100ms 的 scrypt，并发注册挡不住。
    if (await this.users.findByUsername(username)) throw ApiErrors.conflict(4090, "用户名已存在");

    const credentials = this.auth.hashPassword(password);
    const user = await this.users.create(username, credentials.hash, credentials.salt);
    // accessToken / refreshToken / expiresIn 必须与 user 平铺在同一层 data 下：
    // 客户端用 getString 硬取，包一层就会抛异常。
    return { user, ...(await this.auth.issueTokens(user)) };
  }

  @Public()
  @RateLimit("auth:login")
  @Post("login")
  @HttpCode(HttpStatus.OK)
  async login(@Body() body: Record<string, unknown>) {
    const username = this.auth.textOf(body?.username, true);
    const password = this.auth.textOf(body?.password);
    const user = await this.users.findByUsername(username);
    if (!user || !this.auth.verifyPassword(password, user.password_salt, user.password_hash)) {
      throw ApiErrors.unauthorized(4011, "用户名或密码错误");
    }
    return {
      user: { id: user.id, username: user.username, created_at: user.created_at },
      ...(await this.auth.issueTokens(user)),
    };
  }

  /**
   * 刷新令牌。
   *
   * 只有「令牌真的无效」才能返回 4xx —— 客户端收到 4xx 会清空本地令牌并回登录页。
   * 数据库或内部故障必须落到 5xx（由全局过滤器归成 502），那样客户端会保留令牌下次再试。
   */
  @Public()
  @Post("refresh")
  @HttpCode(HttpStatus.OK)
  async refresh(@Body() body: Record<string, unknown>) {
    const tokens = await this.auth.rotate(this.auth.textOf(body?.refreshToken));
    if (!tokens) throw ApiErrors.unauthorized(4012, "刷新令牌无效或已过期");
    return tokens;
  }

  /** 注销。客户端完全忽略响应，本地令牌在调用前就已清空。 */
  @Public()
  @Post("logout")
  @HttpCode(HttpStatus.NO_CONTENT)
  async logout(@Body() body: Record<string, unknown>): Promise<void> {
    const token = this.auth.textOf(body?.refreshToken);
    if (token) await this.auth.revokeRefreshToken(token);
  }

  /** 当前登录用户。客户端目前未调用，保留给运维排查。 */
  @Get("me")
  me(@CurrentUser() user: SessionUser | undefined) {
    if (!user) throw ApiErrors.unauthorized(4010, "未登录");
    return user;
  }
}
