import { HttpStatus, Injectable, Logger } from "@nestjs/common";
import { ApiErrors, ApiException } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { ApiKeyRepository } from "./api-key.repository";
import type { CreateImageDto } from "./dto/create-image.dto";
import { ImageTaskRepository } from "./image-task.repository";

const IMAGE_API_CHANNEL = "GPTIMAGE2";
const QUOTA_PER_TASK = 1;

type ApiSweetPayload = {
  code?: number;
  msg?: string;
  message?: string;
};

type ApiSweetCreateResponse = ApiSweetPayload & {
  data?: { task_id?: string; status?: string };
};

type ApiSweetResultResponse = ApiSweetPayload & {
  task_id?: string;
  state?: string;
  progress?: number;
  created_at?: number;
  completed_at?: number;
  result?: { image_url?: string } | null;
  error?: { message?: string } | string | null;
};

export type ImageTaskResult = {
  taskId: string;
  state: "IN_PROGRESS" | "COMPLETED" | "FAILED";
  progress: number;
  createdAt: number | null;
  completedAt: number | null;
  result: { imageUrl: string } | null;
  error: { message: string } | null;
};

/** ApiSweet gpt-image-2 接口适配层。 */
@Injectable()
export class ImageGenerationClient {
  private readonly logger = new Logger(ImageGenerationClient.name);

  constructor(
    private readonly config: AppConfigService,
    private readonly apiKeys: ApiKeyRepository,
    private readonly tasks: ImageTaskRepository,
  ) {}

