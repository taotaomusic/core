import { Module } from "@nestjs/common";
import { TencentClient } from "./tencent.client";

/** 上游适配层：把第三方接口的不一致（成功码、字段名、音质降级）收敛在这里。 */
@Module({
  providers: [TencentClient],
  exports: [TencentClient],
})
export class UpstreamModule {}
