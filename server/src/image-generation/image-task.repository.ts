import { Injectable } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

export type StoredImageTask = {
  taskId: string;
  prompt: string;
  consumedQuota: number;
  channel: string;
  state: "IN_PROGRESS" | "COMPLETED" | "FAILED";
  completed: number;
  imageUrl: string | null;
  apiKeyId: number;
  apiKey: string;
};

const TASK_COLUMNS = `
  task.task_id AS "taskId",
  task.prompt,
  task.consumed_quota AS "consumedQuota",
  task.channel,
  task.state,
  task.completed,
  task.image_url AS "imageUrl",
  task.api_key_id AS "apiKeyId",
  credential.key AS "apiKey"`;

/** 图片生成任务及其创建时所用 Key 的持久化映射。 */
@Injectable()
export class ImageTaskRepository {
  constructor(private readonly database: DatabaseService) {}

  async create(task: {
    taskId: string;
    prompt: string;
    consumedQuota: number;
    channel: string;
    apiKeyId: number;
  }): Promise<void> {
    await this.database.run(
      `INSERT INTO image_generation_task
         (task_id, prompt, consumed_quota, channel, state, completed, image_url, api_key_id)
       VALUES ($1, $2, $3, $4, 'IN_PROGRESS', 0, NULL, $5)`,
      [task.taskId, task.prompt, task.consumedQuota, task.channel, task.apiKeyId],
    );
  }

  find(taskId: string): Promise<StoredImageTask | undefined> {
    return this.database.first<StoredImageTask>(
      `SELECT ${TASK_COLUMNS}
       FROM image_generation_task AS task
       JOIN api_key AS credential ON credential.id = task.api_key_id
       WHERE task.task_id = $1`,
      [taskId],
    );
  }

  async updateResult(
    taskId: string,
    state: StoredImageTask["state"],
    imageUrl: string | null,
  ): Promise<void> {
    await this.database.run(
      `UPDATE image_generation_task
       SET state = $1, completed = $2, image_url = $3
       WHERE task_id = $4`,
      [state, state === "IN_PROGRESS" ? 0 : 1, imageUrl, taskId],
    );
  }
}
