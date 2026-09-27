import { Injectable, Logger, OnApplicationBootstrap, OnModuleDestroy } from "@nestjs/common";
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

    constructor(private readonly config: AppConfigService) {}

    /** 加密链路是否可用。false 时上层应完全透明地走明文。 */
    get enabled(): boolean {
        return this.native !== null && this.server !== null;
    }

    onApplicationBootstrap(): void {
        this.native = loadNativeCrypto(this.logger);
        if (!this.native) return;

        this.server = new this.native.Server();
        // PSK 登记优先级：
        //   1. 配了 CRYPTO_PSK_HEX → 用它（显式，支持轮换/多密钥场景）；
        //   2. 否则 .node 内嵌了真实 PSK → 直接用内嵌那把（putEmbeddedPsk），
        //      与客户端 clientNewEmbedded 对称，构建期注入一次两端通用，无需再配 hex；
        //   3. 都没有 → 只有占位密钥，保持明文并告警。
        // psk_id 未配时默认 prod-v1，与客户端 CryptoTransport.DEFAULT_PSK_ID 一致。
        const pskId = this.config.cryptoPskId || "prod-v1";
        const pskHex = this.config.cryptoPskHex;
        if (pskHex) {
            this.server.putPsk(pskId, pskHex);
            this.logger.log(`已登记 PSK（来自环境变量，id=${pskId}）`);
        } else if (this.native.hasRealPsk() && typeof this.server.putEmbeddedPsk === "function") {
            this.server.putEmbeddedPsk(pskId);
            this.logger.log(`已登记内嵌 PSK（来自产物，id=${pskId}）`);
        } else if (this.native.hasRealPsk()) {
            // 产物版本过旧（无 putEmbeddedPsk，需 v2.2.0+）：不崩，提示改配 hex 或升级产物。
            this.logger.warn("产物内嵌了 PSK 但版本过旧（缺 putEmbeddedPsk），请升级到 v2.2.0+ 或配置 CRYPTO_PSK_HEX，暂保持明文");
        } else {
            this.logger.warn("产物无真实内嵌 PSK 且未配置 CRYPTO_PSK_HEX，握手将失败，链路保持明文");
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

    /** 处理 ClientHello，返回 ServerHello 字节。deviceId 折进握手密钥。链路未启用时返回 null。 */
    handshake(clientHello: Uint8Array, deviceId: string): Uint8Array | null {
        if (!this.server) return null;
        return this.server.accept(clientHello, deviceId, Date.now());
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
