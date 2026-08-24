import { Injectable, Logger } from "@nestjs/common";
import nodemailer from "nodemailer";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { renderVerificationEmail, type VerificationEmailContent } from "./mail.template";

/** SMTP 投递能力；业务模块只提供邮件主题与变量，不直接依赖 nodemailer。 */
@Injectable()
export class MailService {
  private readonly logger = new Logger(MailService.name);
  private readonly transporter;

  constructor(private readonly config: AppConfigService) {
    this.transporter = config.isSmtpConfigured
      ? nodemailer.createTransport({
          host: config.smtpHost,
          port: config.smtpPort,
          secure: config.smtpPort === 465,
          auth: { user: config.smtpUser, pass: config.smtpPassword },
        })
      : null;
  }

  async sendVerification(to: string, subject: string, content: VerificationEmailContent): Promise<void> {
    if (!this.transporter) throw ApiErrors.upstream("邮件服务尚未配置，请联系管理员");
    const rendered = renderVerificationEmail(content);
    try {
      await this.transporter.sendMail({ from: this.config.smtpFrom, to, subject, ...rendered });
    } catch (error) {
      this.logger.error(`发送邮件失败：${error instanceof Error ? error.message : String(error)}`);
      throw ApiErrors.upstream("验证码发送失败，请稍后重试");
    }
  }
}
