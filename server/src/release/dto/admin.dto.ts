import { IsInt, IsOptional, IsString, Matches, Max, Min } from "class-validator";

export class RolloutDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsInt()
  @Min(1)
  versionCode: number;

  @IsInt()
  @Min(0)
  @Max(100)
  percent: number;

  /** 传 false 可下架某个版本。 */
  @IsOptional()
  enabled?: boolean;
}

export class MinVersionDto {
  @IsOptional()
  @IsString()
  channel?: string;

  /** 0 表示取消强制更新下限。 */
  @IsInt()
  @Min(0)
  versionCode: number;
}

/** 热修复补丁的放量调整。补丁按「宿主版本 + 补丁版本」定位，比发布多一维。 */
export class PatchRolloutDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsInt()
  @Min(1)
  targetVersionCode: number;

  @IsInt()
  @Min(1)
  patchVersion: number;

  @IsInt()
  @Min(0)
  @Max(100)
  percent: number;

  /** 传 false 可紧急下架某个补丁。 */
  @IsOptional()
  enabled?: boolean;
}

/**
 * 编辑已登记的发布记录（更新说明 / 安装包外链）。
 *
 * 两个字段都是「传了才更新」：`undefined` / 未传保持原值。注意 `@IsOptional()`
 * 会把 null 和 undefined 一起跳过，所以「清空」用空串表达，不用 null。
 */
export class ReleaseEditDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsInt()
  @Min(1)
  versionCode: number;

  @IsOptional()
  @IsString()
  releaseNote?: string;

  /**
   * 安装包外链（GitHub Release 资产地址等）。这个值会原样下发给客户端当下载地址，
   * 必须是 http(s)；空串表示清除外链、回落本机 `/app/apk` 端点。
   */
  @IsOptional()
  @Matches(/^$|^https?:\/\//i, { message: "apkUrl 必须是 http(s) 地址或空串" })
  apkUrl?: string;
}

/**
 * 创建「外链版本」：不上传安装包字节，直接登记一个外部下载地址（GitHub Release
 * 资产、对象存储等）。与 webhook 登记的记录同形状 —— `apk_file` 留空、`apk_url`
 * 原样下发当下载地址。sha256 / 大小选填：sha256 是断更比对的依据，能给就给。
 */
export class ReleaseLinkDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsInt()
  @Min(1)
  versionCode: number;

  @IsString()
  @Matches(/^\d+(\.\d+)*$/, { message: "versionName 必须是数字点分段（如 1.0.70）" })
  versionName: string;

  @IsString()
  @Matches(/^https?:\/\//i, { message: "apkUrl 必须是 http(s) 地址" })
  apkUrl: string;

  /** 安装包字节数，选填，仅用于后台展示与下载提示。 */
  @IsOptional()
  @IsInt()
  @Min(0)
  apkSize?: number;

  /** 安装包 sha256（64 位十六进制），选填；下发前统一转小写。 */
  @IsOptional()
  @Matches(/^[0-9a-fA-F]{64}$/, { message: "sha256 必须是 64 位十六进制" })
  sha256?: string;

  @IsOptional()
  @IsString()
  releaseNote?: string;

  @IsOptional()
  @IsInt()
  @Min(0)
  @Max(100)
  rollout?: number;

  @IsOptional()
  @IsInt()
  @Min(0)
  minSdk?: number;

  @IsOptional()
  enabled?: boolean;
}

export class RemoteConfigDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsString()
  key: string;

  /**
   * 配置值。传 `null` 表示删除该键。
   *
   * 刻意不加校验器：class-validator 的 `@IsOptional()` 会把 null 和 undefined
   * 一视同仁地跳过，那就分不出「删除」和「没传」了。
   */
  value?: unknown;

  @IsOptional()
  @IsInt()
  minVersionCode?: number;

  @IsOptional()
  @IsInt()
  maxVersionCode?: number;
}
