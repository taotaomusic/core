import {
  Body, Controller, Delete, Get, HttpCode, HttpStatus, Param, Patch, Post, Put, Query, Req,
} from "@nestjs/common";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { RateLimit } from "../common/decorators/rate-limit.decorator";
import type { AdminAuthenticatedRequest } from "../common/request.types";
import { AdminGuarded } from "../admin-auth/admin-guarded.decorator";
import { RequireRole } from "../admin-auth/roles.decorator";
import { READ_ROLES, PRIVILEGED_READ_ROLES, WRITE_ROLES } from "../admin-auth/admin-roles";
import { AdminAuditService } from "../admin-auth/admin-audit.service";
import { isUniqueViolation } from "../database/pg-errors";
import { isMusicSource } from "./music-source.client";
import type { MusicSource } from "./music-source.client";
import { MusicSourceAccountRepository, maskPhoneNumber } from "./music-source-account.repository";
import type { MusicSourceAccountPatch } from "./music-source-account.repository";
import { MusicSourceRegistry } from "./music-source.registry";

/** 各字段的长度上界。上游对这些字段没有明确限制，这里按「人填的东西」设上界。 */
const MAX_LABEL_LENGTH = 40;
const MAX_REMARK_LENGTH = 200;
const MAX_TOKEN_LENGTH = 512;
const MAX_UID_LENGTH = 64;

/** 中国大陆手机号。上游只认大陆号码，明显不合法的输入在本地就挡掉，省一次上游请求。 */
const PHONE_PATTERN = /^1[3-9]\d{9}$/;

/** 验证码长度按上游实际发的是 4–8 位纯数字处理。 */
const SMS_CODE_PATTERN = /^\d{4,8}$/;

/**
 * 音源账号管理。
 *
 * ## 明文凭据的去向
 *
 * `token` 只允许从两个方向穿过这个控制器：**进来**（管理员手填或短信登录换回来）
 * 和**落库**。出去的方向一律是掩码 —— 列表走 [MusicSourceAccountRepository.summaryOf]，
 * 审计只记 uid 与掩码手机号。**任何把 `token` 写进审计 detail 的改动都是泄漏**：
 * 审计日志是读权限更低（`PRIVILEGED_READ_ROLES`）也能看到的东西。
 *
 * ## 守卫
 *
 * 逐个方法标注 `@AdminGuarded()`，不挂类上 —— 类级守卫会在鉴权前生效，
 * 但这里全是要鉴权的路由，看起来像多余的小心，实则不然：`@Public()` 是类级的
 * （绕开全局访问令牌守卫），一旦将来有人往这个控制器加一条公开路由，
 * 逐个标注能保证它不会意外继承管理权限。详见 [AdminGuarded] 的说明。
 */
@Public()
@RateLimit("admin")
@Controller("app/admin/music-sources")
export class MusicSourceAdminController {
  constructor(
    private readonly accounts: MusicSourceAccountRepository,
    private readonly registry: MusicSourceRegistry,
    private readonly audit: AdminAuditService,
  ) {}

  // ---------- 读 ----------

  /**
   * 账号列表。可按音源过滤。**响应里只有掩码，没有明文凭据。**
   *
   * 用 [PRIVILEGED_READ_ROLES] 而不是 [READ_ROLES]：响应里带掩码手机号与上游账号 ID，
   * 属于个人信息面。观察者能进后台是为了看发布状态这类运营数据，不是来管音源账号的。
   * 改这里的角色必须同时改前端页签的可见性（`App.vue`），否则观察者会点进一个只会报错的页签。
   */
  @AdminGuarded()
  @Get()
  @RequireRole(...PRIVILEGED_READ_ROLES)
  async list(@Query("source") rawSource?: string) {
    const source = typeof rawSource === "string" && rawSource.trim() !== "" ? rawSource.trim() : undefined;
    if (source !== undefined && !isMusicSource(source)) throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    return this.accounts.list(source);
  }

