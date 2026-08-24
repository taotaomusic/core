import { Global, Module } from "@nestjs/common";
import { MailService } from "./mail.service";

/** 所有邮件投递与模板的统一入口。 */
@Global()
@Module({ providers: [MailService], exports: [MailService] })
export class MailModule {}
