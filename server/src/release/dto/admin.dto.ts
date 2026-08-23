import { IsInt, IsOptional, IsString, Max, Min } from "class-validator";

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
