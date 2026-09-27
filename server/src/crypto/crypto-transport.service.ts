import { Injectable, Logger, OnApplicationBootstrap, OnModuleDestroy } from "@nestjs/common";
import { randomBytes } from "crypto";
import { AppConfigService } from "../config/app-config.service";
import { loadNativeCrypto, NativeCrypto, NativeServer } from "./native-loader";

/**
 * 传输加密的服务端会话引擎。
 *
 * 单例持有一个原生 `Server`，负责握手、按会话解密请求体 / 加密响应体，并周期性清理
 * 过期会话。**优雅降级**：产物缺失或协议版本不符时 [enabled] 为 false，所有方法空转，
 * 上层中间件据此让请求走原明文路径——后端不因为没拉加密产物而不可用。
 */
@Injectable()
export class CryptoTransportService implements OnApplicationBootstrap, OnModuleDestroy {
    private readonly logger = new Logger(CryptoTransportService.name);
    private native: NativeCrypto | null = null;
    private server: NativeServer | null = null;
    private sweepTimer: NodeJS.Timeout | null = null;
    /** 当前生效的 PSK（下发给已登录客户端；见 [deliverablePsk]）。 */
    private pskId = "prod-v1";
    private pskHex = "";

    constructor(private readonly config: AppConfigService) {}

    /** 加密链路是否可用。false 时上层应完全透明地走明文。 */
    get enabled(): boolean {
        return this.native !== null && this.server !== null && this.pskHex !== "";
    }

    /**
     * 下发给客户端的 PSK。客户端不再内嵌密钥，改由后端动态下发（`plans/009` 修订）：
     * 两端用的是同一把（后端这把），密钥不可能对不上；`.so` 混淆强度有限、内嵌钥
     * 易被提取的问题也一并消除。**安全权衡**：下发经 HTTPS + 登录令牌保护，此层
     * 不再是独立于 TLS 的第二把秘密，主要提供设备绑定与抗一般篡改。
     */
    get deliverablePsk(): { pskId: string; pskHex: string } | null {
        return this.enabled ? { pskId: this.pskId, pskHex: this.pskHex } : null;
    }

    onApplicationBootstrap(): void {
        this.native = loadNativeCrypto(this.logger);
        if (!this.native) return;

        this.server = new this.native.Server();
        // PSK 来源：优先环境变量 CRYPTO_PSK_HEX；未配则启动时随机生成一把。
        // 无论哪种，都由 /crypto/psk 下发给已登录客户端，两端天然一致。
        this.pskId = this.config.cryptoPskId || "prod-v1";
        this.pskHex = this.config.cryptoPskHex || randomBytes(32).toString("hex");
        this.server.putPsk(this.pskId, this.pskHex);
        if (this.config.cryptoPskHex) {
            this.logger.log(`已登记 PSK 并开放下发（来自环境变量，id=${this.pskId}）`);
        } else {
            this.logger.warn(
                `未配置 CRYPTO_PSK_HEX，已随机生成一把 PSK（id=${this.pskId}）。` +
                    `重启会变、多实例不一致；生产建议配固定值。`,
            );
        }

        // 每分钟清理过期会话，避免长期运行内存里堆积死会话。
        this.sweepTimer = setInterval(() => {
            const removed = this.server?.sweepExpired(Date.now()) ?? 0;
            if (removed > 0) this.logger.debug(`清理过期加密会话 ${removed} 条`);
        }, 60_000);
        this.sweepTimer.unref();
    }

    onModuleDestroy(): void {
        if (this.sweepTimer) clearInterval(this.sweepTimer);
    }

    /** 处理 ClientHello，返回 ServerHello 字节。deviceId 折进握手密钥。链路未启用/失败时返回 null。 */
    handshake(clientHello: Uint8Array, deviceId: string): Uint8Array | null {
        if (!this.server) return null;
        try {
            const response = this.server.accept(clientHello, deviceId, Date.now());
            // 审计：记录握手成功的设备号（ANDROID_ID / MachineGuid），便于按设备反查流量。
            this.logger.log(`握手成功：deviceId="${deviceId}"`);
            return response;
        } catch (err) {
            // 诊断用：握手失败时把非敏感线索打出来（deviceId 不是密钥，可记录）。
            // MAC 失配最常见的成因是 deviceId 两端不一致或 PSK 版本不同。
            this.logger.warn(
                `握手失败：${(err as Error).message}；deviceId="${deviceId}"(len=${deviceId.length})，helloLen=${clientHello.length}`,
            );
            return null;
        }
    }

    /** 构造 AAD（method + path）。 */
    aad(method: string, pathAndQuery: string): Uint8Array | null {
        if (!this.native) return null;
        return this.native.aadContext(method, pathAndQuery);
    }

    /** 解析 `X-Taotao-Crypto` 头，返回 [会话ID, 序号]，非法返回 null。 */
    parseHeader(value: string): [string, string] | null {
        if (!this.native) return null;
        return this.native.parseHeader(value);
    }

    /** 解密请求体。 */
    open(sessionId: string, aad: Uint8Array, frame: Uint8Array): Uint8Array | null {
        if (!this.server) return null;
        return this.server.open(sessionId, aad, frame, Date.now());
    }

    /** 加密响应体。 */
    seal(sessionId: string, aad: Uint8Array, plaintext: Uint8Array): Uint8Array | null {
        if (!this.server) return null;
        return this.server.seal(sessionId, aad, plaintext, Date.now());
    }

    /** 会话是否有效。 */
    hasSession(sessionId: string): boolean {
        if (!this.server) return false;
        return this.server.hasSession(sessionId, Date.now());
    }
}
