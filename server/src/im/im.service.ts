import { Injectable } from "@nestjs/common";
import { createHash, randomBytes } from "node:crypto";
import { UsersRepository } from "../auth/users.repository";
import { ApiErrors } from "../common/api.exception";
import { AppConfigService } from "../config/app-config.service";
import { ImRepository } from "./im.repository";
import { WukongImClient } from "./wukong-im.client";

const ANDROID_DEVICE_FLAG = 1;
const PRIMARY_DEVICE_LEVEL = 1;

export type ImSessionBootstrap = {
  uid: string;
  token: string;
  tokenExpiresAt: number;
  deviceFlag: number;
  deviceLevel: number;
  gatewayUrl: string;
};

/** 将既有桃桃账号映射为悟空 IM 用户，并签发短期 Android 连接凭据。 */
@Injectable()
export class ImService {
  constructor(
    private readonly config: AppConfigService,
    private readonly users: UsersRepository,
    private readonly repository: ImRepository,
    private readonly wukong: WukongImClient,
  ) {}

  async createAndroidSession(userId: number, deviceId: string): Promise<ImSessionBootstrap> {
    this.requireEnabled();
    const uid = await this.users.ensureImUid(userId);
    const now = Date.now();
    const tokenExpiresAt = now + this.config.imSessionLifetimeSeconds * 1000;
    const token = randomBytes(48).toString("base64url");

    // 先让悟空 IM 接受新 Token，再写入本库；若数据库故障，不把看似有效却无法审计的
    // Token 留在运行时，尽力执行一次设备退出回滚。
    await this.wukong.registerUserToken({
      uid,
      token,
      deviceFlag: ANDROID_DEVICE_FLAG,
      deviceLevel: PRIMARY_DEVICE_LEVEL,
    });
    try {
      await this.repository.saveSession({
        userId,
        deviceFlag: ANDROID_DEVICE_FLAG,
        deviceIdHash: this.hash(deviceId),
        tokenHash: this.hash(token),
        expiresAt: tokenExpiresAt,
        now,
      });
    } catch (error) {
      await this.wukong.quitDevice(uid, ANDROID_DEVICE_FLAG).catch(() => undefined);
      throw error;
    }

    return {
      uid,
      token,
      tokenExpiresAt,
      deviceFlag: ANDROID_DEVICE_FLAG,
      deviceLevel: PRIMARY_DEVICE_LEVEL,
      gatewayUrl: this.config.imExternalGatewayUrl,
    };
  }

  async revokeAndroidSession(userId: number): Promise<void> {
    this.requireEnabled();
    const now = Date.now();
    // 悟空 IM v2 的 device_quit 以设备类别为范围，故当前 MVP 的退出会关闭该帐号全部
    // Android 连接；多 Android 设备并行是后续需要和 Gateway 设备 ID 校验一起扩展的能力。
    // 历史账号可能从未启用过聊天，退出时无需生成一个无意义的 UID；有记录时才请求悟空 IM。
    const uid = await this.users.imUidOf(userId);
    if (uid) await this.wukong.quitDevice(uid, ANDROID_DEVICE_FLAG);
    await this.repository.revokeSession(userId, ANDROID_DEVICE_FLAG, now);
  }

  private requireEnabled(): void {
    if (!this.config.imEnabled) throw ApiErrors.serviceUnavailable(5031, "聊天服务尚未启用");
  }

  private hash(value: string): string {
    return createHash("sha256").update(value).digest("hex");
  }
}