  async createTask(input: CreateImageDto): Promise<{ taskId: string; status: string }> {
    const prompt = input.prompt.trim();
    if (!prompt) throw ApiErrors.badRequest(4007, "图片生成提示词不能为空");

    const reserved = await this.apiKeys.reserve(IMAGE_API_CHANNEL, QUOTA_PER_TASK);
    if (!reserved) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, 5032, "图片生成服务未配置或额度不足");
    }

    try {
      let response: Response;
      try {
        response = await fetch(`${this.config.apiSweetBaseUrl}/v1/draw/completions`, {
          method: "POST",
          headers: {
            authorization: `Bearer ${reserved.key}`,
            "content-type": "application/json",
            accept: "application/json",
            "user-agent": "TaotaoMusic/1.0",
          },
          body: JSON.stringify({
            model: "gpt-image-2",
            prompt,
            ...(input.images === undefined ? {} : { images: input.images }),
            ...(input.aspectRatio === undefined ? {} : { aspectRatio: input.aspectRatio }),
            ...(input.imageSize === undefined ? {} : { imageSize: input.imageSize }),
            ...(input.quality === undefined ? {} : { quality: input.quality }),
          }),
          signal: AbortSignal.timeout(30_000),
        });
      } catch (error) {
        const message =
          error instanceof Error && error.name === "TimeoutError"
            ? "图片生成服务请求超时"
            : "图片生成服务连接失败";
        throw ApiErrors.upstream(message);
      }

      const payload = await this.readPayload<ApiSweetCreateResponse>(response);
      if (!response.ok || payload.code !== 200) {
        this.throwUpstreamError(response.status, payload, "图片生成任务创建失败");
      }

      const taskId = String(payload.data?.task_id ?? "").trim();
      const status = String(payload.data?.status ?? "").trim();
      if (!taskId || !status) throw ApiErrors.upstream("图片生成服务返回了无效的任务信息");
      await this.tasks.create({
        taskId,
        prompt,
        consumedQuota: QUOTA_PER_TASK,
        channel: IMAGE_API_CHANNEL,
        apiKeyId: reserved.id,
      });
      return { taskId, status };
    } catch (error) {
      await this.refundQuietly(reserved.id, QUOTA_PER_TASK);
      throw error;
    }
  }

  /** 查询一次任务状态；轮询节奏由客户端控制，避免长时间占用服务端连接。 */
  async getTaskResult(rawTaskId: string): Promise<ImageTaskResult> {
    const taskId = rawTaskId.trim();
    if (!/^[A-Za-z0-9_-]{1,128}$/.test(taskId)) {
      throw ApiErrors.badRequest(4007, "图片生成任务 ID 不合法");
    }

    const storedTask = await this.tasks.find(taskId);
    if (!storedTask) throw ApiErrors.notFound(4042, "图片生成任务不存在");

    let response: Response;
    try {
      response = await fetch(`${this.config.apiSweetBaseUrl}/v1/draw/result/${encodeURIComponent(taskId)}`, {
        headers: {
          authorization: `Bearer ${storedTask.apiKey}`,
          accept: "application/json",
          "user-agent": "TaotaoMusic/1.0",
        },
        signal: AbortSignal.timeout(30_000),
      });
    } catch (error) {
      const message =
        error instanceof Error && error.name === "TimeoutError" ? "图片任务查询超时" : "图片任务查询连接失败";
      throw ApiErrors.upstream(message);
    }

    const payload = await this.readPayload<ApiSweetResultResponse>(response);
    if (!response.ok || payload.code !== 200) {
      this.throwUpstreamError(response.status, payload, "图片任务查询失败");
    }

    const state = String(payload.state ?? "");
    if (state !== "IN_PROGRESS" && state !== "COMPLETED" && state !== "FAILED") {
      throw ApiErrors.upstream("图片生成服务返回了未知的任务状态");
    }
    const returnedTaskId = String(payload.task_id ?? "").trim();
    if (!returnedTaskId || returnedTaskId !== taskId) {
      throw ApiErrors.upstream("图片生成服务返回了无效的任务信息");
    }

    const imageUrl = String(payload.result?.image_url ?? "").replace(/^http:/, "https:");
    if (state === "COMPLETED" && !imageUrl) {
      throw ApiErrors.upstream("图片生成任务已完成，但没有返回图片地址");
    }
    const errorMessage =
      typeof payload.error === "string" ? payload.error.trim() : String(payload.error?.message ?? "").trim();

    await this.tasks.updateResult(taskId, state, imageUrl || null);

    return {
      taskId: returnedTaskId,
      state,
      progress: this.numberOr(payload.progress, 0),
      createdAt: this.optionalNumber(payload.created_at),
      completedAt: this.optionalNumber(payload.completed_at),
      result: imageUrl ? { imageUrl } : null,
      error: state === "FAILED" ? { message: errorMessage || "图片生成失败" } : null,
    };
  }

  private async readPayload<T extends ApiSweetPayload>(response: Response): Promise<T> {
    try {
      return (await response.json()) as T;
    } catch {
      throw ApiErrors.upstream(`图片生成服务返回了无法解析的响应（HTTP ${response.status}）`);
    }
  }

  private throwUpstreamError(status: number, payload: ApiSweetPayload, fallback: string): never {
    const upstreamMessage = String(payload.msg ?? payload.message ?? fallback);
    if (status === HttpStatus.BAD_REQUEST) {
      throw ApiErrors.badRequest(4007, upstreamMessage);
    }
    if (status === HttpStatus.NOT_FOUND) {
      throw ApiErrors.notFound(4042, "图片生成任务不存在");
    }
    if (status === HttpStatus.TOO_MANY_REQUESTS) {
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, 4291, "图片服务请求过于频繁，请稍后再试");
    }
    // 第三方的 401/402 表示服务端密钥或余额异常，不能透传为客户端登录失效。
    throw new ApiException(HttpStatus.BAD_GATEWAY, 5021, upstreamMessage);
  }

  private numberOr(value: unknown, fallback: number): number {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : fallback;
  }

  private optionalNumber(value: unknown): number | null {
    if (value === undefined || value === null) return null;
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  private async refundQuietly(apiKeyId: number, quota: number): Promise<void> {
    try {
      await this.apiKeys.refund(apiKeyId, quota);
    } catch (error) {
      const reason = error instanceof Error ? error.message : String(error);
      this.logger.error(`图片生成额度归还失败：${reason}`);
    }
  }
}
