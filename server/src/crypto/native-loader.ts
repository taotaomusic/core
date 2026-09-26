import { Logger } from "@nestjs/common";
import { existsSync } from "fs";
import { join } from "path";

/**
 * 传输加密的服务端引擎（对应 crypto/dist wasm 里的 `Server`）。
 *
 * Node 原生插件 `taotao_crypto.node` 导出的接口，与 wasm 侧一一对应。
 * 这里只声明服务端会用到的方法，方法名沿用 Rust 绑定的 snake_case。
 */
export interface NativeServer {
    /** 处理 ClientHello，返回 ServerHello。ClientHello 里携带 device_id，内部按设备现算 PSK 并把 device_id 绑定到会话。 */
    accept(client_hello: Uint8Array, now_ms: number): Uint8Array;
    /**
     * 解密请求体。AAD 由会话内部持有的 device_id + 传入的 method/path 现拼，
     * 调用方不再手工构造 AAD——避免服务端拼错顺序与客户端漂移。
     */
    open(session_id_hex: string, method: string, path_and_query: string, frame: Uint8Array, now_ms: number): Uint8Array;
    /** 加密响应体。AAD 同 open，由会话内部拼。 */
    seal(session_id_hex: string, method: string, path_and_query: string, plaintext: Uint8Array, now_ms: number): Uint8Array;
    /** 会话是否仍有效。 */
    has_session(session_id_hex: string, now_ms: number): boolean;
    /** 加入 / 更新一条 PSK（按 psk_id 登记原始 PSK，派生在 accept 时按报文里的 device_id 做）。 */
    put_psk(psk_id: string, psk_hex: string): void;
    /** 清理过期会话，返回清理数量。 */
    sweep_expired(now_ms: number): number;
    /** 当前会话数量。 */
    readonly session_count: number;
}

/**
 * 原生插件的模块导出面。
 */
export interface NativeCrypto {
    Server: new () => NativeServer;
    /** 从 `X-Taotao-Crypto` 头值解析出 [会话ID, 序号]，非法返回 null。 */
    parse_header(value: string): [string, string] | null;
    /** 协议版本。设备绑定版本要求 >= 2。 */
    protocol_version(): number;
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
        if (native.protocol_version() < 2) {
            logger.warn(`加密产物协议版本 ${native.protocol_version()} 低于设备绑定要求（>=2），降级为明文`);
            return null;
        }
        logger.log(`已加载传输加密产物 protocol v${native.protocol_version()}（${platformDir}）`);
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