  /**
   * 可选音源清单与各自的能力。
   *
   * 前端据此渲染「新增账号」的来源下拉框，以及决定要不要显示短信登录表单 ——
   * 而不是在 Vue 里硬编码「只有酷我能登录」。加一个新音源时后端改一处即可。
   *
   * `numericUidOnly` 同样走这条路：前端据它决定 uid 输入框要不要提示「必须纯数字」。
   * 真正的拦截在 [assertUidShape] 里做，前端提示只是提前告知，**不能替代服务端校验**。
   *
   * 这里用 [READ_ROLES] 而不是 [PRIVILEGED_READ_ROLES]：响应里只有音源名与能力标记，
   * 不含任何账号或个人数据，观察者看一眼「支持哪些音源」没有风险。
   *
   * 声明在 `:id` 之前：Nest 按声明顺序匹配，`available` 若排在后面会被 `:id` 吃掉。
   */
  @AdminGuarded()
  @Get("available")
  @RequireRole(...READ_ROLES)
  available() {
    return this.registry.sources().map((source) => {
      const client = this.registry.of(source);
      return {
        source,
        displayName: client.displayName,
        supportsLogin: this.registry.supportsCredentialLogin(source),
        numericUidOnly: this.registry.numericUidOnly(source),
      };
    });
  }

  // ---------- 写 ----------

