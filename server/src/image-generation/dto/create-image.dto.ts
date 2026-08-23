import { ArrayMaxSize, Equals, IsArray, IsIn, IsOptional, IsString, IsUrl } from "class-validator";

const ASPECT_RATIOS = ["1:1", "4:3", "3:4", "16:9", "9:16", "3:2", "2:3", "2:1", "1:2"] as const;
const IMAGE_SIZES = ["1K", "2K", "4K"] as const;
const QUALITY_LEVELS = ["low", "medium", "high"] as const;

/** 创建 gpt-image-2 图片任务的请求参数。 */
export class CreateImageDto {
  @IsString()
  @Equals("gpt-image-2")
  model: string;

  @IsString()
  prompt: string;

  @IsOptional()
  @IsArray()
  @ArrayMaxSize(8)
  @IsUrl({ protocols: ["http", "https"], require_protocol: true }, { each: true })
  images?: string[];

  @IsOptional()
  @IsIn(ASPECT_RATIOS)
  aspectRatio?: (typeof ASPECT_RATIOS)[number];

  @IsOptional()
  @IsIn(IMAGE_SIZES)
  imageSize?: (typeof IMAGE_SIZES)[number];

  @IsOptional()
  @IsIn(QUALITY_LEVELS)
  quality?: (typeof QUALITY_LEVELS)[number];
}
