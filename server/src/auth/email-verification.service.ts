import { Injectable, Logger } from "@nestjs/common";
import { createHash, randomInt } from "node:crypto";
import nodemailer from "nodemailer";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";

const CODE_LIFETIME_MS = 10 * 60_000;
const RESEND_COOLDOWN_MS = 60_000;

/** 发送并核验注册邮箱验证码。 */
@Injectable()
export class EmailVerificationService {
  private readonly logger = new Logger(EmailVerificationService.name);
  private readonly lastSentAt = new Map<string, number>();
  /** 仅保存哈希且只在当前进程内存中存在；重启后所有未使用验证码自然失效。 */
  private readonly codes = new Map<string, { hash: string; expiresAt: number; failedAttempts: number }>();
  private readonly transporter;

  constructor(
    private readonly config: AppConfigService,
  ) {
    this.transporter = config.isSmtpConfigured
      ? nodemailer.createTransport({
          host: config.smtpHost,
          port: config.smtpPort,
          secure: config.smtpPort === 465,
          auth: { user: config.smtpUser, pass: config.smtpPassword },
        })
      : null;
  }

  async send(email: string): Promise<void> {
    if (!this.transporter) throw ApiErrors.upstream("邮件服务尚未配置，请联系管理员");
    const now = Date.now();
    const previous = this.lastSentAt.get(email) ?? 0;
    if (now - previous < RESEND_COOLDOWN_MS) {
      throw ApiErrors.tooManyRequests();
    }

    const code = String(randomInt(0, 1_000_000)).padStart(6, "0");
    const codeHash = this.hash(email, code);
    const expiresAt = now + CODE_LIFETIME_MS;
    try {
      await this.transporter.sendMail({
        from: this.config.smtpFrom,
        to: email,
        subject: "桃桃音乐注册验证码",
        text: `你的桃桃音乐注册验证码是：${code}\n\n验证码 10 分钟内有效，请勿向任何人泄露。`,
        html: this.emailHtml(code),
      });
    } catch (error) {
      this.logger.error(`发送注册验证码失败：${error instanceof Error ? error.message : String(error)}`);
      throw ApiErrors.upstream("验证码发送失败，请稍后重试");
    }
    this.codes.set(email, { hash: codeHash, expiresAt, failedAttempts: 0 });
    this.lastSentAt.set(email, now);
  }

  consume(email: string, code: string): boolean {
    const item = this.codes.get(email);
    if (!item || item.expiresAt <= Date.now()) {
      this.codes.delete(email);
      return false;
    }
    if (item.hash !== this.hash(email, code)) {
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
  private emailHtml(code: string): string {
    return `<!doctype html>
<html lang="zh-CN">
  <body style="margin:0;padding:24px 12px;background:#fff7f4;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;color:#332522;">
    <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0">
      <tr><td align="center">
        <table role="presentation" width="100%" cellspacing="0" cellpadding="0" border="0" style="max-width:520px;background:#ffffff;border-radius:24px;overflow:hidden;box-shadow:0 12px 32px rgba(139,67,49,.12);">
          <tr><td style="padding:32px 36px 28px;background:linear-gradient(135deg,#ff8a70,#ffb6a7);color:#ffffff;">
            <div style="font-size:26px;font-weight:700;letter-spacing:.5px;">桃桃音乐</div>
            <div style="margin-top:8px;font-size:14px;opacity:.92;">让喜欢的声音，陪你久一点</div>
          </td></tr>
          <tr><td style="padding:34px 36px 18px;">
            <h1 style="margin:0;font-size:22px;line-height:1.4;">验证你的邮箱</h1>
            <p style="margin:14px 0 24px;font-size:15px;line-height:1.7;color:#76635e;">请输入以下验证码以完成桃桃音乐注册：</p>
            <div style="padding:18px 12px;border-radius:16px;background:#fff2ed;text-align:center;color:#df5f47;font-size:32px;font-weight:700;letter-spacing:10px;">${code}</div>
            <p style="margin:24px 0 0;font-size:14px;line-height:1.7;color:#76635e;">验证码 10 分钟内有效，请勿将它分享给任何人。</p>
          </td></tr>
          <tr><td style="padding:22px 36px 28px;font-size:12px;line-height:1.6;color:#a2918c;">如果不是你本人发起的注册，请忽略此邮件。<br>此邮件由系统自动发送，请勿直接回复。</td></tr>
        </table>
      </td></tr>
    </table>
  </body>
</html>`;
  }
}
