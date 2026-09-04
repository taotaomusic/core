import { SetMetadata } from "@nestjs/common";

export const PUBLIC_METADATA_KEY = "taotao:public";

/**
 * 标记该路由不需要访问令牌。
 *
 * 迁移前鉴权靠「门禁那行代码的位置」来保证 —— 在它之后注册的路由才受保护，
 * 加新路由放错位置就是漏洞。现在默认全部需要令牌，公开的必须显式标注，
 * 漏标只会导致该接口意外要求登录（能被立刻发现），而不是意外裸奔。
 */
export const Public = () => SetMetadata(PUBLIC_METADATA_KEY, true);
