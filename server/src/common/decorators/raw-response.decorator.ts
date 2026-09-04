import { SetMetadata } from "@nestjs/common";

export const RAW_RESPONSE_METADATA_KEY = "taotao:raw-response";

/**
 * 标记该路由自己写响应体，不要套统一信封。
 *
 * 搜索返回 NDJSON 流、歌词默认返回纯文本、播放和安装包是二进制流 ——
 * 这些一旦被信封包成 `{code,data}`，客户端会取不到内容且**不报任何错误**
 * （搜索会表现为「搜不到东西」），是最隐蔽的破契约方式。
 */
export const RawResponse = () => SetMetadata(RAW_RESPONSE_METADATA_KEY, true);
