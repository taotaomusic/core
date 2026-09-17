import { Body, Controller, Get, HttpCode, HttpStatus, Patch, Post, UploadedFile, UseInterceptors } from "@nestjs/common";
import { FileInterceptor } from "@nestjs/platform-express";
import type { Express } from "express";
import { ApiErrors } from "../common/api.exception";
import { CurrentUser } from "../common/decorators/current-user.decorator";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import { IMAGE_EXTENSION, IMAGE_MIME, sniffImageKind } from "../common/image-signature";
import type { SessionUser } from "../common/request.types";
import { AuthService } from "./auth.service";
import { UsersRepository } from "./users.repository";
import { EmailVerificationService } from "./email-verification.service";
import { AppConfigService } from "../config/app-config.service";

/** 用户名 3 至 32 位，允许字母数字下划线与汉字。与迁移前完全一致。 */
const USERNAME_PATTERN = /^[\w一-龥]{3,32}$/;
const MIN_PASSWORD_LENGTH = 6;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const VERIFICATION_CODE_PATTERN = /^\d{6}$/;
/**
 * 注册邮箱白名单。内部测试域仅在服务端保留，客户端仍维持原有 QQ 邮箱提示，
 * 避免把内部地址变成对外承诺的注册渠道。
 */
const ALLOWED_EMAIL_DOMAINS = new Set(["qq.com", "foxmail.com", "gongfa.qzz.io"]);
const MAX_NICKNAME_LENGTH = 24;
const MAX_AVATAR_URL_LENGTH = 2_048;

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
    private readonly emailVerification: EmailVerificationService,
    private readonly config: AppConfigService,
  ) {}

  /** 向邮箱发送注册验证码。邮箱统一转小写，保证同一地址不会绕过冷却与唯一约束。 */
  @Public()
  @RateLimit("email-verification")
  @Post("email-verification")
  @HttpCode(HttpStatus.NO_CONTENT)
  async sendEmailVerification(@Body() body: Record<string, unknown>): Promise<void> {
    const email = this.emailOf(body?.email);
    if (!this.isAllowedEmail(email)) throw ApiErrors.badRequest(4008, "仅支持 QQ 邮箱（qq.com 或 foxmail.com）");
    if (await this.users.findByEmail(email)) throw ApiErrors.conflict(4092, "邮箱已注册");
    await this.emailVerification.send(email, "register");
  }

  @Public()
  @RateLimit("auth:register")
  @Post("register")
  @HttpCode(HttpStatus.CREATED)
  async register(@Body() body: Record<string, unknown>) {
    const username = this.auth.textOf(body?.username, true);
    const password = this.auth.textOf(body?.password);
    const email = this.emailOf(body?.email);
    const verificationCode = this.auth.textOf(body?.verificationCode);
    if (!USERNAME_PATTERN.test(username) || password.length < MIN_PASSWORD_LENGTH || !this.isAllowedEmail(email)) {
      throw ApiErrors.badRequest(4003, "用户名为3至32位，密码至少6位，且须填写 QQ 邮箱");
    }
    // 这次查重只是为了在常见路径上给出准确的 409；真正的兜底是 users.create 里
    // 对唯一约束冲突的翻译 —— 两步之间夹着约 100ms 的 scrypt，并发注册挡不住。
    if (await this.users.findByUsername(username)) throw ApiErrors.conflict(4090, "用户名已存在");
    if (await this.users.findByEmail(email)) throw ApiErrors.conflict(4092, "邮箱已注册");
    if (!VERIFICATION_CODE_PATTERN.test(verificationCode) || !this.emailVerification.consume(email, verificationCode, "register")) {
      throw ApiErrors.badRequest(4009, "邮箱验证码错误或已过期");
    }

    const credentials = this.auth.hashPassword(password);
    const user = await this.users.create(username, email, credentials.hash, credentials.salt);
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

  /** 读取当前用户的可编辑资料；邮箱仅返回给账号本人。 */
  @Get("profile")
  async profile(@CurrentUser() user: SessionUser | undefined) {
    return this.requireProfile(user);
  }

  /** 更新昵称和头像。头像只接受 HTTPS URL，允许传 null 清除头像。 */
  @Patch("profile")
  async updateProfile(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>) {
    const nickname = this.optionalNickname(body?.nickname);
    const avatarUrl = this.optionalAvatarUrl(body?.avatarUrl);
    if (nickname === undefined && avatarUrl === undefined) {
      throw ApiErrors.badRequest(4000, "请至少提供昵称或头像");
    }
    const current = await this.requireProfile(user);
    return (await this.users.updateProfile(current.id, nickname, avatarUrl))!;
  }

  /** 接收头像图片并转存到兰空图床，Key 仅保留在服务端环境变量。 */
  @Post("avatar")
  @UseInterceptors(FileInterceptor("file", { limits: { fileSize: 5 * 1024 * 1024 } }))
  async uploadAvatar(@CurrentUser() user: SessionUser | undefined, @UploadedFile() file: Express.Multer.File) {
    const current = await this.requireProfile(user);
    // 按文件头判定格式，不信 `file.mimetype`：那是请求里的 Content-Type，
    // 客户端改一个字节就能把任意文件声明成 image/png。
    const kind = file ? sniffImageKind(file.buffer) : null;
    if (!file || !kind) throw ApiErrors.badRequest(4000, "请选择 PNG / JPEG / GIF / WebP 图片");
    if (!this.config.lskyApiKey) throw ApiErrors.badRequest(4000, "头像上传服务未配置");
    const form = new FormData();
    form.append(
      "image",
      new Blob([file.buffer], { type: IMAGE_MIME[kind] }),
      file.originalname || `avatar.${IMAGE_EXTENSION[kind]}`,
    );
    form.append("token", this.config.lskyApiKey);
    const response = await fetch(this.config.lskyUploadUrl, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "User-Agent": "TaotaoMusic-AvatarUploader/1.0",
      },
      body: form,
    });
    const raw = await response.text();
    let payload: { result?: string; code?: number; message?: string; url?: string };
    try {
      payload = JSON.parse(raw) as typeof payload;
    } catch {
      const contentType = response.headers.get("content-type") || "未知类型";
      const preview = raw.replace(/\s+/g, " ").slice(0, 160);
      throw ApiErrors.badRequest(4000, `头像上传接口返回了无效响应（HTTP ${response.status}，${contentType}）：${preview}`);
    }
    const url = payload.url;
    if (!response.ok || payload.result !== "success" || !url) {
      throw ApiErrors.badRequest(4000, payload.message || "头像上传失败");
    }
    // 这个地址会被写进用户资料、再由别人的客户端去请求，所以必须过一遍白名单，
    // 不能因为「是上游返回的」就默认可信。
    const storedUrl = this.requireStoredAvatarUrl(url);
    return (await this.users.updateProfile(current.id, undefined, storedUrl))!;
  }

  /** 老账号补绑邮箱：仅允许当前尚未绑定邮箱的已登录用户使用。 */
  @RateLimit("email-verification")
  @Post("email/bind-verification")
  @HttpCode(HttpStatus.NO_CONTENT)
  async sendBindVerification(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>): Promise<void> {
    const current = await this.requireUserWithEmail(user);
    if (current.email) throw ApiErrors.conflict(4093, "当前账号已绑定邮箱，请使用换绑功能");
    const email = this.requireAllowedEmail(body?.email);
    if (await this.users.findByEmail(email)) throw ApiErrors.conflict(4092, "邮箱已注册");
    await this.emailVerification.send(email, "bind");
  }

  @Post("email/bind")
  @HttpCode(HttpStatus.NO_CONTENT)
  async bindEmail(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>): Promise<void> {
    const current = await this.requireUserWithEmail(user);
    if (current.email) throw ApiErrors.conflict(4093, "当前账号已绑定邮箱，请使用换绑功能");
    const email = this.requireAllowedEmail(body?.email);
    const code = this.auth.textOf(body?.verificationCode);
    if (!VERIFICATION_CODE_PATTERN.test(code) || !this.emailVerification.consume(email, code, "bind")) {
      throw ApiErrors.badRequest(4009, "邮箱验证码错误或已过期");
    }
    if (!(await this.users.bindEmail(current.id, email))) throw ApiErrors.conflict(4093, "当前账号已绑定邮箱，请使用换绑功能");
  }

  /** 换绑只要求当前会话有效，验证码发送到新邮箱，避免旧邮箱失效时用户无法迁移。 */
  @RateLimit("email-verification")
  @Post("email/change-verification")
  @HttpCode(HttpStatus.NO_CONTENT)
  async sendChangeVerification(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>): Promise<void> {
    const current = await this.requireUserWithEmail(user);
    if (!current.email) throw ApiErrors.conflict(4094, "当前账号尚未绑定邮箱，请先绑定");
    const email = this.requireAllowedEmail(body?.email);
    if (email === current.email) throw ApiErrors.badRequest(4008, "新邮箱不能与当前邮箱相同");
    if (await this.users.findByEmail(email)) throw ApiErrors.conflict(4092, "邮箱已注册");
    await this.emailVerification.send(email, "change");
  }

  @Post("email/change")
  @HttpCode(HttpStatus.NO_CONTENT)
  async changeEmail(@CurrentUser() user: SessionUser | undefined, @Body() body: Record<string, unknown>): Promise<void> {
    const current = await this.requireUserWithEmail(user);
    if (!current.email) throw ApiErrors.conflict(4094, "当前账号尚未绑定邮箱，请先绑定");
    const email = this.requireAllowedEmail(body?.email);
    const code = this.auth.textOf(body?.verificationCode);
    if (!VERIFICATION_CODE_PATTERN.test(code) || !this.emailVerification.consume(email, code, "change")) {
      throw ApiErrors.badRequest(4009, "邮箱验证码错误或已过期");
    }
    if (!(await this.users.changeEmail(current.id, email))) throw ApiErrors.conflict(4094, "当前账号尚未绑定邮箱，请先绑定");
  }

  private emailOf(value: unknown): string {
    return this.auth.textOf(value, true).toLowerCase();
  }

  private isAllowedEmail(email: string): boolean {
    const domain = email.slice(email.lastIndexOf("@") + 1);
    return EMAIL_PATTERN.test(email) && ALLOWED_EMAIL_DOMAINS.has(domain);
  }

  private requireAllowedEmail(value: unknown): string {
    const email = this.emailOf(value);
    if (!this.isAllowedEmail(email)) throw ApiErrors.badRequest(4008, "仅支持 QQ 邮箱（qq.com 或 foxmail.com）");
    return email;
  }

  private async requireUserWithEmail(user: SessionUser | undefined) {
    if (!user) throw ApiErrors.unauthorized(4010, "未登录");
    const current = await this.users.findByIdWithEmail(user.id);
    if (!current) throw ApiErrors.unauthorized(4010, "未登录");
    return current;
  }

  private async requireProfile(user: SessionUser | undefined) {
    if (!user) throw ApiErrors.unauthorized(4010, "未登录");
    const profile = await this.users.findProfileById(user.id);
    if (!profile) throw ApiErrors.unauthorized(4010, "未登录");
    return profile;
  }

  private optionalNickname(value: unknown): string | undefined {
    if (value === undefined) return undefined;
    const nickname = this.auth.textOf(value, true);
    if (!nickname || nickname.length > MAX_NICKNAME_LENGTH || /[\u0000-\u001F\u007F]/.test(nickname)) {
      throw ApiErrors.badRequest(4000, `昵称须为 1 至 ${MAX_NICKNAME_LENGTH} 个非控制字符`);
    }
    return nickname;
  }

  private optionalAvatarUrl(value: unknown): string | null | undefined {
    if (value === undefined) return undefined;
    if (value === null || value === "") return null;
    const avatarUrl = this.auth.textOf(value, true);
    const parsed = (() => {
      try { return new URL(avatarUrl); } catch { return null; }
    })();
    if (!parsed || parsed.protocol !== "https:" || avatarUrl.length > MAX_AVATAR_URL_LENGTH) {
      throw ApiErrors.badRequest(4000, "头像必须是 HTTPS 图片地址，且不超过 2048 个字符");
    }
    return avatarUrl;
  }

  /**
   * 校验图床返回的头像地址。
   *
   * 与 [optionalAvatarUrl] 的区别在于来源：那个校验的是**用户填的**地址，这个校验
   * 的是**上游响应里的**地址。后者看起来可信，其实不然 —— 图床被劫持、配置写错、
   * 或者上游返回一个跳转后的第三方 CDN 地址，都会让任意 URL 落进 `avatar_url`，
   * 之后每个渲染该用户头像的客户端都会去请求它。
   *
   * 基础要求是 https 且长度可控；配了 `LSKY_PUBLIC_HOSTS` 就再收紧到主机白名单。
   */
  private requireStoredAvatarUrl(raw: string): string {
    const trimmed = raw.trim();
    const parsed = (() => {
      try { return new URL(trimmed); } catch { return null; }
    })();
    if (!parsed || parsed.protocol !== "https:" || trimmed.length > MAX_AVATAR_URL_LENGTH) {
      throw ApiErrors.badRequest(4000, "头像上传接口返回了非法的图片地址");
    }
    const allowed = this.config.lskyPublicHosts;
    if (allowed.length > 0 && !allowed.includes(parsed.host.toLowerCase())) {
      throw ApiErrors.badRequest(4000, "头像上传接口返回了白名单之外的图片地址");
    }
    return trimmed;
  }
}
