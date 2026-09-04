import { Injectable } from "@nestjs/common";
import { createHash, randomInt } from "node:crypto";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { MailService } from "../mail/mail.service";

const CODE_LIFETIME_MS = 10 * 60_000;
const RESEND_COOLDOWN_MS = 60_000;
export type VerificationPurpose = "register" | "bind" | "change";

/** 发送并核验注册邮箱验证码。 */
@Injectable()
export class EmailVerificationService {
  private readonly lastSentAt = new Map<string, number>();
  /** 仅保存哈希且只在当前进程内存中存在；重启后所有未使用验证码自然失效。 */
  private readonly codes = new Map<string, { hash: string; expiresAt: number; failedAttempts: number; purpose: VerificationPurpose }>();

  constructor(
    private readonly mail: MailService,
    private readonly config: AppConfigService,
  ) {}

  async send(email: string, purpose: VerificationPurpose): Promise<void> {
    const now = Date.now();
    const previous = this.lastSentAt.get(email) ?? 0;
    if (now - previous < RESEND_COOLDOWN_MS) {
      throw ApiErrors.tooManyRequests();
    }

    // 独立验证库可以在 NODE_ENV=test 下配置固定验证码，完整覆盖真实注册链路，
    // 又无需依赖外部 SMTP。该配置在环境校验中被严格限制，不能用于生产。
    const code = this.config.emailVerificationTestCode ?? String(randomInt(0, 1_000_000)).padStart(6, "0");
    const codeHash = this.hash(email, code);
    const expiresAt = now + CODE_LIFETIME_MS;
    if (!this.config.emailVerificationTestCode) {
      await this.mail.sendVerification(email, `桃桃音乐${this.purposeText(purpose)}验证码`, {
        title: this.purposeText(purpose),
        description: `请输入以下验证码以${this.purposeText(purpose)}：`,
        code,
      });
    }
    this.codes.set(email, { hash: codeHash, expiresAt, failedAttempts: 0, purpose });
    this.lastSentAt.set(email, now);
  }

  consume(email: string, code: string, purpose: VerificationPurpose): boolean {
    const item = this.codes.get(email);
    if (!item || item.expiresAt <= Date.now()) {
      this.codes.delete(email);
      return false;
    }
    if (item.purpose !== purpose || item.hash !== this.hash(email, code)) {
      // 允许用户纠正一次手误，但最多五次，避免六位验证码被暴力猜测。
      item.failedAttempts++;
      if (item.failedAttempts >= 5) this.codes.delete(email);
      return false;
    }
    // 成功校验后立即抛弃，不能注册多个账号。
    this.codes.delete(email);
    return true;
  }

  private hash(email: string, code: string): string {
    return createHash("sha256").update(`${email}:${code}`).digest("hex");
  }

  /** 邮件客户端会直接渲染这段 HTML；纯内联样式保证 QQ 邮箱等客户端兼容。 */
  private purposeText(purpose: VerificationPurpose): string {
    return { register: "完成注册", bind: "绑定邮箱", change: "更换邮箱" }[purpose];
  }
}