  /**
   * 手工新增一条账号。
   *
   * 允许只填手机号先占位（凭据留空，播放链路会跳过它），也允许直接把已有 token
   * 填进来。凭据留空的账号在列表里能被认出来（掩码为空串），不会被误当成可用。
   */
  @AdminGuarded()
  @Post()
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.CREATED)
  async create(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const source = this.requiredSource(body?.source);
    const patch = this.patchOf(body);
    this.assertUidShape(source, patch);
    let created;
    try {
      created = await this.accounts.create(source, patch);
    } catch (error) {
      // 部分唯一索引拦下「同一音源同一 uid 的第二条」。翻译成 409 而不是让它
      // 冒成 502 —— 这是管理员的操作冲突，不是服务故障。
      if (isUniqueViolation(error)) throw ApiErrors.conflict(4090, "该音源下已存在相同账号");
      throw error;
    }
    await this.audit.record(request, "music_source.create", "music_source_account", String(created.id), {
      source,
      maskedPhone: created.maskedPhone,
      uid: created.uid,
      enabled: created.enabled === 1,
    });
    // 新增的账号可能立刻成为「启用状态里最新的那一条」，必须清缓存，
    // 否则最长 30 秒内播放还在按旧凭据（或匿名）走。
    this.registry.invalidateCredentialCache(source);
    return created;
  }

  /**
   * 局部更新。只写提交过的字段。
   *
   * `enabled` 不在这里改 —— 走 [setEnabled]，两者的审计动作不同，
   * 「停用了一个账号」和「改了账号信息」在审计里要能分开看。
   */
  @AdminGuarded()
  @Patch(":id")
  @RequireRole(...WRITE_ROLES)
  async update(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") rawId: string,
    @Body() body: Record<string, unknown>,
  ) {
    const id = this.requiredId(rawId);
    const patch = this.patchOf(body, true);
    if (Object.keys(patch).length === 0) throw ApiErrors.badRequest(4007, "没有需要更新的字段");
    // 先取一次现有账号：uid 的形状规则取决于音源，而 PATCH 的 body 里没有 source。
    const existing = await this.accounts.findById(id);
    if (!existing) throw ApiErrors.notFound(4042, "音源账号不存在");
    this.assertUidShape(this.requiredSource(existing.source), patch);
    const updated = await this.accounts.update(id, patch);
    if (!updated) throw ApiErrors.notFound(4042, "音源账号不存在");
    // 只记改过哪些字段与掩码后的手机号，**不记 token、不记 token 的掩码** ——
    // 掩码也能被用来确认「某个 token 是不是这一个」，没必要留这个痕迹。
    await this.audit.record(request, "music_source.update", "music_source_account", String(id), {
      fields: Object.keys(patch),
      maskedPhone: updated.maskedPhone,
      uid: updated.uid,
    });
    // 改的可能是 token / uid / 备注。token 与 uid 直接影响播放，所以一律清缓存。
    this.registry.invalidateCredentialCache(this.requiredSource(updated.source));
    return updated;
  }

  /** 启用或停用。停用后播放链路立刻不再取用它（本方法会清掉凭据缓存）。 */
  @AdminGuarded()
  @Put(":id/enabled")
  @RequireRole(...WRITE_ROLES)
  async setEnabled(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") rawId: string,
    @Body() body: Record<string, unknown>,
  ) {
    const id = this.requiredId(rawId);
    if (typeof body?.enabled !== "boolean") throw ApiErrors.badRequest(4007, "enabled 必须是布尔值");
    const updated = await this.accounts.setEnabled(id, body.enabled);
    if (!updated) throw ApiErrors.notFound(4042, "音源账号不存在");
    await this.audit.record(
      request,
      body.enabled ? "music_source.enable" : "music_source.disable",
      "music_source_account",
      String(id),
      { source: updated.source, uid: updated.uid },
    );
    // 启停改变的是「哪一条算当前生效」，同样必须清缓存 —— 停用后要求立刻切回匿名。
    this.registry.invalidateCredentialCache(this.requiredSource(updated.source));
    return updated;
  }

  /**
   * 连通性测试：用这条账号的凭据真实取一次播放地址，把探测到的音质报回来。
   *
   * **失败也要落库并回报**，不能只在成功时更新状态 —— 管理员需要看到「上次为什么失败」。
   * 探测结果写在 `last_note` 里，成功时形如 `mp3 128kbps`；账号还没填凭据时按匿名探测，
   * 结果形如 `匿名 mp3 128kbps`。
   *
   * 不给 `musicId` 时由适配器自己挑一首当前可播的歌，避免写死的 ID 随曲库下线失效。
   *
   * 这个接口**不退回匿名**：凭据无效就是要报出来。播放链路同样不做匿名兜底
   * （见 `KuwoClient.resolveLink`），两边口径一致 —— 兜底会把「配的账号没生效」
   * 伪装成正常播放，管理员就没有任何途径看出账号是坏的。
   */
  @AdminGuarded()
  @Post(":id/probe")
  @RequireRole(...WRITE_ROLES)
  async probe(
    @Req() request: AdminAuthenticatedRequest,
    @Param("id") rawId: string,
    @Body() body: Record<string, unknown>,
  ) {
    const id = this.requiredId(rawId);
    const credential = await this.accounts.credentialOf(id);
    if (!credential) throw ApiErrors.notFound(4042, "音源账号不存在");

    const summary = await this.accounts.findById(id);
    if (!summary) throw ApiErrors.notFound(4042, "音源账号不存在");
    const manager = this.registry.credentialManagerOf(this.requiredSource(summary.source));

    const musicId = this.optionalPositiveInt(body?.musicId, "探测曲目 ID");
    try {
      const note = await manager.probeCredential(credential, musicId);
      await this.accounts.recordCheck(id, "ok", "", note);
      await this.audit.record(request, "music_source.probe", "music_source_account", String(id), {
        source: summary.source,
        status: "ok",
        note,
      });
      return { id, status: "ok", note };
    } catch (error) {
      const message = error instanceof Error ? error.message : "探测失败";
      await this.accounts.recordCheck(id, "invalid", message);
      await this.audit.record(request, "music_source.probe", "music_source_account", String(id), {
        source: summary.source,
        status: "invalid",
      });
      throw error;
    }
  }

  @AdminGuarded()
  @Delete(":id")
  @RequireRole(...WRITE_ROLES)
  @HttpCode(HttpStatus.NO_CONTENT)
  async remove(@Req() request: AdminAuthenticatedRequest, @Param("id") rawId: string): Promise<void> {
    const id = this.requiredId(rawId);
    const summary = await this.accounts.findById(id);
    if (!summary || !(await this.accounts.remove(id))) throw ApiErrors.notFound(4042, "音源账号不存在");
    await this.audit.record(request, "music_source.delete", "music_source_account", String(id), {
      source: summary.source,
      uid: summary.uid,
    });
    // 删掉的如果是当前生效的那一条，播放要立刻切回匿名（或下一条启用的）。
    this.registry.invalidateCredentialCache(this.requiredSource(summary.source));
  }

  // ---------- 登录 ----------

  /**
   * 发短信验证码。
   *
   * 这是**唯一会向第三方产生真实外发动作**的接口（真发短信、按条计费），所以：
   * 独立限流分桶（按地址 5 次 + 按手机号 3 次 / 15 分钟）、要求 `WRITE_ROLES`、
   * 并且**把手机号当成不可信输入**校验格式后才提交给上游。
   *
   * 验证码不落库、不回传：第二步拿它去上游换凭据时由上游校验，我们这边不留状态，
   * 也就不存在「自己实现一套验证码校验」可能带来的漏洞。
   */
  @AdminGuarded()
  @RateLimit("music-source-sms")
  @Post("sms")
  @RequireRole(...WRITE_ROLES)
  async sendSms(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const source = this.requiredSource(body?.source);
    const phone = this.requiredPhone(body?.phone);
    await this.registry.credentialManagerOf(source).sendLoginSms(phone);
    // 审计里只留掩码：完整手机号是个人信息，审计日志的可见范围比音源配置更宽。
    await this.audit.record(request, "music_source.sms", "music_source_account", null, {
      source,
      maskedPhone: maskPhoneNumber(phone),
    });
    return { sent: true };
  }

  /**
   * 用验证码换取凭据并落库。
   *
   * 同一个账号重复登录只刷新 token，不会堆出第二行（`(source, uid)` 是身份）。
   * 成功后**必须清播放链路的凭据缓存**，否则新账号最多 30 秒后才生效，
   * 管理员会看到「登录成功但测试仍然失败」而去怀疑凭据本身。
   */
  @AdminGuarded()
  @Post("login")
  @RequireRole(...WRITE_ROLES)
  async login(@Req() request: AdminAuthenticatedRequest, @Body() body: Record<string, unknown>) {
    const source = this.requiredSource(body?.source);
    const phone = this.requiredPhone(body?.phone);
    const code = this.requiredCode(body?.code);
    const label = this.optionalText(body?.label, MAX_LABEL_LENGTH, "备注名");

    const manager = this.registry.credentialManagerOf(source);
    const credential = await manager.loginBySms(phone, code);
    // 上游返回的 uid 也要过一遍形状校验：万一上游换了字段，存进去就是一个坏账号，
    // 而它要到播放时才暴露（搜索照常、取不到地址）。
    this.assertUidShape(source, { uid: credential.uid });
    const account = await this.accounts.upsertByLogin(source, phone, credential.token, credential.uid, label);
    manager.invalidateCredentialCache();

    await this.audit.record(request, "music_source.login", "music_source_account", String(account.id), {
      source,
      maskedPhone: account.maskedPhone,
      uid: account.uid,
    });
    return account;
  }

  // ---------- 参数校验 ----------

  private requiredSource(raw: unknown): MusicSource {
    const source = typeof raw === "string" ? raw.trim() : "";
    if (!isMusicSource(source)) throw ApiErrors.badRequest(4001, "不支持的音乐来源");
    return source;
  }

  private requiredId(rawId: string): number {
    const id = Number(rawId);
    if (!Number.isSafeInteger(id) || id <= 0) throw ApiErrors.badRequest(4007, "音源账号 ID 不合法");
    return id;
  }

  private requiredPhone(raw: unknown): string {
    const phone = typeof raw === "string" ? raw.trim() : "";
    if (!PHONE_PATTERN.test(phone)) throw ApiErrors.badRequest(4007, "手机号格式不正确");
    return phone;
  }

  private requiredCode(raw: unknown): string {
    const code = typeof raw === "string" ? raw.trim() : "";
    if (!SMS_CODE_PATTERN.test(code)) throw ApiErrors.badRequest(4007, "验证码格式不正确");
    return code;
  }

  /**
   * 组装可选字段的补丁。
   *
   * [lenient] 为真时用于 PATCH：`enabled` 被显式忽略（走 [setEnabled]），
   * 其余字段只在**确实出现过**时进入补丁 —— 传了空串就是「清空」，没传就是「不动」，
   * 这两件事必须区分得开，否则界面上的「清空备注」永远做不到。
   */
  private patchOf(body: Record<string, unknown>, lenient = false): MusicSourceAccountPatch {
    const patch: MusicSourceAccountPatch = {};
    if (body?.label !== undefined) patch.label = this.optionalText(body.label, MAX_LABEL_LENGTH, "备注名");
    if (body?.phone !== undefined) {
      const phone = typeof body.phone === "string" ? body.phone.trim() : "";
      // 允许清空手机号（手工填 token 的账号本来就没有手机号）。
      if (phone !== "" && !PHONE_PATTERN.test(phone)) throw ApiErrors.badRequest(4007, "手机号格式不正确");
      patch.phone = phone;
    }
    if (body?.token !== undefined) patch.token = this.optionalText(body.token, MAX_TOKEN_LENGTH, "token");
    if (body?.uid !== undefined) patch.uid = this.optionalText(body.uid, MAX_UID_LENGTH, "uid");
    if (body?.remark !== undefined) patch.remark = this.optionalText(body.remark, MAX_REMARK_LENGTH, "备注");
    if (!lenient && body?.enabled !== undefined) patch.enabled = body.enabled === true;
    return patch;
  }

  /**
   * 校验 uid 的形状。
   *
   * 空串表示「清空凭据」，允许 —— 手工填 token 的账号本来就可能没有 uid。
   * 非空则按音源的规则校验，规则来自 `MusicSourceCredentialManager.numericUidOnly`。
   *
   * 酷我要求纯数字，理由是实测出来的：传非数字 uid 时上游会拒绝下发**任何播放地址**，
   * 而搜索、单曲信息、歌词全部照常 —— 表现为「搜得到、放不出」，换任何 token 都救不回来。
   * 这种账号存进库就是坏的，**只有在写入时拦掉才给得出可读的错误**。
   */
  private assertUidShape(source: MusicSource, patch: MusicSourceAccountPatch): void {
    const uid = patch.uid;
    if (uid === undefined || uid === "") return;
    if (!this.registry.numericUidOnly(source)) return;
    if (!/^\d+$/.test(uid)) {
      throw ApiErrors.badRequest(4007, `${this.registry.of(source).displayName}的账号 ID 必须是纯数字`);
    }
  }

  /** 取一个可选的文本字段。允许空串（表示清空），但拒绝非字符串与超长。 */
  private optionalText(raw: unknown, maxLength: number, field: string): string {
    if (raw === undefined || raw === null) return "";
    if (typeof raw !== "string") throw ApiErrors.badRequest(4007, `${field}必须是字符串`);
    const value = raw.trim();
    if (value.length > maxLength) throw ApiErrors.badRequest(4007, `${field}不能超过 ${maxLength} 个字符`);
    return value;
  }

  private optionalPositiveInt(raw: unknown, field: string): number | undefined {
    if (raw === undefined || raw === null || raw === "") return undefined;
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || value <= 0) throw ApiErrors.badRequest(4007, `${field}必须是正整数`);
    return value;
  }
}
