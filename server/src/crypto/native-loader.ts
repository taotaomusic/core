import { Logger } from "@nestjs/common";
import { existsSync } from "fs";
import { join } from "path";

/**
 * 传输加密的服务端引擎（对应 crypto/dist wasm 里的 `Server`）。
 *
 * Node 原生插件 `taotao_crypto.node` 导出的接口。
 * ⚠️ napi-rs 默认把 Rust 的 snake_case 导出成 **camelCase**（与 node 冒烟测试
 * `tools/smoke-test.cjs` 里的用法一致：`putPsk` / `sessionCount` / `protocolVersion`）。
 * 这里的方法名必须用 camelCase，写成 snake_case 会「不是函数」而静默降级明文。
 */
export interface NativeServer {
    /**
     * 处理 ClientHello，返回 ServerHello。`deviceId` 由握手请求携带，折进握手密钥；
     * 与客户端不一致则 MAC 失配、握手被拒（协议 v2 的设备绑定，见 plans/009）。
     */
    accept(clientHello: Uint8Array, deviceId: string, nowMs: number): Uint8Array;
    /** 解密请求体。aad 由调用方用 aadContext(method, path) 构造。 */
    open(sessionIdHex: string, aad: Uint8Array, frame: Uint8Array, nowMs: number): Uint8Array;
    /** 加密响应体。aad 同 open。 */
    seal(sessionIdHex: string, aad: Uint8Array, plaintext: Uint8Array, nowMs: number): Uint8Array;
    /** 会话是否仍有效。 */
    hasSession(sessionIdHex: string, nowMs: number): boolean;
    /** 加入 / 更新一条 PSK（登记原始 PSK，设备绑定在握手时按 deviceId 现算）。 */
    putPsk(pskId: string, pskHex: string): void;
    /** 用产物内嵌的 PSK 登记（与客户端 clientNewEmbedded 对称，无需传 hex）。 */
    putEmbeddedPsk(pskId: string): void;
    /** 清理过期会话，返回清理数量。 */
    sweepExpired(nowMs: number): number;
    /** 当前会话数量。 */
    readonly sessionCount: number;
}

/**
 * 原生插件的模块导出面（同为 camelCase）。
 */
export interface NativeCrypto {
    Server: new () => NativeServer;
    /** 构造 AAD 上下文（method + path，绑定方法与路径，不含 deviceId）。 */
    aadContext(method: string, pathAndQuery: string): Uint8Array;
    /** 从 `X-Taotao-Crypto` 头值解析出 [会话ID, 序号]，非法返回 null。 */
    parseHeader(value: string): [string, string] | null;
    /** 协议版本。设备绑定版本要求 >= 2。 */
    protocolVersion(): number;
    /** 产物是否内嵌了真实 PSK（占位密钥返回 false）。 */
    hasRealPsk(): boolean;
}

/**
 * 加载平台对应的 `taotao_crypto.node`。
 *
 * 产物由独立仓库 `hdppppppp/tools` 的 CI 交叉编译，经 `tools/fetch-crypto.ps1`
 * 落到 `crypto/dist/node/<平台>/`。**本机不编译**：找不到产物时返回 null，
 * 后端照常起（加密链路自动降级为明文，见 CryptoTransportService）。
 */
export function loadNativeCrypto(logger: Logger): NativeCrypto | null {
    const platformDir = resolvePlatformDir();
    if (!platformDir) {
        logger.warn(`当前平台 ${process.platform}-${process.arch} 无对应加密产物，传输加密降级为明文`);
        return null;
    }

    // crypto/dist 在仓库根；后端运行目录是 server/，向上一级找。
    const candidates = [
        join(process.cwd(), "..", "crypto", "dist", "node", platformDir, "taotao_crypto.node"),
        join(process.cwd(), "crypto", "dist", "node", platformDir, "taotao_crypto.node"),
    ];
    const found = candidates.find((path) => existsSync(path));
    if (!found) {
        logger.warn("未找到 taotao_crypto.node，先执行 tools/fetch-crypto.ps1 拉产物；传输加密降级为明文");
        return null;
    }

    try {
        // eslint-disable-next-line @typescript-eslint/no-var-requires
        const native = require(found) as NativeCrypto;
        if (native.protocolVersion() < 2) {
            logger.warn(`加密产物协议版本 ${native.protocolVersion()} 低于设备绑定要求（>=2），降级为明文`);
            return null;
        }
        logger.log(`已加载传输加密产物 protocol v${native.protocolVersion()}（${platformDir}）`);
        return native;
    } catch (error) {
        logger.error(`加载 taotao_crypto.node 失败，降级为明文：${(error as Error).message}`);
        return null;
    }
}

/**
 * 把 Node 的平台标识映射到产物目录名。只发布了 linux-x64 / windows-x64 两个平台。
 */
function resolvePlatformDir(): string | null {
    if (process.arch !== "x64") return null;
    if (process.platform === "linux") return "linux-x64";
    if (process.platform === "win32") return "windows-x64";
    return null;
}
