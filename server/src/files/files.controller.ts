import { Controller, Get, Param, Res } from "@nestjs/common";
import type { Response } from "express";
import { ApiErrors } from "../common/api.exception";
import { Public } from "../common/decorators/public.decorator";
import { AvatarStoreService } from "./avatar-store.service";

/**
 * 用户文件的公开下载端点。
 *
 * 头像必须匿名可读：渲染方（Android 端 Coil、管理后台的 `<img>`）不会携带
 * 访问令牌，这与旧图床外链的语义一致。可枚举性由随机 token 兜住 —— 地址是
 * 128 位随机数，不能从用户 ID 推出来，头像更新后旧地址直接 404。
 */
@Controller("files")
export class FilesController {
  constructor(private readonly avatars: AvatarStoreService) {}

  @Public()
  @Get("avatars/:token")
  async serveAvatar(@Param("token") token: string, @Res() response: Response): Promise<void> {
    const row = await this.avatars.find(token);
    if (!row) throw ApiErrors.notFound(4040, "头像不存在或已被更新");
    // token 每次上传都会变化，URL 与内容一一对应，缓存可以放心标记为不可变。
    response.setHeader("Content-Type", row.content_type);
    response.setHeader("Content-Length", String(row.byte_size));
    response.setHeader("Cache-Control", "public, max-age=31536000, immutable");
    response.end(row.bytes);
  }
}
