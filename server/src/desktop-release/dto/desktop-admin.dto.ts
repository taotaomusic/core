import { IsBoolean, IsInt, IsOptional, IsString, Matches, Max, Min } from "class-validator";

export class DesktopRolloutDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsOptional()
  @IsString()
  @Matches(/^[a-z0-9][a-z0-9._-]{0,31}$/i)
  architecture?: string;

  @IsInt()
  @Min(1)
  versionCode: number;

  @IsInt()
  @Min(0)
  @Max(100)
  percent: number;

  @IsOptional()
  @IsBoolean()
  enabled?: boolean;
}

export class DesktopMinVersionDto {
  @IsOptional()
  @IsString()
  channel?: string;

  @IsOptional()
  @IsString()
  @Matches(/^[a-z0-9][a-z0-9._-]{0,31}$/i)
  architecture?: string;

  @IsInt()
  @Min(0)
  versionCode: number;
}
