import { Module } from "@nestjs/common";
import { DatabaseModule } from "../database/database.module";
import { AvatarStoreService } from "./avatar-store.service";
import { FilesController } from "./files.controller";

/** 用户文件的存储与公开下载（头像等）。 */
@Module({
  imports: [DatabaseModule],
  controllers: [FilesController],
  providers: [AvatarStoreService],
  exports: [AvatarStoreService],
})
export class FilesModule {}
